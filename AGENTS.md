# BuildFlow — Agent Instructions

현재는 건설·시공 소규모 업체(1~10인)를 위한 현장 업무 관리 MSA 플랫폼.
현장별 문서(공내역서, 견적서, 세금계산서, 하자보증보험)를 묶어 관리하고,
AI 대시보드로 마진·손익을 요약. 1인 개발 + 가족이 단일 관리자 계정을 공유하는 로컬 실사용.

장기적으로 건설 도메인을 첫 검증 영역으로 삼아, 서로 다른 파일·폴더·양식의 원본·출처·버전·관계·확정 판단을 보존하는 유연한 정보관리 플랫폼으로 확장한다. 제품 비전 기준은 `docs/PRODUCT_VISION.md`이며 현재 건설 실사용 우선순위는 `.claude/BACKLOG.md`를 따른다.

---

## 1. Tech Stack

### Backend
- Java 17, Spring Boot 3.3.4, Spring Cloud 2023.0.3
- Gradle (Groovy), 멀티프로젝트 모노레포
- JPA/Hibernate, MySQL 8.0 (서비스별 스키마 분리)
- Redis 7.2 (JWT refresh·블랙리스트, chat 보조 캐시 — 조회 캐시·분산락 미구현, ADR-003 v2)
- Kafka (KRaft 모드) — 비동기 이벤트
- OpenFeign — 서비스 간 동기 통신 (chat→site/tax/estimate/purchase, 생성 시 site 현장 참조 검증)
- Zipkin — 분산 트레이싱

### Frontend
- React 18 + TypeScript + Vite
- Ant Design (dark theme), Zustand, TanStack Query
- axios + JWT 인터셉터, MSW (개발 모킹)
- Lucide React (아이콘), Motion/framer-motion v11+
- 패키지 매니저: **bun** (npm/yarn 사용 금지)

---

## 2. 서비스 & 포트

| 서비스 | 포트 | DB 스키마 | 역할 |
|--------|------|-----------|------|
| eureka-server | 8761 | — | 서비스 디스커버리 |
| config-server | 8888 | — | 중앙 설정 관리 |
| gateway-server | 8080 | — | API Gateway, JWT 검증 |
| auth-service | 8081 | buildflow_auth | JWT + Redis 블랙리스트 |
| estimate-service | 8082 | buildflow_estimate | 견적서 CRUD + 공내역서 AI 파싱(Ollama) |
| site-service | 8083 | buildflow_site | 현장 관리 + 손익 계산 |
| purchase-service | 8084 | buildflow_purchase | 매입 관리 |
| tax-service | 8085 | buildflow_tax | 세금계산서 + 미수금 |
| notification-service | 8086 | — | 인앱 알림 + 하자보증보험 OCR |
| frontend | 3000 | — | Vite dev / Nginx prod |

**기동 순서 필수**: Eureka → Config → Gateway → 나머지 서비스

---

## 3. 빌드 & 실행 명령

```bash
# 인프라 (Docker)
docker compose up -d                                    # MySQL, Redis, Kafka, Zipkin (Ollama는 native 기본)
docker compose -f docker-compose.yml -f docker-compose.app.yml up -d  # 앱 포함 전체
docker compose down                                     # 종료 (docker stop 금지)

# 백엔드 (Gradle 멀티프로젝트)
./gradlew build                                         # 전체 빌드
./gradlew :auth-service:bootRun                         # 단일 서비스 실행
./gradlew :estimate-service:test                        # 단일 서비스 테스트
./gradlew :site-service:bootJar                         # JAR 빌드

# 프론트엔드
cd frontend
bun install
bun run dev                                             # Vite dev server (:3000)
bun run build                                           # TypeScript + Vite 프로덕션 빌드
bun run lint                                            # ESLint (max-warnings: 0)
```

---

## 4. 패키지 구조

```
{service}/src/main/java/com/buildflow/{service}/
├── domain/
│   └── {entity}/
│       ├── controller/
│       ├── service/
│       ├── repository/
│       ├── dto/            # XxxRequest / XxxResponse
│       └── entity/         # JPA Entity
└── global/
    ├── config/
    ├── exception/          # GlobalExceptionHandler + 커스텀 예외
    └── response/           # ApiResponse<T>

frontend/src/
├── api/                    # {domain}.api.ts + axiosInstance.ts
├── components/
├── hooks/
├── pages/
├── stores/                 # Zustand
└── types/
```

