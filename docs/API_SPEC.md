# BuildFlow API 명세서

> 서비스별 REST API 엔드포인트 목록.
> Claude Code가 Controller, OpenFeign Client를 생성할 때 참조한다.
> 모든 API는 Gateway를 통해 접근. 인증 필요 API는 Authorization: Bearer {JWT} 헤더 필수.

---

## 공통 응답 포맷

```json
// 성공
{
  "success": true,
  "data": { ... }
}

// 실패
{
  "success": false,
  "error": "현장을 찾을 수 없습니다."
}

// 목록 (페이징)
{
  "success": true,
  "data": {
    "content": [ ... ],
    "page": 0,
    "size": 20,
    "totalElements": 45,
    "totalPages": 3
  }
}
```

`data`와 `error` 중 값이 `null`인 필드는 응답 JSON에서 생략된다.

---

## 1. auth-service

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/auth/login | `{loginId, password}`로 관리자 로그인 → JWT 발급 | X |
| POST | /api/v1/auth/refresh | `{refreshToken}`으로 토큰 갱신 | X (refresh token 필요) |
| POST | /api/v1/auth/logout | Bearer access token 로그아웃 (블랙리스트 등록) | O |

공개 회원가입·최초 관리자 생성 API는 없습니다. 최초 관리자는 로컬 `scripts/create-admin.ps1` 대화형 명령으로만 생성합니다. access token은 `type=access`, `role=ADMIN`, `authVersion=2`를 포함하며 Gateway가 이를 검증합니다. 전환 이전 토큰은 유효하지 않습니다.

---

## 2. estimate-service

### 공내역서 (specifications)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/specifications | 공내역서 파일 업로드 (multipart) | O (ADMIN) |
| GET | /api/v1/specifications?siteId={id} | 현장별 공내역서 목록 | O |
| GET | /api/v1/specifications/{id} | 공내역서 상세 (파일 정보) | O |
| GET | /api/v1/specifications/{id}/download | 파일 다운로드 | O |
| DELETE | /api/v1/specifications/{id} | 삭제 | O (ADMIN) |

### 견적서 (estimates)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/estimates | 견적서 파일 업로드 + 금액 입력 (multipart) | O (ADMIN) |
| GET | /api/v1/estimates?siteId={id} | 현장별 견적서 목록 | O |
| GET | /api/v1/estimates/{id} | 견적서 상세 | O |
| PUT | /api/v1/estimates/{id} | 견적서 수정 (금액, 메모 등) | O (ADMIN) |
| GET | /api/v1/estimates/{id}/download | 파일 다운로드 | O |
| DELETE | /api/v1/estimates/{id} | DRAFT 삭제 (`CONFIRMED`는 409) | O (ADMIN) |
| GET | /api/v1/estimates/total?siteId={id} | 현장별 견적 합계 (OpenFeign용) | O |

---

## 3. site-service

### 현장 (sites)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/sites | 현장 생성 | O (ADMIN) |
| GET | /api/v1/sites | 현장 목록 (상태 필터, 검색, 페이징) | O |
| GET | /api/v1/sites/{id} | 현장 상세 (손익 포함) | O |
| PUT | /api/v1/sites/{id} | 현장 수정 | O (ADMIN) |
| PATCH | /api/v1/sites/{id}/status | 상태 변경 | O (ADMIN) |
| DELETE | /api/v1/sites/{id} | 삭제 | O (ADMIN) |
| GET | /api/v1/sites/{id}/profit | 현장 손익 상세 | O |
| GET | /api/v1/sites/dashboard | 전체 현장 종합 대시보드 | O |

### 거래처 (clients)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/clients | 거래처 등록 | O (ADMIN) |
| GET | /api/v1/clients | 거래처 목록 | O |
| GET | /api/v1/clients/{id} | 거래처 상세 (관련 현장, 미수금 합계) | O |
| PUT | /api/v1/clients/{id} | 거래처 수정 | O (ADMIN) |

---

## 4. purchase-service

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/purchases | 매입 등록. 수량은 1 이상 정수, 단가는 0 이상·정수 10자리/소수 2자리 이하, 총액은 DECIMAL(15,2) 범위 | O (ADMIN) |
| GET | /api/v1/purchases?siteId={id} | 현장별 매입 목록 | O |
| GET | /api/v1/purchases/{id} | 매입 상세 | O |
| PUT | /api/v1/purchases/{id} | 매입 수정. 현장 ID는 변경하지 않으며 등록과 같은 금액 제약 적용 | O (ADMIN) |
| DELETE | /api/v1/purchases/{id} | 삭제 | O (ADMIN) |

금액 검증 실패는 400 문자열 오류로 응답한다. 수정·삭제는 행 잠금으로 직렬화하고 증가한 revision의 outbox 이벤트를 남긴다.

---

## 5. tax-service

### 세금계산서

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/taxes | 세금계산서 등록 | O (ADMIN) |
| GET | /api/v1/taxes?siteId={id}&type={SALES/PURCHASE} | 목록 (현장, 유형 필터) | O |
| GET | /api/v1/taxes/{id} | 상세 | O |
| PUT | /api/v1/taxes/{id} | 수정. 입금 확인된 건은 409 | O (ADMIN) |
| PATCH | /api/v1/taxes/{id}/confirm-payment | 입금 확인. 요청 본문에 선택적 `paymentDate` | O (ADMIN) |
| DELETE | /api/v1/taxes/{id} | 삭제. 입금 확인된 건은 409 | O (ADMIN) |
| GET | /api/v1/taxes/outstanding?siteId={id} | 미수금 조회 (OpenFeign용) | O |

---

## 6. notification-service

### 하자보증보험

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/warranties | JSON으로 직접 등록 (ocrStatus=MANUAL) | O (ADMIN) |
| POST | /api/v1/warranties/upload | PDF 멀티파트 업로드 + OCR 비동기 처리 (202 Accepted) | O (ADMIN) |
| GET | /api/v1/warranties?siteId={id} | 현장별 보증보험 목록 | O |
| GET | /api/v1/warranties/{id} | 상세 (OCR 결과 + ocrStatus 포함) | O |
| PUT | /api/v1/warranties/{id} | 수정 (OCR 실패 시 사용자 보완) | O (ADMIN) |
| DELETE | /api/v1/warranties/{id} | 삭제 | O (ADMIN) |
| GET | /api/v1/warranties/expiring?days=30 | 만료 임박 목록 | O |

### 알림

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | /api/v1/notifications | 내 알림 목록 | O |
| PATCH | /api/v1/notifications/{id}/read | 읽음 처리 | O |
| PATCH | /api/v1/notifications/read-all | 전체 읽음 처리 | O |
| GET | /api/v1/notifications/unread-count | 읽지 않은 알림 수 | O |

---

## 7. chat-service

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/chat | 질문 전송 → SSE 스트리밍 응답 | O |
| GET | /api/v1/chat/sessions | 대화 세션 목록 | O |
| GET | /api/v1/chat/sessions/{id}/messages | 세션별 메시지 이력 | O |
| DELETE | /api/v1/chat/sessions/{id} | 세션 삭제 | O |

---

## 변경 이력

| 버전 | 날짜 | 변경 |
|------|------|------|
| v1.0 | 2026-04-04 | 초안 |
