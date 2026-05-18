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
  "data": { ... },
  "error": null
}

// 실패
{
  "success": false,
  "data": null,
  "error": {
    "code": "SITE_NOT_FOUND",
    "message": "현장을 찾을 수 없습니다."
  }
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
  },
  "error": null
}
```

---

## 1. auth-service

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/auth/signup | 회원가입 | X |
| POST | /api/v1/auth/login | 로그인 → JWT 발급 | X |
| POST | /api/v1/auth/refresh | 토큰 갱신 | O |
| POST | /api/v1/auth/logout | 로그아웃 (블랙리스트 등록) | O |
| GET | /api/v1/auth/me | 내 정보 조회 | O |

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
| DELETE | /api/v1/estimates/{id} | 삭제 | O (ADMIN) |
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
| POST | /api/v1/purchases | 매입 등록 | O (ADMIN) |
| GET | /api/v1/purchases?siteId={id} | 현장별 매입 목록 | O |
| GET | /api/v1/purchases/{id} | 매입 상세 | O |
| PUT | /api/v1/purchases/{id} | 매입 수정 | O (ADMIN) |
| DELETE | /api/v1/purchases/{id} | 삭제 | O (ADMIN) |
| GET | /api/v1/purchases/total?siteId={id} | 현장별 매입 합계 (OpenFeign용) | O |

---

## 5. tax-service

### 세금계산서

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/tax-invoices | 세금계산서 등록 | O (ADMIN) |
| GET | /api/v1/tax-invoices?siteId={id}&type={SALES/PURCHASE} | 목록 (현장, 유형 필터) | O |
| GET | /api/v1/tax-invoices/{id} | 상세 | O |
| PUT | /api/v1/tax-invoices/{id} | 수정 | O (ADMIN) |
| DELETE | /api/v1/tax-invoices/{id} | 삭제 | O (ADMIN) |
| GET | /api/v1/tax-invoices/outstanding?siteId={id} | 미수금 조회 (OpenFeign용) | O |

### 입금 확인

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/payments | 입금 확인 등록 | O (ADMIN) |
| GET | /api/v1/payments?taxInvoiceId={id} | 세금계산서별 입금 내역 | O |

---

## 6. notification-service

### 하자보증보험

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | /api/v1/warranties | PDF 업로드 + OCR 요청 | O (ADMIN) |
| GET | /api/v1/warranties?siteId={id} | 현장별 보증보험 목록 | O |
| GET | /api/v1/warranties/{id} | 상세 (OCR 결과 포함) | O |
| GET | /api/v1/warranties/{id}/download | PDF 다운로드 | O |
| GET | /api/v1/warranties/expiring | 만료 임박 목록 | O |

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