---

## 5. API 컨벤션

- 경로: `/api/v1/{resource}`
- 응답 래퍼: `{ success: boolean, data: T, error: string | null }`
- Gateway 라우팅:
  - `/api/v1/auth/**` → auth-service (인증 필터 없음)
  - `/api/v1/estimates/**` → estimate-service (AuthorizationHeaderFilter)
  - `/api/v1/sites/**` → site-service (AuthorizationHeaderFilter)
  - `/api/v1/purchases/**` → purchase-service (AuthorizationHeaderFilter)
  - `/api/v1/taxes/**` → tax-service (AuthorizationHeaderFilter)

---

## 6. Kafka 이벤트

메시지 형식: `{ eventId, eventType, timestamp, payload }`
Consumer 그룹: `{service}-group`
eventId 기반 멱등성 필수.

| 토픽 | 발행 | 소비 |
|------|------|------|
| estimate.parsed | estimate-service | site-service, notification-service |
| estimate.deleted | estimate-service | site-service, notification-service |
| purchase.registered | purchase-service | site-service, notification-service |
| purchase.updated | purchase-service | site-service |
| purchase.deleted | purchase-service | site-service |
| tax.registered | tax-service | notification-service |
| tax.payment.confirmed | tax-service | notification-service |
| warranty.expiring | notification-service | notification-service |

---

## 7. 손익 계산 공식

```
마진 = SUM(견적서 금액) - SUM(매입 금액)
마진율 = 마진 / SUM(견적서 금액) × 100
미수금 = SUM(매출 세금계산서) - SUM(입금 확인 금액)
```

- 금액·수량 입력과 저장되는 파생 값(비율·합계·차액)은 입력 극단 조합의 최소·최대를 DB `DECIMAL(precision, scale)`과 대조하고 극단값 저장 테스트를 둔다. `margin_rate`(DECIMAL(5,2))는 −999.99 하한 처리, 정확한 손실은 `margin` 금액으로 본다(2026-10-17 회고).

---

## 8. 코드 컨벤션 (반드시 준수)

### Java
- DTO 분리 필수 — **Entity 직접 반환 금지**
- `@Data` **금지** — `@Getter`, `@Builder`, `@NoArgsConstructor(access = PROTECTED)` 사용
- 생성자 주입: `@RequiredArgsConstructor` — `@Autowired` **금지**
- JPA 중심 — MyBatis **금지**
- 서비스 간 직접 DB 접근 **금지** → OpenFeign 또는 Kafka 경유

### Redis 키 컨벤션
- `{service}:{domain}:{id}`
- 도입 시 기본 TTL 5분, 분산락 TTL 30초 (현재 분산락·조회 캐시 미구현 — DB 비관적 잠금 + outbox 멱등, ADR-003 v2)

### React / TypeScript
- 함수형 컴포넌트 + Hooks만 사용
- 파일명: PascalCase.tsx (컴포넌트), camelCase.ts (유틸/훅)
- 서버 상태: TanStack Query, 클라이언트 상태: Zustand

---

## 9. 절대 금지 사항

- 환경변수 하드코딩
- `@Data` 사용
- `MyBatis` 사용
- 서비스 간 직접 DB 접근
- Entity 직접 반환
- `docker stop` (반드시 `docker compose down`)
- `npm` / `yarn` (프론트엔드는 `bun`)
- AI 결과로 원본 파일 덮어쓰기·임의 이름 변경
- 사용자 확정 없이 AI 제안을 금액·계약·삭제·보존 만료의 최종 사실로 반영

---

## 10. 주요 파일 위치

| 파일 | 용도 |
|------|------|
| `CLAUDE.md` | 전체 개발 가이드 (사람 + AI용, 상세) |
| `docs/PRODUCT_VISION.md` | 제품 철학·정보 보존 원칙·단계별 장기 로드맵 |
| `.claude/BACKLOG.md` | 다음 작업 우선순위 (P0/P1/P2) |
| `.claude/PROGRESS.md` | 완료 이력 + 현재 git 상태 |
| `docs/ARCHITECTURE.md` | 시스템 전체 구조 상세 |
| `docs/ERD.md` | 데이터 모델 |
| `docs/API_SPEC.md` | API 명세 |
| `docs/DECISIONS.md` | 아키텍처 결정 기록(ADR) |
| `docker-compose.yml` | 인프라 컨테이너 |
| `docker-compose.app.yml` | 앱 서비스 컨테이너 |

