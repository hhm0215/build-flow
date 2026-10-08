# BuildFlow API 명세서

> 2026-10-03 코드 기준 구현 계약. 각 서비스의 Controller/DTO, Gateway 명시 라우트와 `frontend/src/api/` 호출을 대조했다. 아래 표에 없는 제안 경로는 구현 API가 아니다.
> 클라이언트는 Gateway의 `/api/v1` 경로로 접근한다. `auth` 이외 경로는 단일 관리자 access JWT의 `Authorization: Bearer <token>`이 필요하다. Gateway의 미인증·폐기 토큰 응답은 **401 빈 본문**일 수 있다.

---

## 공통 응답 포맷

```json
{"success":true,"data":{"id":1}}
```

서비스 내부 오류는 예를 들어 `{"success":false,"error":"현장을 찾을 수 없습니다."}` 형태다. `@JsonInclude(NON_NULL)`로 `data`/`error`의 null 필드는 생략하며, 성공 본문이 없는 삭제·로그아웃 등은 `{"success":true}`다. **현재 목록은 `data`가 배열이고 서버 페이징이 없다.** 리소스 생성은 201, 보증보험 PDF 업로드는 202, 일반 조회·수정·삭제와 동기 채팅은 200이다. `/api/v1/chat/stream`의 정상 응답은 JSON 래퍼가 아니라 `text/event-stream`이다. 서비스의 유효성 오류는 일반적으로 400, 없는 리소스는 404이며 도메인별 충돌은 해당 서비스의 오류 코드에 따른다. Gateway 자체의 401은 서비스 JSON 오류 래퍼가 아니다.

---

## 1. auth-service

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/auth/login | JSON `{loginId, password}` → `TokenResponse` | X |
| POST | /api/v1/auth/refresh | JSON `{refreshToken}` → `TokenResponse` | X (refresh token 필요) |
| POST | /api/v1/auth/logout | Bearer access token → `{"success":true}`; access token 블랙리스트 등록·refresh 토큰 제거 | Bearer access |

`TokenResponse` 필드는 `accessToken`, `refreshToken`, `tokenType`(`Bearer`), `expiresIn`(밀리초)이다. `loginId`는 영문·숫자·`.`·`_`·`-`로 된 3~50자이며 비밀번호는 필수다. 공개 회원가입·최초 관리자 생성 API는 없다. 최초 관리자는 로컬 `scripts/create-admin.ps1` 대화형 명령으로만 생성한다. access token은 `type=access`, `role=ADMIN`, `authVersion=2`를 포함하며 Gateway가 이를 검증한다. 전환 이전 토큰은 유효하지 않다.

---

## 2. estimate-service

### 공내역서 파일 파싱 (`ParseController`)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/estimates/parse | multipart `file` → `ParseResult` (`fileName`, `itemCount`, `items[]`); DB 견적 생성과 별개 | O |

`items[]`는 `itemName`, `unit`, `quantity`, `unitPrice`, `amount`를 담는다. AI 파싱 결과는 사용자 검토·견적 작성 전에는 최종 금액이나 원본 파일의 변경으로 취급하지 않는다. 이 API는 파일 다운로드·공내역서 보관 API가 아니다. 현재 파일럿 VPS에는 Ollama 모델이 없어 파싱 성공을 보장하지 않는다.

### 견적서 (estimates)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/estimates | JSON `EstimateCreateRequest` → `EstimateResponse` (201) | O |
| GET | /api/v1/estimates?siteId={id} | 선택적 현장 필터 → `EstimateResponse[]` | O |
| GET | /api/v1/estimates/{id} | `EstimateResponse` | O |
| PUT | /api/v1/estimates/{id} | JSON `EstimateUpdateRequest` → `EstimateResponse`; 확정 건 수정 불가 | O |
| PATCH | /api/v1/estimates/{id}/confirm | 본문 없음 → `EstimateResponse`; 확정 후 집계 이벤트 발행 | O |
| DELETE | /api/v1/estimates/{id} | DRAFT만 삭제; CONFIRMED는 충돌 오류 | O |

생성 요청은 `siteId`, `title`, `estimateDate`(ISO 날짜), `items[]`(1개 이상), 선택적 `memo`이다. 수정은 `siteId`를 제외한 동일 필드다. 각 항목은 `itemName`, `unit`, `quantity`(0 초과, 정수 8자리·소수 2자리), `unitPrice`(0 이상, 정수 13자리·소수 2자리)이다. 응답에는 `id`, `siteId`, `title`, `status`(`DRAFT`/`CONFIRMED`), `estimateDate`, `totalAmount`, `memo`, `items[]`, 생성·수정 시각이 포함된다. 금액은 서버가 항목으로 계산한다. 파일을 업로드하거나 다운로드하는 견적 CRUD 경로는 없다.

---

## 3. site-service

