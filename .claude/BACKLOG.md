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

(현재 없음 — 능동 발의 실험 2026-07-04 확정 완료)

---

## P1 — 중기

(현재 없음 — useListFilters 추상화 2026-07-04 완료)

---

## P2 — 인프라/툴링

### chat-service Phase 2~3 (Phase 1은 2026-07-04 완료)
- **Phase 1 완료**: 툴콜 에이전트 코어 + 도구 4종 + 이력 저장 + `POST /api/v1/chat` (아키텍처 A, 계획 `.claude/plans/2026-07-04-chat-service.md`)
- **Phase 2**: SSE(`SseEmitter`) 스트리밍 + 프론트 채팅 UI — 예상 M
- **Phase 3**: 도구 확장(estimate/purchase by site) + C 폴백 라우터 + tool_call_id 견고화 — 예상 M
- **런타임 검증 대기**: Ollama 기동 상태에서 실제 툴콜 왕복 수기 확인 (사용자 환경)

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
| 2026-07-03 | ADR-014 [TRIAL] 1회차 회고(발의 0건) → 실험 연장, 회고 트리거 조정 (PR #35) |
| 2026-07-03 | BACKLOG 발의(실험 데이터 #2) → 미추적 AGENTS.md 버전 관리 편입 완료 |
| 2026-07-04 | P1 useListFilters 추상화 완료 — 5페이지 필터 훅 통합, 5.5 리뷰 회귀 1건 자가 fix (설계 자문 발의 #3) |
| 2026-07-04 | Gradle wrapper(8.10) 생성·커밋 완료 — 컴파일 검증은 JDK 17 필요로 후속 분리 |
| 2026-07-04 | openjdk@17 설치 + `./gradlew compileJava` 9개 서비스 컴파일 통과 — 백엔드 컴파일 검증 최초 성공, P2 닫음 |
| 2026-07-04 | 능동 발의 실험 2회차 회고 → **확정(CONFIRMED)** — ADR-014 v1.0 정식화, [TRIAL] 종료, P0 회고 항목 제거 |
| 2026-07-04 | chat-service Phase 1 완료 — 툴콜 에이전트(아키텍처 A), 도구 4종, 이력 저장. P2를 Phase 2~3로 갱신 |