---

## 11. 현재 구현 상태

### 완료
- eureka-server, config-server, gateway-server (JWT 검증)
- auth-service: 단일 관리자 로컬 초기화/아이디 로그인/토큰 갱신/로그아웃 (JWT + Redis 블랙리스트, 공개 회원가입 없음)
- estimate-service: CRUD + Kafka 발행 + Ollama 공내역서 AI 파싱
- site-service: CRUD + 손익 계산 + Kafka 소비
- purchase-service: CRUD + Kafka 발행
- tax-service: 세금계산서 CRUD + 미수금 관리 + Kafka 발행
- notification-service: Kafka 구독 알림 + 하자보증보험 OCR(PDF)
- frontend: 로그인/현장관리/견적/매입/세금계산서/하자보증보험 페이지 (Ant Design dark)

### 백로그
- 고정 목록을 이 문서에 복제하지 않는다. `.claude/BACKLOG.md`를 단일 진실원으로 사용한다.

---

## 12. 역할별 에이전트·모델 운용

- **도구 간 인계(Codex ↔ Claude Code)**: 작업 시작 전 `.claude/HANDOFF.md`의 작업 잠금을 확인한다. 다른 도구가 2시간 이내 `ACTIVE`이면 그 브랜치·PR·BACKLOG 항목을 건드리지 않는다. 착수·단계 전환·종료 때 잠금을 갱신하고, 두 도구에 공통인 사용자 지시는 도구별 메모리가 아니라 HANDOFF "공통 사용자 지시"에 기록한다. 끊긴 세션은 `scripts/agents/last_session.py`로 확인한 뒤 Git/GitHub 실측과 대조한다. 세션 시작 시 `python -I -X utf8 scripts/agents/workspace.py status`로 잠금·열린 PR·작업 폴더를 확인한다. 체크포인트는 `workspace.py checkpoint --tool codex|claude --step ... --next ... --path <검토한 파일>`로 **명시한 파일만** 커밋·푸시한다. `--auto` 커밋·푸시는 비활성화했고 예약 heartbeat는 사용자가 취소했으므로 재생성하지 않는다. 세션이 끊기면 마지막으로 명시적으로 올린 체크포인트까지만 상대 도구가 볼 수 있다.
- 과거 작업의 추적 경로와 교대 체크리스트는 `docs/AGENT_COLLABORATION.md`를 따른다. 대화별 기억·원시 세션 로그·비밀값을 저장소에 복제하지 않는다.

- 제품이 역할명만 보고 모델을 자동 전환한다고 가정하지 않는다. 사용자가 다중 에이전트 위임을 허용한 작업에서 주 에이전트가 역할과 위험도에 따라 가용 모델·추론 강도를 명시적으로 선택한다.
- 데이터 복구·보안·아키텍처·마이그레이션처럼 실패 영향이 큰 독립 검토는 가장 강한 가용 추론 모델을 우선한다.
- 기능 구현·코드 리뷰·통합 테스트는 코딩 품질과 속도가 균형 잡힌 가용 모델을 사용한다.
- 파일 목록화·반복 검색·정형 문서 점검처럼 기계적인 작업은 빠른 가용 모델을 사용한다.
- 짧거나 순차 의존성이 큰 작업은 분산하지 않고 주 에이전트가 처리한다. 같은 파일을 여러 에이전트가 동시에 수정하지 않는다.
- 지정 모델의 사용량 한도·가용성 문제로 위임이 실패하면 부모 모델 또는 다음 가용 모델로 즉시 폴백하고, 전체 작업을 중단하지 않는다.
- 모델 선택은 품질 보조 수단일 뿐이며, 최종 결과는 주 에이전트가 테스트·근거·diff를 직접 검증한 뒤 통합한다.

---

## 13. 보안·인프라 점검 하네스

