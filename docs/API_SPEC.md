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
| PUT | /api/v1/estimates/{id} | JSON `EstimateUpdateRequest` → `EstimateResponse`; DRAFT만 수정(CONFIRMED·SUPERSEDED 409) | O |
| PATCH | /api/v1/estimates/{id}/confirm | 본문 없음 → `EstimateResponse`; 확정 후 집계 이벤트 발행. 같은 수정 체인의 기존 확정본은 SUPERSEDED | O |
| DELETE | /api/v1/estimates/{id} | DRAFT만 삭제; CONFIRMED·SUPERSEDED는 409 | O |
| GET | /api/v1/estimates/profit-source?siteId={id} | 손익 재계산 원천(운영용, 프론트 미사용) → `{siteId, confirmedTotalAmount, confirmedEstimateCount, profitEvents[{eventId, eventType}]}` | O |

생성 요청은 `siteId`, `title`, `estimateDate`(ISO 날짜), `items[]`(1개 이상), 선택적 `memo`, 선택적 관계 `kind`(`ORIGINAL`/`REVISION`/`ADDITIONAL`, 생략 시 ORIGINAL)·`revisionOfId`이다. 수정은 `siteId`·관계를 제외한 동일 필드다. 각 항목은 `itemName`, `unit`, `quantity`(0 초과, 정수 8자리·소수 2자리), `unitPrice`(0 이상, 정수 13자리·소수 2자리)이다. 응답에는 `id`, `siteId`, `title`, `status`(`DRAFT`/`CONFIRMED`/`SUPERSEDED`), `estimateDate`, `totalAmount`, `memo`, `kind`, `revisionOfId`(nullable), `chainRootId`(수정 체인 루트, 원본·추가공사는 자기 id), `supersededById`(nullable), `items[]`, 생성·수정 시각이 포함된다.

수정본·추가공사(2026-10-23): `REVISION`·`ADDITIONAL`은 `revisionOfId`가 같은 현장의 CONFIRMED 또는 SUPERSEDED 견적이어야 한다(없음·다른 현장·초안·`ORIGINAL`에 `revisionOfId`·`kind` 없이 `revisionOfId`만 지정은 400). 수정본은 원 견적의 체인에 들어가고 추가공사는 새 체인을 시작한다. 확정 시 같은 체인의 기존 CONFIRMED를 SUPERSEDED로 바꾸고(`supersededById` 기록) `estimate.deleted`(payload `reason: "SUPERSEDED"`, `supersededById`, 대체되는 견적의 `totalAmount`)와 새 견적의 `estimate.parsed`를 한 트랜잭션 outbox로 발행한다. 한 체인에서 손익에 반영되는 확정본은 최대 1개이며 추가공사는 별도 합산된다. 체인 확정은 루트 행 비관적 잠금으로 직렬화한다. 기존 행(`kind` NULL)은 ORIGINAL로 응답한다. 항목 복사는 프론트가 원 견적 항목으로 생성 요청을 채우는 방식이며 별도 복제 API는 없다. 금액은 서버가 항목으로 계산한다. 견적 생성 전 site-service에서 현장 ID를 확인하며 없는 현장은 400, 현장 확인 서비스 장애나 불일치 응답은 503으로 거부하고 견적을 저장하지 않는다. 파일을 업로드하거나 다운로드하는 견적 CRUD 경로는 없다.

---

## 3. site-service

### 현장 (sites)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/sites | JSON `SiteCreateRequest` → `SiteResponse` (201) | O |
| GET | /api/v1/sites?status={status}&archived={true\|false} | 두 필터 모두 선택적 → `SiteResponse[]`; `archived` 미지정은 보관 여부와 무관한 전체(기존 계약); 서버 검색·페이징 없음 | O |
| GET | /api/v1/sites/{id} | `SiteResponse` (손익 별도) | O |
| PUT | /api/v1/sites/{id} | JSON `SiteUpdateRequest` → `SiteResponse` | O |
| PATCH | /api/v1/sites/{id}/status | JSON `{status}` → `SiteResponse`; 보관된 현장은 409 | O |
| PATCH | /api/v1/sites/{id}/archive | 본문 없음 → `SiteResponse`; 보관(멱등, 최초 `archivedAt` 유지) | O |
| PATCH | /api/v1/sites/{id}/restore | 본문 없음 → `SiteResponse`; 복원(`archivedAt=null`) | O |
| DELETE | /api/v1/sites/{id} | 물리 삭제 차단(기존 ID 409, 없는 ID 404); 정리는 보관을 사용 | O |
| GET | /api/v1/sites/{id}/profit | `ProfitResponse` | O |
| POST | /api/v1/sites/{id}/profit/reconcile?apply={false\|true} | 운영 절차용 손익 재계산(프론트 미노출) → `ProfitReconcileResponse`; 기본 dry-run, `apply=true`일 때만 반영 | O |

