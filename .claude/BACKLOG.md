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

(현재 없음 — chat Phase 2 2026-07-15 완료)

---

## P1 — 중기

### 실데이터 파일럿 온보딩 (현장 1개 끝까지 입력)
- **배경**: 기능 라이프사이클 전 구간 완성. 다음 완성도 요구사항은 추측이 아니라 실데이터에서 도출 — USB의 실제 현장 자료 1개를 거래처→현장→견적(공내역서 파싱)→매입→세금계산서→보증보험까지 실제로 입력
- **산출물**: 온보딩 중 드러난 갭 목록(입력 필드 부족, 현장별 문서함(원본 파일 보관 — 현재 warranty PDF만 저장됨) 필요성, 과거 현장 일괄 입력 UX 등)을 BACKLOG 신규 항목으로 전환
- **예상 규모**: S~M (사용자 참여 필요 — USB 자료 준비)
- **상태**: TODO

---

## P2 — 인프라/툴링

### chat-service Phase 3 (Phase 1·2 완료, 견고화/확장)
- **Phase 3**: 도구 확장(estimate/purchase by site) + C 폴백 라우터 + tool_call_id 견고화 — 예상 M
- **추가(Phase 2 리뷰/검증에서 이관)**: 모델 실시간 토큰 스트리밍(Ollama stream:true+tools 단일 콜 — 현재는 선택 라운드 답변 조각 relay), 프론트 per-token 전체 리스트 리렌더 최적화, buildMessages 이력 tail 쿼리(LIMIT)
- 7b 토큰 잡음("마argin율") 2026-07-04·07-15 반복 실측 → 견고화 근거

### 테스트 커버리지 확장 (파운데이션은 2026-07-04 완료)
- 파일럿(chat ToolExecutor/ToolCatalog, useListFilters) 완료 → 나머지 순수 로직으로 확장
- 후보: ProfitService 마진계산, estimate OllamaService JSON추출, tax 미수금 계산, 프론트 다른 훅
- **예상 규모**: S~M (점진적)

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
| 2026-07-04 | 테스트 파운데이션 완료 — CI(GitHub Actions) + Vitest(프론트) + 백엔드 파일럿 단위 테스트, ADR-015. chat Phase 2를 P0로 |
| 2026-07-04 | chat Phase 1 런타임 검증 통과(툴콜+Feign+세션 실동작) + 잠복 버그 3건 fix(kafka 이미지 소멸/JDBC PublicKeyRetrieval/init chat 스키마) |
| 2026-07-15 | chat Phase 2 완료(SSE 스트리밍+채팅 패널) — 5.5 리뷰 10건 전부 fix, Gateway 경유 런타임 검증 통과. P0 비움, P1에 실데이터 파일럿 온보딩 등록, Phase 3에 실시간 스트리밍 이관 |
