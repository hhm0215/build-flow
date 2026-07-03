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

### 능동 발의 실험 피드백 회고 — 2회차 (2026-07-03 실험 연장)
- **트리거**(갱신): 발의 5건 축적 또는 실사이클 3회 경과 중 먼저 도달 (이전 "다음 세션 시작"은 발의 0건 공회전으로 폐기)
- **1회차 결과**(2026-07-03): 발의 0건 → 측정 불가 → 실험 연장(수정) 결정 반영 완료
- **액션**: `.claude/PROGRESS.md` "능동 발의 로그" 섹션 통독 → 발의 N건 승인률·거부 사유 요약 → 사용자에게 확정/폐기/수정 3택 제시
- **결과 반영**: `docs/DECISIONS.md` ADR-014 상태 갱신 (TRIAL → CONFIRMED/DEPRECATED/REVISED) + 규칙 조정 시 CLAUDE.md "능동 발의" 섹션 편집
- **예상 규모**: S

---

## P1 — 중기

### useListFilters 추상화 (별도 PR — 큰 결정)
- **배경**: 5페이지(estimate/purchase/tax/warranty/site) ListPage가 `useFilterParams + useDebouncedValue + filtered useMemo + resetFilters + activeCount` 패턴 반복
- **산출물**: `useListFilters(schema, items, filterFn)` 훅으로 boilerplate 통합. 각 페이지 차이(검색 필드 다름, 필터 로직 다름)를 어떻게 추상화할지 설계 필요
- **예상 규모**: M (5페이지 다 손대야 함, 추상화 잘못 가면 가독성 ↓)
- **상태**: TODO — 설계부터 합의 필요 (자동 진행 위험)

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
| 2026-06-18 | warranty PDF OCR Phase 2 완료 → P0 항목을 프론트 업로드/상태 표시로 갱신 |
| 2026-06-18 | ADR-012 위임 모드 정식 도입 (BACKLOG 항목 외 메타 작업) |
| 2026-06-18 | 프론트 PDF 업로드 + OCR 상태 표시 완료 → P0 비움. P1에 Warranty 타입 일치 신규 등록 |
| 2026-06-21 | 위임 모드 5 사이클 일괄 처리 — P1 4건 완료(InputNumber/Warranty optional/SiteSelect/필터 명명+추론), useListFilters는 별도 분리 |
| 2026-06-21 | 백엔드 DefectWarranty.coverageAmount 필드 추가 완료 — 사이클 2 짝 완성 (frontend↔backend 일치) |
| 2026-06-22 | /code-review로 critical+high 7건 발견 → 사이클 A 핫픽스 완료. MEDIUM 7건은 P1 신규 등록 |
| 2026-06-22 | ADR-013 자동 코드 리뷰 5.5단계 정식 도입 (BACKLOG 항목 외 메타 작업) |
| 2026-06-22 | P1 MEDIUM 6건 fix 완료 + 5.5단계 자동 리뷰 HIGH 1건 즉시 fix. MEDIUM 2건/LOW 2건 분리 등록 |
| 2026-06-26 | P2 LOW 2건 정리 — parser 다중 라벨 순회 + isExpiringSoon dead 메서드 제거 |
| 2026-07-01 | P1 MEDIUM 2건(parser 거리 제약 + update Optional 3-state) 완료 + 5.5 리뷰 HIGH 2건(greedy group 캡처 + gap 임계값) 즉시 fix |
| 2026-07-01 | ADR-014 [TRIAL] 능동 발의 실험 도입 — 다음 세션 회고 대상, P0에 피드백 항목 등록 |
