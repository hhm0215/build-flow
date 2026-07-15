# chat-service — 툴콜 에이전트 챗봇 (RAG 대체)

- **시작일**: 2026-07-04
- **BACKLOG 항목**: P2 "chat-service (RAG 챗봇) 미구현"
- **예상 규모**: L → Phase 분할
- **상태**: IN_PROGRESS (Phase 1 완료 2026-07-04, Phase 2~3 남음)

## 목표

"이 현장 마진 얼마?", "미수금 총액은?" 같은 자연어 질문에 **실데이터 기반**으로 답하는 챗봇 서비스.
LLM이 도구를 골라 OpenFeign으로 실데이터를 조회하고 한국어로 답변을 조합, SSE로 스트리밍.
Phase 1(비스트리밍 툴콜 왕복 + 2~3개 도구 + 이력 저장)이 끝나면 "동작하는 챗봇" 완료로 본다.

## 배경 / 동기

- 설계 자문(2026-07-04)에서 **아키텍처 A(툴콜 에이전트)** 확정. 클래식 RAG(벡터스토어)는 반려 —
  데이터가 구조화된 소규모라 정밀 쿼리가 필요하고 퍼지 벡터 검색은 부적합. ARCHITECTURE.md도 "즉시 데이터 = OpenFeign" 명시.
- 벡터스토어는 지금 도입 안 함. 나중에 비정형 문서 Q&A(공내역서 원문 검색)가 필요해지면 그때 증설.

## 접근법

### 아키텍처 A — 툴콜 에이전트 (+ C 폴백)

```
POST /api/v1/chat  { sessionId, message }
  1. 세션 이력 로드 (Redis chat:session:{id}, miss 시 MySQL)
  2. LLM 1차 호출: system(도구 카탈로그) + 이력 + 질문 → 도구 선택(tool_calls)
  3. chat-service가 선택된 도구를 OpenFeign으로 실행 → 결과 JSON
  4. LLM 2차 호출: 도구 결과를 넣어 한국어 답변 조합
  5. 답변 저장(Redis + MySQL) → 반환(Phase 1 비스트리밍 / Phase 2 SSE)
  * 폴백(C): LLM이 도구 선택 실패/환각 시 규칙 기반 인텐트 라우터로 도구 강제 매핑
```

- **LLM**: 기존 Ollama(qwen2.5:7b), WebClient `/api/chat`. qwen2.5 tools 포맷 사용. estimate-service `OllamaService` 패턴 재사용.
- **도구 → 기존 엔드포인트 정합** (Feign 클라이언트):
  | 도구 | 대상 | 엔드포인트 |
  |------|------|-----------|
  | `getSiteProfit(siteId)` | site | `GET /api/v1/sites/{id}/profit` |
  | `getOutstandingTax()` | tax | `GET /api/v1/taxes/outstanding` (미수금) |
  | `getDashboardSummary()` | site | `GET /api/v1/dashboard/summary` |
  | `listSites(status?)` | site | `GET /api/v1/sites` |
  | `listEstimatesBySite(siteId)` | estimate | `GET /api/v1/estimates?siteId=` (Phase 3) |
  | `listPurchasesBySite(siteId)` | purchase | `GET /api/v1/purchases?siteId=` (Phase 3) |

- **SSE**: Spring **MVC `SseEmitter`** 채택 — 다른 서비스가 전부 MVC라 일관성 우선. Ollama `stream:true` 토큰을 Emitter로 relay. (WebFlux `Flux`는 스트리밍이 자연스럽지만 스택 이질성 비용이 커서 반려.)
- **세션/이력**: Redis `chat:session:{id}` 30분(활성 대화 컨텍스트) + MySQL `buildflow_chat`(영구 이력). 엔티티 `ChatSession`, `ChatMessage(role, content, toolCalls, createdAt)`.
- **인프라**: 포트 8087, 스키마 buildflow_chat, Eureka 등록, Gateway `/api/v1/chat/**`(ARCHITECTURE.md에 이미 라우트 존재), AuthorizationHeaderFilter 적용.

