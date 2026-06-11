# BuildFlow 백로그

> 요구사항 / 문제 / 다음 작업의 **단일 진실원**.
> 우선순위 순으로 정렬. 작업 시작 시 여기서 선택, 완료 시 PROGRESS.md "완료된 작업"으로 이동 후 여기서 제거.
>
> 새 항목 추가 시 형식:
> - **제목** — 한 줄 요약
>   - 배경/이유:
>   - 산출물:
>   - 관련 파일:
>   - 예상 규모: S/M/L
>   - 상태: TODO / IN_PROGRESS / BLOCKED

---

## P0 — 다음 1~2 작업

### 1. notification-service OCR + 만료 스케줄러
- **배경**: 하자보증보험 PDF 업로드는 CRUD만 됨. 보험사/보증금/기간을 손으로 입력해야 함. 만료 임박 알림도 수동.
- **산출물**:
  - Tesseract OCR로 PDF에서 보험사/보증금/시작일/만료일 자동 추출 → `Warranty` 엔티티 채움
  - `@Scheduled`로 매일 만료 D-30 / D-7 시점에 알림 자동 발송 (`warranty.expiring` 토픽)
- **관련 파일**: `notification-service/.../domain/warranty/**`
- **예상 규모**: L (OCR 처리, 스케줄러, Kafka 발행 3건)
- **상태**: TODO
- **계획 문서**: 작업 시작 시 `.claude/plans/YYYY-MM-DD-warranty-ocr-scheduler.md`로 작성

### 2. /review 후속 fix 2건 (작은 변경, 워밍업)
- **배경**: 공내역서 업로드 UI PR(#20) 머지 후 `/review`에서 발견. 단독 PR 가치는 약해 다음 작업 첫 커밋으로 묶을 만함.
- **산출물**:
  - `frontend/src/api/estimates.api.ts:43` — multipart 요청에서 `Content-Type: 'multipart/form-data'` 수동 설정 제거 (axios가 FormData 입력 시 boundary 포함해 자동 설정. 수동 헤더는 boundary 누락 위험)
  - `frontend/src/pages/estimate/UploadParseModal.tsx:55` — parse 실패 시 백엔드 `error.response?.data?.error`를 우선 노출, fallback으로 generic 메시지
- **예상 규모**: S
- **상태**: TODO

---

## P1 — 중기

### 3. 필터 리팩터 (별도 PR)
- useListFilters 추상화: 5페이지 공통 boilerplate 통합
- useFilterParams: schema에서 T 타입 추론 (interface 중복 제거)
- Warranty endStart/endEnd → expiryFrom/expiryTo 명명 일관성
- **예상 규모**: M

### 4. 견적서 작성 모달 — 현장 ID 입력 개선
- 현재 siteId 수동 InputNumber → Select(현장 목록 검색)으로 교체
- 공내역서 업로드 흐름에서도 함께 적용
- **예상 규모**: S

### 5. InputNumber parser 타입 캐스트 정리
- estimate/purchase/tax/warranty 모달 폼의 `as 0` / `as any` 캐스트 제거
- **예상 규모**: S

---

## P2 — 인프라/툴링

### 6. Gradle wrapper 설치
- `gradlew` 없음 — 백엔드 자동 컴파일 검증 불가
- **예상 규모**: S

### 7. chat-service (RAG 챗봇) 미구현
- LLM function calling + OpenFeign + SSE 스트리밍
- 본 시스템의 핵심 차별화 기능이지만 미착수
- **예상 규모**: L

---

## 보류 / 결정 대기

(없음)

---

## 변경 이력

| 날짜 | 작업 |
|------|------|
| 2026-06-10 | 초기 작성 — PROGRESS.md "다음 작업" 섹션에서 이관 |