생성·수정 본문은 필수 `siteName`과 선택적 `clientId`, `address`, `startDate`, `endDate`, `memo`이다. 상태 값은 `IN_PROGRESS`, `SETTLING`, `WARRANTY`, `COMPLETED`. `SiteResponse`는 `id`, `siteName`, 내장 `client`(미지정 시 null), `address`, `status`, 시작·종료일, `memo`, 생성·수정 시각을 담는다. `ProfitResponse`는 `siteId`, `totalEstimateAmount`, `totalPurchaseAmount`, `margin`, `marginRate`이다. `marginRate`는 소수 2자리 %이며 저장 범위(DECIMAL(5,2)) 때문에 −999.99에서 하한 처리된다(매입이 견적의 약 11배 이상인 손실 현장). 정확한 손실은 `margin` 금액으로 본다. 프론트의 현장명 검색은 현재 내려받은 목록에서 처리한다. `SiteResponse.archivedAt`은 보관 시각(KST, 미보관 null)이며 업무 단계 `status`와 별개다. 보관된 현장은 수정·상태 변경이 409이고, 매입·견적·세금계산서·보증 생성은 해당 서비스에서 400으로 거부된다. 보관 현장 소속 기존 문서의 수정·삭제(견적 확정, 보증 수정 포함)는 각 서비스에서 409이며, 매출 세금계산서 입금 확인만 허용한다. 현장 검증 도입 전 생긴 고아 문서(현장 404)는 정정할 수 있도록 막지 않는다. 보관해도 기존 자료·손익 조회는 유지된다(ADR-021).

**손익 재계산(2026-10-23)**: `POST /sites/{id}/profit/reconcile`은 `docs/DATABASE_OPERATIONS.md` "복원 후 재조정"의 `UNEXPLAINED` 조치다. 현장 행 비관적 잠금(손익 소비자와 같은 잠금)을 잡은 채 estimate-service `GET /estimates/profit-source`(CONFIRMED 합계 — DRAFT·SUPERSEDED 제외 — 와 그 견적들의 손익 outbox eventId)와 purchase-service `GET /purchases/profit-source`(현재 매입의 금액·revision)를 Feign으로 조회한다(각 connect 2s/read 5s). 응답 `ProfitReconcileResponse`는 `siteId`, `applyRequested`, `applied`, `matched`, `reconcileId`, `before`/`source`(각 `totalEstimateAmount`, `totalPurchaseAmount`, `margin`, `marginRate`), `estimateEventsToMark`, `purchaseProjectionsToRewrite`이다. `apply=true`이고 차이가 있을 때만 ① 원천 합계로 `site_profits`를 덮어쓰고(마진·마진율은 이벤트 경로와 같은 규칙, 마진율 −999.99 하한) ② 원천 스냅샷의 미수신 견적 eventId를 `processed_profit_events`에 기록해 늦게 도착해도 무시되게 하며 ③ 매입 projection을 원천 revision·금액으로 다시 쓰고, 원천에 없는(삭제된) 활성 projection은 최대 revision 삭제 상태로 고정한다. 일치하면 아무것도 쓰지 않는다. 원천 조회 실패·`success=false`·형식 이상(현장 불일치, eventId 형식, 매입 합계≠항목 합)은 503, 원천 합계가 DECIMAL(15,2)를 넘거나 같은 매입 id의 projection이 다른 현장에 있으면 409, 없는 현장은 404이며 모두 변경 없음. 실행마다 금액·건수만 로그로 남긴다.

### 거래처 (clients)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/clients | JSON `ClientCreateRequest` → `ClientResponse` (201) | O |
| GET | /api/v1/clients | `ClientResponse[]` | O |
| GET | /api/v1/clients/{id} | `ClientResponse`; 관련 현장·미수금 집계 미포함 | O |
| PUT | /api/v1/clients/{id} | JSON `ClientUpdateRequest` → `ClientResponse` | O |
| PATCH | /api/v1/clients/{id}/archive | 본문 없음 → `ClientResponse`; 보관(멱등) | O |
| PATCH | /api/v1/clients/{id}/restore | 본문 없음 → `ClientResponse`; 복원 | O |