### Phase 분할
- **Phase 1 (동작하는 챗봇 최소)**: 서비스 스캐폴딩(포트/DB/Eureka/Gateway) + Ollama 툴콜 1왕복(비스트리밍) + 도구 3개(getSiteProfit/getOutstandingTax/getDashboardSummary) + 이력 저장(Redis+MySQL).
- **Phase 2 (스트리밍)**: SseEmitter로 답변 토큰 스트리밍 + 프론트 채팅 UI.
- **Phase 3 (확장/견고화)**: 도구 추가(estimate/purchase by site) + C 폴백 라우터 + 툴콜 실패/타임아웃 처리 강화.

## 산출물 체크리스트 (Phase 1)

- [ ] `chat-service` 모듈 신설 (settings.gradle include, build.gradle 의존성: web, feign, data-jpa, redis, webflux(WebClient))
- [ ] 인프라: application.yml(포트 8087, buildflow_chat, ollama, eureka), Gateway 라우트 확인/추가
- [ ] `ChatSession`/`ChatMessage` 엔티티 + JPA repo, Redis 세션 저장소
- [ ] Feign 클라이언트 3종(site/tax) + DTO
- [ ] `OllamaToolService` — 툴콜 왕복(1차 선택 → 도구 실행 → 2차 답변), estimate `OllamaService` 패턴 참고
- [ ] `POST /api/v1/chat` + `ApiResponse<ChatResponse>` 래퍼
- [ ] 문서: ARCHITECTURE.md 갱신, PROGRESS/BACKLOG 반영

## 리스크 / 모르는 것

- **qwen2.5:7b 툴콜 신뢰도**: 로컬 7b는 tool_calls 포맷을 놓치거나 환각할 수 있음 → Phase 3 C 폴백 전, Phase 1에서도 "도구 미선택 시 일반 답변"으로 graceful degrade.
- **JDK/빌드**: 새 모듈도 Java 17 + `./gradlew :chat-service:compileJava`로 검증(JAVA_HOME=openjdk@17).
- **Feign 인증 전파**: 다른 서비스가 AuthorizationHeaderFilter 뒤에 있으므로 chat→타서비스 호출 시 JWT 전파 방식 확인 필요(기존 서비스 간 Feign 호출 패턴 따름).
- **Ollama 미기동/타임아웃**: estimate-service처럼 `BusinessException` + ErrorCode로 처리.

## 테스트 / 검증

- `./gradlew :chat-service:compileJava` 통과 (JDK 17)
- Ollama 기동 상태에서 `POST /api/v1/chat`로 "○○현장 마진?" → getSiteProfit 호출 → 한국어 답변 왕복 수기 확인
- 5.5 자동 코드 리뷰(백엔드 코드 변경 → 필수)

## 결과 (작업 후 기록)

### Phase 1 완료 (2026-07-04)
- **신설**: `chat-service` 모듈(포트 8087, buildflow_chat, Eureka/Config/Feign/Redis/JPA), settings.gradle 등록, Gateway `/api/v1/chat/**` 라우트 추가.
- **에이전트 루프**: `OllamaToolService` — Ollama `/api/chat` 툴콜 왕복(비스트리밍), 도구 미선택 시 일반 답변 graceful degrade, `maxToolRounds` 캡.
- **도구 4종**(계획 3종 → listSites 추가): `listSites`/`getSiteProfit`/`getOutstandingTax`/`getDashboardSummary`. `/taxes/outstanding`이 siteId 필수라 현장명→id 해소용 listSites 추가.
- **Feign**: `SiteClient`(site-service), `TaxClient`(tax-service) + `FeignApiResponse<T>` 래퍼. 내부 인증 없음 확인 → 직접 호출.
- **이력**: MySQL `ChatSession`/`ChatMessage` 영구 저장 + Redis `chat:session:{id}` 30분 TTL(liveness). LLM 호출은 트랜잭션 밖.
- **API**: `POST /api/v1/chat` → `ApiResponse<ChatResponse{sessionId, answer}>`.
- **검증**: `./gradlew compileJava` 10개 모듈 전부 통과(JDK 17). 5.5 정적 리뷰 CRITICAL/HIGH 0.

