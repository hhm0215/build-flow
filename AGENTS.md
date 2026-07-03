# BuildFlow — Agent Instructions

건설·시공 소규모 업체(1~10인)를 위한 현장 업무 관리 MSA 플랫폼.
현장별 문서(공내역서, 견적서, 세금계산서, 하자보증보험)를 묶어 관리하고,
AI 대시보드로 마진·손익을 요약. 1인 개발 + 실사용(혜민 관리, 아버지 열람).

---

## 1. Tech Stack

### Backend
- Java 17, Spring Boot 3.3.4, Spring Cloud 2023.0.3
- Gradle (Groovy), 멀티프로젝트 모노레포
- JPA/Hibernate, MySQL 8.0 (서비스별 스키마 분리)
- Redis 7.2 (JWT 블랙리스트, 캐시)
- Kafka (KRaft 모드) — 비동기 이벤트
- OpenFeign — 서비스 간 동기 통신
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
docker compose up -d                                    # MySQL, Redis, Kafka, Ollama, Zipkin
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
| tax.registered | tax-service | site-service, notification-service |
| tax.payment.confirmed | tax-service | site-service |
| warranty.expiring | notification-service | notification-service |

---

## 7. 손익 계산 공식

```
마진 = SUM(견적서 금액) - SUM(매입 금액)
마진율 = 마진 / SUM(견적서 금액) × 100
미수금 = SUM(매출 세금계산서) - SUM(입금 확인 금액)
```

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
- 기본 TTL 5분, 분산락 TTL 30초

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

---

## 10. 주요 파일 위치

| 파일 | 용도 |
|------|------|
| `CLAUDE.md` | 전체 개발 가이드 (사람 + AI용, 상세) |
| `.claude/BACKLOG.md` | 다음 작업 우선순위 (P0/P1/P2) |
| `docs/PROGRESS.md` | 완료 이력 + 현재 git 상태 |
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
- auth-service: 회원가입/로그인/토큰 갱신/로그아웃 (JWT + Redis 블랙리스트)
- estimate-service: CRUD + Kafka 발행 + Ollama 공내역서 AI 파싱
- site-service: CRUD + 손익 계산 + Kafka 소비
- purchase-service: CRUD + Kafka 발행
- tax-service: 세금계산서 CRUD + 미수금 관리 + Kafka 발행
- notification-service: Kafka 구독 알림 + 하자보증보험 OCR(PDF)
- frontend: 로그인/현장관리/견적/매입/세금계산서/하자보증보험 페이지 (Ant Design dark)

### 백로그 (P1)
- `WarrantyOcrParser.findPeriod` 200자 윈도우 개선 (날짜 오매칭 위험)
- `DefectWarranty.update` PATCH/PUT 시맨틱 분리 (null vs absent 구분)
- `useListFilters` 훅 추상화 (5개 ListPage 중복 제거)

### 백로그 (P2)
- Gradle wrapper 설치
- chat-service (RAG 챗봇, LLM function calling + OpenFeign + SSE)