생성·수정 본문은 필수 `companyName`과 선택적 `representative`, `businessNo`, `phone`, `email`, `address`, `memo`이다. 응답에 `id`와 생성·수정 시각이 추가된다. 응답의 `archivedAt`은 보관 시각(미보관 null)이다. 목록은 선택적 `archived=true|false` 필터를 받으며 미지정은 전체다. 보관된 거래처는 수정 409, 현장 생성·수정에서 새로 연결하면 400이며, 이미 연결된 현장은 같은 거래처를 유지한 채 수정할 수 있다.

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
| PUT | /api/v1/purchases/{id}/evidence | JSON `{taxInvoiceId}` → `PurchaseResponse`; 같은 현장 매입 세금계산서를 증빙으로 연결(재연결 시 교체) | O |
| DELETE | /api/v1/purchases/{id}/evidence | 증빙 연결 해제 → `PurchaseResponse` | O |
| GET | /api/v1/purchases/evidence-summary?siteId={id} | 필수 `siteId` → `PurchaseEvidenceSummaryResponse`(매입 ↔ 매입 계산서 대조) | O |
| GET | /api/v1/purchases/evidence-links?taxInvoiceId={id} | `{taxInvoiceId, linkedPurchaseCount}`; tax-service의 삭제·유형 변경 전 확인용 | O |
| GET | /api/v1/purchases/profit-source?siteId={id} | 손익 재계산 원천(운영용, 프론트 미사용) → `{siteId, totalAmount, purchases[{purchaseId, revision, totalAmount}]}` (삭제된 매입 제외) | O |

생성 본문은 필수 `siteId`, `itemName`, `quantity`(1 이상 정수), `unitPrice`(0 이상·정수 10자리/소수 2자리 이하)와 선택적 `supplier`, `purchaseDate`, `memo`이다. 수정에서는 `siteId`가 없다. 응답에는 `id`, `siteId`, 요청 필드, 계산된 `totalAmount`, 생성·수정 시각이 포함된다. 총액은 DECIMAL(15,2) 범위이며 검증 실패는 JSON 오류 래퍼의 400이다. 매입 생성 전 site-service에서 현장 ID를 확인하며 없는 현장은 400, 현장 확인 서비스 장애나 불일치 응답은 503으로 거부하고 매입/outbox를 저장하지 않는다. 수정·삭제는 행 잠금으로 직렬화하고 증가한 revision의 outbox 이벤트를 남긴다.

**증빙 세금계산서 연결(2026-10-23)**: 원가의 단일 진실원은 매입 항목이며 매입 세금계산서는 원가에 더하지 않고 증빙으로만 연결한다. `PurchaseResponse.evidenceTaxInvoiceId`(nullable)로 매입 1건은 계산서 최대 1건, 계산서 1건은 같은 현장 매입 0..N건과 연결된다. 연결 시 purchase-service가 tax-service `GET /taxes/{id}`로 확인해 없는 계산서·매출 계산서·다른 현장 계산서는 400, tax-service 장애·불일치 응답은 503(fail-closed)이다. 보관 현장의 매입은 연결·해제 409. 연결·해제는 손익 이벤트를 만들지 않는다. `PurchaseEvidenceSummaryResponse`는 `purchaseCount/Amount`, `linkedPurchaseCount/Amount`, `unlinkedPurchaseCount/Amount`(끊어진 연결 포함), `brokenLinkCount`(연결 계산서가 삭제·유형 변경된 경합 잔여), `invoiceCount`, `invoiceSupplyAmount`, `invoiceTotalAmount`, `invoiceWithoutPurchaseCount`, `supplyDifference`/`totalDifference`(= 계산서 공급가/합계 − 연결 매입 합계), 계산서별 `invoices[]`이다. 요약은 tax-service `GET /taxes?siteId&type=PURCHASE`를 읽으며 장애 시 503.

---

## 5. tax-service