- 공개 포트 개방, 인증/권한 변경, 배포, 실데이터 입력 전 `docs/SECURITY_OPERATIONS.md`의 해당 게이트를 실행하고 `PASS/FAIL/PENDING/UNVERIFIED`와 근거를 남긴다. CI/Compose 정적 통과를 실제 외부 노출·TLS 통과로 대체하지 않는다.
- 보안 영향 변경과 공개 전 검토는 `security-reviewer`, VPS/배포/백업 변경은 `infrastructure-reviewer`의 독립 검토를 요청한다. 역할별 작업 범위는 `.claude/agents/`에 고정한다. 주 에이전트가 중복·상충 결과를 검증해 통합한다.
- BuildFlow 관련 사용자 프롬프트를 처리할 때마다 **마지막 작업으로**, 응답 직전에 `security-reviewer`와 `infrastructure-reviewer` 관점의 읽기 전용 점검을 수행한다. `docs/SECURITY_OPERATIONS.md`의 사용자 프롬프트 종료 점검 항목을 현재 변경과 공개 파일럿 상태에 맞게 확인하고 `PASS/FAIL/PENDING/UNVERIFIED`를 구분한다. 접속·권한이 없으면 실측한 척하지 않는다. 이 종료 점검은 예약 실행이나 상시 감시가 아니며, 사용자 요청 처리 중 필요한 사전 보안 게이트를 대신하지 않는다.
- 종료 점검에서 이상이 생기면 원인·심각도·증거·복구 제안·백로그를 보고한다. 정상·변화 없음은 간결하게 처리한다. 점검 자체가 포트 개방, SSH/방화벽 변경, 재부팅, 백업 복원, 볼륨 삭제, 운영 자동 배포를 승인하지 않는다.
- 공개 HTTPS는 테스트 데이터 전용이다. `docs/SECURITY_OPERATIONS.md`의 실데이터 운영 게이트가 전부 통과하기 전 업무 원본을 VPS에 넣지 않는다. 종료 점검에서 정상·변화 없음은 불필요한 알림으로 반복하지 않는다.

---

## 14. V1 완료 기준·진행률 보고

- 첫 실데이터 현장 1건의 안전한 운영을 V1 100% 완료선으로 삼는다. 범용 정보관리 플랫폼 장기 비전과 섞지 않는다. 범위·가중치·통과 게이트의 기준은 `docs/DELIVERY_PLAN.md`, 세부 우선순위는 `.claude/BACKLOG.md`다. 사용자가 정하지 않은 사용 목표일을 임의로 약속하거나 그 날짜까지 작업을 대기시키지 않는다.
- 매 작업 종료에 현재 `N/100`, 통과한 체크포인트와 바로 이어서 처리할 게이트를 보고한다. 코드 작성이나 CI 통과를 VPS 배포·백업 복원·실데이터 승인으로 계산하지 않는다.
- 막힘이나 범위 변경은 원인·회복 행동·사용자 결정 사항을 명시한다. 사용자와 별도로 합의하지 않은 마감일을 만들지 않으며, 속도를 위해 보안·복구 게이트를 생략하지 않는다.

## 15. 도구 상태·사용량 설명의 증거 기준

- `usageLimited`, `blocked`, `UNVERIFIED` 같은 **상태값은 관찰 사실이지 원인 설명이 아니다**. 해당 값의 소유 범위(목표/채팅/계정/도구/서버), 실제 사용 가능 여부, 표시 시각을 서로 구분한다.
- Codex 목표 상태와 계정 사용량은 별도 출처로 확인한다. 한쪽 상태만 보고 다른 쪽의 한도 소진·자동 재개 불가·앱 전체 중단을 단정하지 않는다. 제한 원인·적용 시작일·해제 시각은 확인된 근거가 없으면 `UNVERIFIED`라고 말한다.
- 상태 때문에 작업이 멈춘다고 주장하기 전에 현재 요청에서 가능한 안전한 작업을 실제로 시도한다. 불가능할 때는 실패한 동작과 정확한 오류, 범위, 재개 조건을 제시한다. 추정은 추정으로 표시하고 사용자에게 검증되지 않은 제품 규칙을 사실처럼 설명하지 않는다.
- 잘못 설명한 것이 확인되면 즉시 정정하고, 무엇을 관찰했으며 무엇을 추론했는지 구분해 보고한다. 내부 상태명의 의미가 문서화되지 않았으면 공식 문서의 일반적 제한 설명을 해당 사례의 확정 원인으로 바꾸지 않는다.