### Phase 1 런타임 검증 통과 (2026-07-04, Claude가 직접 실행)
- 환경: docker(mysql/redis/ollama + qwen2.5:7b pull) + 호스트 bootRun(eureka→site→chat)
- **(a) 툴콜 스모크**: "등록된 현장 목록 알려줘" → listSites 발화 → Feign 실데이터 → "1. 강남 리모델링 (진행 중, 서울 강남구)" ✅ (54s, 모델 로드 포함)
- **(b) 체인+세션**: 같은 세션 "강남 리모델링 현장 마진 얼마야?" → getSiteProfit(siteId=1) → "마진 0원, 마진율 0%"(신규 DB라 정확) ✅ (10s)
- DB 영속화: chat_messages 4행(USER/ASSISTANT 왕복) 확인. 로컬 7b 토큰 잡음("마argin율") 관찰 — Phase 3 견고화 근거
- **검증 중 발견·수정한 잠복 버그 3건**:
  1. `bitnami/kafka:3.7` Docker Hub 소멸(2025 무료 배포 중단) → `bitnamilegacy/kafka:3.7` 드롭인 교체
  2. 전 서비스 JDBC URL에 `allowPublicKeyRetrieval=true` 부재 → 새 MySQL 볼륨에서 전 서비스 기동 불가(잠복 버그) → 7개 yml 일괄 수정
  3. `docker/mysql/init`에 buildflow_chat 스키마 누락 → 추가

### 남은 것
- **Phase 2**: SSE(`SseEmitter`) 스트리밍 + 프론트 채팅 UI.
- **Phase 3**: 도구 확장(estimate/purchase by site) + C 폴백 라우터 + 툴콜 견고화(tool_call_id 상관, 7b 토큰 잡음 실측됨).

## Phase 2 실행 계획 (2026-07-10)

### 범위

- 기존 동기 `POST /api/v1/chat`은 호환성과 진단 용도로 유지한다.
- `POST /api/v1/chat/stream`을 추가하고 Spring MVC `SseEmitter`로 응답한다.
- 툴 선택 라운드는 기존 `stream:false`를 유지하고, 최종 답변 생성만 Ollama `stream:true`로 전달한다.
- 인증된 모든 프론트 화면에서 접근 가능한 플로팅 채팅 패널을 추가한다.

### 스트리밍 계약

- `session`: 서버가 결정한 `sessionId`. 스트림의 첫 이벤트.
- `status`: `thinking`, `tool`, `answering` 진행 상태. 내부 도구 결과나 인자는 노출하지 않는다.
- `token`: 최종 답변의 텍스트 조각.
- `done`: 답변 저장까지 정상 완료됨.
- `error`: 사용자에게 공개 가능한 오류 코드와 메시지.
- 이벤트 데이터는 모두 JSON이며 SSE 내부에는 `ApiResponse` 래퍼를 사용하지 않는다.

### 백엔드 구현 순서

1. 동기/스트리밍 경로가 세션 생성, 이력 구성, 사용자 메시지 저장, 툴 라운드를 공유하도록 오케스트레이션을 분리한다.
2. `OllamaToolService`에 최종 답변 NDJSON 스트림을 순서대로 해석하는 경로를 추가한다.
3. 정상 완료 시에만 누적 답변을 ASSISTANT 메시지로 한 번 저장하고 Redis TTL을 갱신한다.
4. 연결 중단·타임아웃·Ollama 오류 시 부분 답변은 저장하지 않고 정리한다.
5. `/stream` 컨트롤러와 설정 가능한 emitter timeout(초기값 180초)을 추가한다.

### 프론트 구현 순서

1. JWT 헤더와 POST JSON이 필요하므로 네이티브 `EventSource` 대신 `fetch` + `ReadableStream` 기반 SSE 클라이언트를 만든다.
2. UTF-8/청크 경계를 보존하는 독립 SSE 파서를 구현하고 단위 테스트를 추가한다.
3. `ChatLauncher`, `ChatPanel`, 메시지 목록, 입력기를 만들고 `MainLayout`에 장착한다.
4. 대화 상태는 기능 내부에서 관리하고 `sessionId`만 `sessionStorage`에 보존한다.
5. 데스크톱은 우측 패널, 모바일은 전체 화면으로 표시하며 전송·중단·재시도·새 대화 상태를 제공한다.

### 테스트 / 완료 조건