### 현장 (sites)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/sites | JSON `SiteCreateRequest` → `SiteResponse` (201) | O |
| GET | /api/v1/sites?status={status} | 선택적 상태 필터 → `SiteResponse[]`; 서버 검색·페이징 없음 | O |
| GET | /api/v1/sites/{id} | `SiteResponse` (손익 별도) | O |
| PUT | /api/v1/sites/{id} | JSON `SiteUpdateRequest` → `SiteResponse` | O |
| PATCH | /api/v1/sites/{id}/status | JSON `{status}` → `SiteResponse` | O |
| DELETE | /api/v1/sites/{id} | 물리 삭제 차단(기존 ID 409, 없는 ID 404); 보관·복원 도입 전까지 사용 금지 | O |
| GET | /api/v1/sites/{id}/profit | `ProfitResponse` | O |

생성·수정 본문은 필수 `siteName`과 선택적 `clientId`, `address`, `startDate`, `endDate`, `memo`이다. 상태 값은 `IN_PROGRESS`, `SETTLING`, `WARRANTY`, `COMPLETED`. `SiteResponse`는 `id`, `siteName`, 내장 `client`(미지정 시 null), `address`, `status`, 시작·종료일, `memo`, 생성·수정 시각을 담는다. `ProfitResponse`는 `siteId`, `totalEstimateAmount`, `totalPurchaseAmount`, `margin`, `marginRate`이다. 프론트의 현장명 검색은 현재 내려받은 목록에서 처리한다.

### 거래처 (clients)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/clients | JSON `ClientCreateRequest` → `ClientResponse` (201) | O |
| GET | /api/v1/clients | `ClientResponse[]` | O |
| GET | /api/v1/clients/{id} | `ClientResponse`; 관련 현장·미수금 집계 미포함 | O |
| PUT | /api/v1/clients/{id} | JSON `ClientUpdateRequest` → `ClientResponse` | O |

생성·수정 본문은 필수 `companyName`과 선택적 `representative`, `businessNo`, `phone`, `email`, `address`, `memo`이다. 응답에 `id`와 생성·수정 시각이 추가된다.

### 대시보드 (`DashboardController`)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | /api/v1/dashboard/stats | `DashboardStatsResponse`: 현장 수/상태별 수, 견적·매입·마진 합계, 평균 마진율, `siteProfits[]` | O |
| GET | /api/v1/dashboard/summary | `DashboardSummaryResponse`: AI `summary`, `generatedAt`; 모델 미가동 시 성공 보장 안 됨 | O |

---

## 4. purchase-service

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/purchases | JSON `PurchaseCreateRequest` → `PurchaseResponse` (201) | O |
| GET | /api/v1/purchases?siteId={id} | 선택적 현장 필터 → `PurchaseResponse[]` | O |
| GET | /api/v1/purchases/{id} | `PurchaseResponse` | O |
| PUT | /api/v1/purchases/{id} | JSON `PurchaseUpdateRequest` → `PurchaseResponse`; 현장 ID 변경 불가 | O |
| DELETE | /api/v1/purchases/{id} | 삭제 | O |

생성 본문은 필수 `siteId`, `itemName`, `quantity`(1 이상 정수), `unitPrice`(0 이상·정수 10자리/소수 2자리 이하)와 선택적 `supplier`, `purchaseDate`, `memo`이다. 수정에서는 `siteId`가 없다. 응답에는 `id`, `siteId`, 요청 필드, 계산된 `totalAmount`, 생성·수정 시각이 포함된다. 총액은 DECIMAL(15,2) 범위이며 검증 실패는 JSON 오류 래퍼의 400이다. 수정·삭제는 행 잠금으로 직렬화하고 증가한 revision의 outbox 이벤트를 남긴다.

---

## 5. tax-service

### 세금계산서

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/taxes | JSON `TaxInvoiceCreateRequest` → `TaxInvoiceResponse` (201) | O |
| GET | /api/v1/taxes?siteId={id}&type={SALES\|PURCHASE} | 두 필터 모두 선택적 → `TaxInvoiceResponse[]` | O |
| GET | /api/v1/taxes/{id} | `TaxInvoiceResponse` | O |
| PUT | /api/v1/taxes/{id} | JSON `TaxInvoiceUpdateRequest` → `TaxInvoiceResponse`; 현장 연결 유지, 입금 확인 건 수정 불가 | O |
| PATCH | /api/v1/taxes/{id}/confirm-payment | JSON `{paymentDate?}` → `TaxInvoiceResponse`; 매출 건만 가능 | O |
| DELETE | /api/v1/taxes/{id} | 삭제; 입금 확인 건 불가 | O |
| GET | /api/v1/taxes/outstanding?siteId={id} | 필수 `siteId` → `OutstandingResponse` | O |

생성 본문은 필수 `siteId`, `type`(`SALES`/`PURCHASE`), `supplyAmount`, `taxAmount`와 선택적 `counterparty`, `issueDate`, `memo`이다. 수정에서 `siteId`는 없고 나머지 금액·유형 필드는 필수다. 두 금액은 각각 0 이상·정수 13자리/소수 2자리 이하, 합계는 DECIMAL(15,2) 범위다. 응답에는 `totalAmount`, `paymentConfirmed`, `paymentDate`, 생성·수정 시각이 추가된다. `OutstandingResponse`는 `siteId`, `totalSalesAmount`, `confirmedAmount`, `outstandingAmount`, `unpaidCount`, `unpaidInvoices[]`이다. 입금 확인 불가 상태는 충돌 오류다.