### 세금계산서

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/taxes | JSON `TaxInvoiceCreateRequest` → `TaxInvoiceResponse` (201) | O |
| GET | /api/v1/taxes?siteId={id}&type={SALES\|PURCHASE} | 두 필터 모두 선택적 → `TaxInvoiceResponse[]` | O |
| GET | /api/v1/taxes/{id} | `TaxInvoiceResponse` | O |
| PUT | /api/v1/taxes/{id} | JSON `TaxInvoiceUpdateRequest` → `TaxInvoiceResponse`; 현장 연결 유지, 유효 입금이 있으면 409 | O |
| PATCH | /api/v1/taxes/{id}/confirm-payment | 하위 호환. JSON `{paymentDate?}` → `TaxInvoiceResponse`; 남은 잔액 전부를 입금 1건으로 기록, 매출 건만 | O |
| GET | /api/v1/taxes/{id}/payments | 입금 내역(취소 포함, 입금일·ID 오름차순) → `TaxPaymentResponse[]` | O |
| POST | /api/v1/taxes/{id}/payments | JSON `{amount, paymentDate, memo?}` → `TaxPaymentResponse` (201); 잔액 초과 400 | O |
| PATCH | /api/v1/taxes/{id}/payments/{paymentId}/cancel | JSON `{reason?}`(본문 생략 가능) → `TaxPaymentResponse`; 삭제 아닌 취소 | O |
| DELETE | /api/v1/taxes/{id} | 삭제; 유효 입금이 있으면 409(취소된 입금 행은 함께 삭제), 매입 증빙으로 연결된 매입 계산서는 409 | O |
| GET | /api/v1/taxes/outstanding?siteId={id} | 필수 `siteId` → `OutstandingResponse` | O |

생성 본문은 필수 `siteId`, `type`(`SALES`/`PURCHASE`), `supplyAmount`, `taxAmount`와 선택적 `counterparty`, `issueDate`, `memo`이다. 수정에서 `siteId`는 없고 나머지 금액·유형 필드는 필수다. 두 금액은 각각 0 이상·정수 13자리/소수 2자리 이하, 합계는 DECIMAL(15,2) 범위다. 응답에는 `totalAmount`, `paymentConfirmed`(유효 입금 합계 = 총액), `paymentDate`(완료 시 마지막 유효 입금일, 아니면 null), `paidAmount`·`remainingAmount`(매출만, 매입은 null), `paymentStatus`(`UNPAID`/`PARTIAL`/`PAID`, 매입은 null), 생성·수정 시각이 추가된다. `OutstandingResponse`는 `siteId`, `totalSalesAmount`, `confirmedAmount`(유효 입금 합계), `outstandingAmount`(= 총액 − 유효 입금), `unpaidCount`·`unpaidInvoices[]`(잔액이 남은 매출 건, 발행일 오름차순)이다.

**분할 입금(2026-10-23)**: 입금 `amount`는 0 초과·정수 13자리/소수 2자리 이하(DECIMAL(15,2), 반올림 없음), `paymentDate` 필수, `memo`·취소 `reason`은 500자 이하다. 계산서 행 비관적 잠금 아래에서 유효 입금 합계를 다시 읽어 잔액 초과(완료 건 추가 입금 포함)는 400, 매입 계산서는 409, 이미 취소된 입금 재취소는 409, 다른 계산서의 입금 ID는 404다. `TaxPaymentResponse`는 `id`, `taxInvoiceId`, `amount`, `paymentDate`, `memo`, `canceled`, `canceledAt`, `cancelReason`, `createdAt`이다. 보관 현장의 계산서는 입금 기록·`confirm-payment`는 허용, 입금 취소는 409(복원 후 정정). `tax.payment.confirmed`는 입금 합계가 총액에 도달해 완료 상태로 바뀔 때만 발행한다(분할 입금 건별 이벤트 없음, 취소 후 재완료 시 다시 1회). 입금 원장 도입 전 입금 확인된 계산서는 기동 시 총액 1건으로 이관되며, 이관 전에도 조회는 총액 입금으로 계산한다. 세금계산서 생성 전 site-service에서 현장 ID를 확인하며 없는 현장은 400, 현장 확인 서비스 장애나 불일치 응답은 503으로 거부하고 세금계산서/outbox를 저장하지 않는다.

**매입 증빙 연결 보호(2026-10-23)**: 매입 계산서 삭제와 `PURCHASE`→`SALES` 유형 변경 전 purchase-service `GET /purchases/evidence-links`로 연결 매입 건수를 확인해 1건 이상이면 409(매입 화면에서 연결 해제 후 가능), purchase-service 장애·불일치 응답은 503(fail-closed)이다. 금액만 바꾸는 수정은 막지 않으며 차이는 대조 요약에 드러난다.

---

## 6. notification-service