- 백엔드: 이벤트 순서, 토큰 누적/단일 저장, 오류·중단 시 부분 저장 방지, 기존 동기 API 회귀 테스트.
- 프론트: SSE 분할 청크, 다중 이벤트, 401 로그아웃, abort, 한 메시지에 토큰 누적, 패널 상호작용 테스트.
- `./gradlew :chat-service:test`, `bun run lint`, `bun run test`, `bun run build` 통과.
- Gateway(8080) 경유 실데이터 툴콜 스트리밍, 중단, 세션 연속성 수동 검증.
- 구현 종료 후 이 문서 결과, BACKLOG 제거, PROGRESS 완료 이력을 함께 갱신한다.

### Phase 2 결과 (2026-07-15)

- **백엔드**: `POST /api/v1/chat/stream`(SseEmitter, timeout 180s 설정화) + 전용 `chatStreamExecutor`(core2/max4/queue20). 이벤트 계약 session/status/token/done/error 구현. 동기 `POST /chat` 유지.
- **프론트**: `fetch`+`ReadableStream` SSE 클라이언트(`chat.api.ts`, UTF-8 청크 경계 보존 파서) + `ChatPanel` 플로팅 패널(전송/중단/새 대화, sessionStorage sessionId) + MSW 핸들러. MainLayout(인증 화면 전체)에 장착.
- **계획 대비 변경 — 최종 답변 스트리밍 방식**: 5.5 리뷰에서 "툴 선택 라운드가 이미 생성한 답변을 버리고 stream:true로 재생성 → 모든 답변이 LLM 2회 생성"(HIGH) 발견. Ollama `stream:true` relay 대신 **선택 라운드의 답변을 공백 경계 조각(chunkAnswer)으로 relay**하도록 변경 — 생성 1회로 절감, 동기 경로와 답변 항상 일치. 모델 실시간 토큰 스트리밍(stream:true+tools 단일 콜)은 Phase 3로 이관.
- **5.5 리뷰(high)**: findings 10건(HIGH 1) 전부 같은 사이클에서 fix — 이중 생성 제거, emitter 생명주기(onTimeout/onError+disconnected 플래그), 클라이언트 중단을 INFO로 분류(SseDisconnectedException), executor 포화 시 error 이벤트(CHAT_BUSY), BusinessException ErrorCode 보존, lombok.config(@Qualifier 복사), 프론트 reader.cancel/uuid 폴백/reset 동기화/scroll 최적화, run↔runStreaming 루프 통합, 401 처리 공유(handleSessionExpired).
- **테스트**: 백엔드 ChatServiceTest(2)+OllamaToolServiceTest(chunkAnswer 3) / 프론트 chat.api.test 3(분할 청크·trailing·reader 취소). lint·build·전체 테스트 green.
- **런타임 검증 (Claude 직접 실행, Gateway 경유)**: (a) 툴콜 스트리밍 "현장 목록" → listSites → token 조각 → done ✅ (b) 세션 연속성 "그 현장 마진?" → listSites→getSiteProfit 2라운드 체인 ✅ (c) 중단 → INFO 분류·부분 답변 미저장·ERROR 0 ✅ (d) chat_messages 영속화 ✅. 7b 토큰 잡음("마argin율") 재실측.
- **검증 중 발견한 환경 함정 (런북 갱신)**:
  1. **호스트 네이티브 Ollama와 docker Ollama가 11434 이중 리스너**(IPv4 네이티브/IPv6 docker) — Java는 IPv4로 붙어 모델 없는 네이티브가 404. 해결: `OLLAMA_URL=http://[::1]:11434` 또는 네이티브 중지.
  2. 로컬 nginx(brew)가 8080/8081 점유 → gateway/auth는 `SERVER_PORT` 오버라이드(18080/18081)로 기동.
  3. gateway는 config-server 필수(`spring.config.import`) — 없으면 `SPRING_CLOUD_CONFIG_ENABLED=false`. config-server는 `~/buildflow-config-repo` git 저장소 부재로 이 머신에선 반쪽.
  4. 서비스 yml `password: ${DB_PASSWORD}` 기본값 없음 → bootRun 시 `DB_PASSWORD=buildflow123` 필수.
- **잔여(Phase 3로)**: 모델 실시간 토큰 스트리밍(stream:true+tools), tool_call_id 상관, 프론트 per-token 전체 리스트 리렌더 최적화, buildMessages 이력 tail 쿼리(LIMIT).