---

## 6. notification-service

### 하자보증보험

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/warranties | JSON `WarrantyCreateRequest` → `WarrantyResponse` (201, `ocrStatus=MANUAL`) | O |
| POST | /api/v1/warranties/upload | multipart `siteId`, `file`(PDF) → PENDING `WarrantyResponse` (202) | O |
| GET | /api/v1/warranties?siteId={id} | 선택적 현장 필터 → `WarrantyResponse[]` | O |
| GET | /api/v1/warranties/{id} | `WarrantyResponse`; 비동기 OCR 상태 폴링에 사용 | O |
| PUT | /api/v1/warranties/{id} | JSON `WarrantyUpdateRequest` → `WarrantyResponse`; OCR 결과 수동 보완 | O |
| DELETE | /api/v1/warranties/{id} | 삭제 | O |
| GET | /api/v1/warranties/expiring?days=30 | 선택적 `days`(기본 30) → `WarrantyResponse[]` | O |

직접 등록 본문은 필수 `siteId`, `insuranceCompany`, `startDate`, `endDate`와 선택적 `policyNumber`, `coverageAmount`(원 단위 정수), `memo`이다. 수정 본문은 필수 보험사·시작일·만료일과 선택적 `policyNumber`, `coverageAmount`, `memo`이다. 수정의 선택 필드는 **미전송=기존 유지, JSON null=지움, 값=변경**의 3상태다. 응답에는 `id`, `siteId`, 날짜·금액, `daysUntilExpiry`, `expired`, `filePath`, `ocrStatus`(`PENDING`/`SUCCESS`/`FAILED`/`MANUAL`), 생성·수정 시각이 포함된다. `filePath`는 파일 다운로드 API가 아니다.

### 알림

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | /api/v1/notifications?unreadOnly=true | 선택적 읽지 않음 필터 → `NotificationResponse[]` | O |
| PATCH | /api/v1/notifications/{id}/read | 읽음 처리 | O |
| PATCH | /api/v1/notifications/read-all | 전체 읽음 처리 | O |
| GET | /api/v1/notifications/unread-count | `{"count":number}` | O |

`NotificationResponse`의 실제 JSON 필드는 `id`, `eventType`, `message`, `siteId`, `read`, `createdAt`이다(`boolean isRead` Java 필드의 직렬화 이름은 `read`). 읽음 처리 단건은 해당 응답을, 전체 읽음은 `{"success":true}`를 반환한다. 미읽음 수의 `data`는 숫자 자체가 아니라 `{"count":number}`다. 프론트는 `eventType`을 화면용 `type`으로 변환하고 count 객체를 풀어 배지 숫자로 쓴다.

---

## 7. chat-service

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/chat | JSON `{sessionId?, message}` → JSON `ChatResponse` (`sessionId`, `answer`) | O |
| POST | /api/v1/chat/stream | 같은 JSON → `text/event-stream` (`session`, `status`, `token`, `done`, `error`) | O |
| GET | /api/v1/chat/availability | 설정된 AI 모델 준비 상태 → JSON `{available: boolean}`. 채팅 입력 전 확인용이며 Gateway JWT 보호 대상 | O |

`message`는 비어 있을 수 없고 `sessionId`가 없거나 비어 있으면 새 세션을 만든다. SSE 이벤트의 `data`는 JSON이다: `session`/`done`은 `sessionId`, `status`는 `phase`, `token`은 `content`, `error`는 `code`와 `message`를 담는다. 현재 VPS 파일럿에는 Ollama 모델이 없으므로 채팅 성공은 보장되지 않는다. 세션/메시지는 내부에 저장하지만 조회·삭제 REST 경로는 아직 없다.

---

## 미구현 경로 — 계획과 구분

이전 문서에 있던 `/api/v1/specifications/**`, `/api/v1/estimates/{id}/download`, `/api/v1/estimates/total`, `/api/v1/sites/dashboard`, `/api/v1/chat/sessions/**`는 현재 Controller/Gateway 계약에 없다. `/tax-invoices/**`, `/payments/**`도 사용하지 않는다. 원본 문서 보존·파일 다운로드·검토 관계는 `docs/PRODUCT_VISION.md`와 `.claude/BACKLOG.md`의 후속 범위이지 현재 호출 가능한 API가 아니다. 클라이언트는 존재하지 않는 경로를 전제로 구현하지 않는다.

---

## 변경 이력

| 버전 | 날짜 | 변경 |
|------|------|------|
| v1.0 | 2026-04-04 | 초안 |
| v1.1 | 2026-10-03 | Controller·DTO·Gateway·프론트 기준 구현 경로/요청/응답/인증 동기화; 미구현 제안 분리 |