### 하자보증보험

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/warranties | JSON `WarrantyCreateRequest` → `WarrantyResponse` (201, `ocrStatus=MANUAL`) | O |
| POST | /api/v1/warranties/upload | multipart `siteId`, `file`(PDF) → PENDING `WarrantyResponse` (202) | O |
| GET | /api/v1/warranties?siteId={id} | 선택적 현장 필터 → `WarrantyResponse[]` | O |
| GET | /api/v1/warranties/{id} | `WarrantyResponse`; 비동기 OCR 상태 폴링에 사용 | O |
| GET | /api/v1/warranties/{id}/history | `WarrantyAuditResponse[]` 시간순; 직접 등록·업로드·OCR 반영/실패·수동 수정·삭제의 변경 후 값 스냅샷. 삭제된 보증도 이력이 있으면 반환 | O |
| GET | /api/v1/warranties/{id}/file | 업로드 PDF 사본을 첨부파일로 스트리밍; 관리자 access JWT·로그아웃 토큰 폐기를 notification-service에서 재검증 | O |
| PUT | /api/v1/warranties/{id} | JSON `WarrantyUpdateRequest` → `WarrantyResponse`; OCR 결과 수동 보완 | O |
| DELETE | /api/v1/warranties/{id} | 삭제 | O |
| GET | /api/v1/warranties/expiring?days=30 | 선택적 `days`(기본 30) → `WarrantyResponse[]` | O |

직접 등록 본문은 필수 `siteId`, `insuranceCompany`, `startDate`, `endDate`와 선택적 `policyNumber`, `coverageAmount`(원 단위 정수), `memo`이다. 수정 본문은 필수 보험사·시작일·만료일과 선택적 `policyNumber`, `coverageAmount`, `memo`이다. 수정의 선택 필드는 **미전송=기존 유지, JSON null=지움, 값=변경**의 3상태다. 응답에는 `id`, `siteId`, 날짜·금액, `daysUntilExpiry`, `expired`, `expiryConfirmed`, `ocrStatus`(`PENDING`/`SUCCESS`/`FAILED`/`MANUAL`), 생성·수정 시각이 포함된다. `SUCCESS`는 필수 필드 추출 완료일 뿐 사용자 확정이 아니며 `expiryConfirmed=false`; 이때 `expired=false`와 `daysUntilExpiry=0`은 만료 사실을 뜻하지 않는다. 사용자가 원본 대조 후 수정/확인 저장한 `MANUAL`과 유효한 필수 필드가 있을 때만 만료 판단·알림에 사용한다. 서버 내부 `filePath`는 응답하지 않는다. `hasUploadedPdf`는 업로드 사본의 DB 연결 여부만 뜻하며 실제 파일이 없으면 다운로드는 404다. 다운로드는 `no-store`·`nosniff`와 동시 요청 상한을 적용한다. PDF 업로드는 20 MiB, 10페이지, 300 DPI 기준 렌더링 1,200만 픽셀/변 5,000픽셀 한도로 거부될 수 있다. OCR 대기열 포화 시 수동 보정 가능한 `FAILED`, 접수 후 30분 초과 `PENDING`도 `FAILED`로 전환된다. 직접 등록과 PDF 업로드는 site-service에서 현장 ID를 먼저 확인하며 없는 현장은 400, 현장 확인 서비스 장애나 불일치 응답은 503으로 거부한다. 업로드는 이 검증이 끝나기 전 PDF를 저장하거나 OCR을 예약하지 않는다. 감사 이력(`warranty_audit_events`)은 추가 전용이며 `action`(CREATED/UPLOADED/OCR_APPLIED/OCR_FAILED/MANUAL_UPDATED/DELETED), `actor`(Gateway `X-User-Id`, 요청 밖 처리는 `system`, 형식 불일치·누락은 `unknown`), 보험사·증권번호·기간·금액·`ocrStatus`·`expiryConfirmedAt`, `occurredAt`(KST)을 담는다. 공유 관리자 계정이라 actor는 계정 단위이며 실제 사람을 증명하지 않는다. 파일 경로는 담지 않는다.

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
| v1.2 | 2026-10-23 | 견적 수정본·추가공사 관계(`kind`·`revisionOfId`·`chainRootId`·`supersededById`)와 SUPERSEDED 상태 |
| v1.3 | 2026-10-23 | 운영용 손익 재계산 `POST /sites/{id}/profit/reconcile`과 원천 조회 `GET /estimates/profit-source`·`GET /purchases/profit-source` |
