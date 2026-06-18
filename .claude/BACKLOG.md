# BuildFlow 백로그

> 요구사항 / 문제 / 다음 작업의 **단일 진실원**.
> 우선순위 그룹(P0/P1/P2) 안에서 위에서 아래 순으로 진행. 글로벌 번호 없음 — 추가/제거 시 재매김 불필요.
> 작업 시작 시 여기서 선택, 완료 시 PROGRESS.md "완료된 작업"으로 이동 후 여기서 제거.
>
> 새 항목 형식:
> - **제목** (헤딩)
>   - 배경/이유
>   - 산출물
>   - 관련 파일
>   - 예상 규모: S/M/L
>   - 상태: TODO / IN_PROGRESS / BLOCKED

---

## P0 — 다음 1~2 작업

### notification-service Phase 2 — PDF OCR 자동 채움
- **배경**: Phase 1(만료 스케줄러 + Kafka 발행)은 2026-06-18 완료. Phase 2는 PDF 업로드 + 텍스트 자동 추출
- **산출물**:
  - `build.gradle`에 Tess4J + PDFBox 의존성
  - `DefectWarranty`에 `ocrStatus` ENUM 필드 (PENDING/SUCCESS/FAILED)
  - `WarrantyOcrService`: PDFBox 텍스트 1차 추출 → 부족 시 Tess4J 2차 → 정규식 파싱 (보험사/증권번호/시작·만료일)
  - `POST /api/v1/warranties/upload` 멀티파트 엔드포인트 + `@Async` 비동기 처리
  - `Dockerfile`에 Tesseract 바이너리 + 한글 traineddata(`kor.traineddata`) 설치
  - `app.upload-dir` 설정 (로컬 파일시스템, S3 전환 가능 구조)
- **관련 파일**: `notification-service/.../domain/warranty/**`, `Dockerfile`
- **예상 규모**: M~L
- **상태**: TODO
- **선행**: Phase 1 PR 머지 후 진입
- **계획 문서**: 작업 시작 시 `.claude/plans/YYYY-MM-DD-warranty-ocr-phase2.md`로 작성

---

## P1 — 중기

### 필터 리팩터 (별도 PR)
- useListFilters 추상화: 5페이지 공통 boilerplate 통합
- useFilterParams: schema에서 T 타입 추론 (interface 중복 제거)
- Warranty endStart/endEnd → expiryFrom/expiryTo 명명 일관성
- **예상 규모**: M

### 견적서 작성 모달 — 현장 ID 입력 개선
- 현재 siteId 수동 InputNumber → Select(현장 목록 검색)으로 교체
- 공내역서 업로드 흐름에서도 함께 적용
- **예상 규모**: S

### InputNumber parser 타입 캐스트 정리
- estimate/purchase/tax/warranty 모달 폼의 `as 0` / `as any` 캐스트 제거
- **예상 규모**: S

---

## P2 — 인프라/툴링

### Gradle wrapper 설치
- `gradlew` 없음 — 백엔드 자동 컴파일 검증 불가
- **예상 규모**: S

### chat-service (RAG 챗봇) 미구현
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
| 2026-06-10 | /review 후속 fix 2건 완료 → 제거 |
| 2026-06-10 | PR 생성 자동화 + main 보호 룰 도입 (BACKLOG 항목 외 메타 작업) |
| 2026-06-10 | 글로벌 번호 제거 (#1, #2 같은 번호 매김 폐기, 헤딩만 사용) — drift 정렬 |
| 2026-06-13 | PR 머지 자동화 도입 (BACKLOG 항목 외 메타 작업) — ADR-011 v2 |
| 2026-06-14 | docker-compose obsolete `version:` 제거 + `.env.example` 신설 (윈도우 이동 전 정리) |
| 2026-06-18 | warranty 만료 스케줄러 + Kafka 발행 Phase 1 완료 → P0 항목을 Phase 2(OCR)로 갱신 |
