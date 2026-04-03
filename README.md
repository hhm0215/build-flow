# BuildFlow — 건설 현장 관리 플랫폼

건설·시공 소규모 업체(1~10인)를 위한 현장 업무 관리 플랫폼.
**공내역서 AI 파싱 → 견적서 자동 생성, 매입/손익 관리, 세금계산서 연동**이 핵심.
MSA + 대용량 트래픽 설계를 보여주는 백엔드 포트폴리오 프로젝트.

---

## 기술 스택

| 구분 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3.3, Spring Cloud 2023.0 |
| Service Discovery | Spring Cloud Netflix Eureka |
| API Gateway | Spring Cloud Gateway (JWT 필터, Redis Rate Limiting) |
| Config | Spring Cloud Config (Git 기반) |
| 동기 통신 | OpenFeign |
| 비동기 통신 | Apache Kafka (KRaft mode) |
| Cache / Lock | Redis (Cache Aside, JWT 블랙리스트, Redisson 분산락) |
| Database | MySQL 8 (Database per Service) |
| Tracing | Zipkin + Micrometer Tracing |
| AI | Claude API (공내역서 파싱) |
| Frontend | React 18 + TypeScript + Vite + Ant Design |
| 상태 관리 | Zustand (클라이언트), TanStack Query (서버 상태) |

---

## 서비스 구성

```
buildflow/
├── eureka-server/        # 서비스 디스커버리          :8761
├── config-server/        # 중앙 설정 관리             :8888
├── gateway-server/       # API Gateway               :8080
├── auth-service/         # 인증·인가 (JWT + Redis)    :8081
├── estimate-service/     # 견적서 + 공내역서 AI 파싱   :8082
├── site-service/         # 현장 관리 + 손익 계산       :8083
├── purchase-service/     # 매입(자재·서비스) 관리      :8084
├── tax-service/          # 세금계산서 + 미수금 관리    :8085
├── notification-service/ # 알림 (Kafka consumer)     :8086
├── frontend/             # React SPA                 :3000
└── docker-compose.yml    # 로컬 인프라
```

---

## 핵심 플로우 — 공내역서 → 견적서 자동 생성

```
1. 사용자가 엑셀(.xlsx) 업로드
2. Apache POI로 셀 데이터 텍스트 변환
3. Claude API 호출 → 품목 JSON 배열 추출
4. 파싱 결과 검토 화면 표시 (사용자 수정 가능)
5. 확정 시 estimate + estimate_item DB 저장
6. Kafka 'estimate.parsed' 이벤트 발행
   ├── site-service     → 현장 손익 재계산
   └── notification-service → 알림 발송
```

---

## 로컬 실행

### 사전 요구사항
- Java 17+
- Docker Desktop
- Node.js 20+

### 1. 환경변수 설정

```bash
cp .env.example .env
# .env 파일을 열어 DB 비밀번호, JWT_SECRET, CLAUDE_API_KEY 값 입력
```

### 2. 인프라 기동 (MySQL, Redis, Kafka, Zipkin)

```bash
docker compose up -d
```

| 서비스 | 접속 주소 |
|--------|----------|
| MySQL | localhost:3306 |
| Redis | localhost:6379 |
| Kafka | localhost:9092 |
| Zipkin | http://localhost:9411 |

### 3. 백엔드 서비스 실행 (순서 중요)

```bash
# 1) Eureka Server
cd eureka-server && ./gradlew bootRun

# 2) Config Server
cd config-server && ./gradlew bootRun

# 3) Gateway + 비즈니스 서비스 (순서 무관)
cd gateway-server && ./gradlew bootRun
cd auth-service && ./gradlew bootRun
cd estimate-service && ./gradlew bootRun
cd site-service && ./gradlew bootRun
cd purchase-service && ./gradlew bootRun
cd tax-service && ./gradlew bootRun
cd notification-service && ./gradlew bootRun
```

### 4. 프론트엔드 실행

```bash
cd frontend
npm install
npm run dev
# http://localhost:3000
```

---

## API 구조

모든 요청은 Gateway(`localhost:8080`)를 통해 라우팅됩니다.

```
POST   /api/v1/auth/signup          # 회원가입
POST   /api/v1/auth/login           # 로그인
POST   /api/v1/auth/logout          # 로그아웃
POST   /api/v1/auth/reissue         # 토큰 재발급

GET    /api/v1/sites                # 현장 목록
POST   /api/v1/sites                # 현장 등록

GET    /api/v1/estimates            # 견적서 목록
POST   /api/v1/estimates/parse      # 공내역서 AI 파싱
POST   /api/v1/estimates            # 견적서 확정 저장

GET    /api/v1/purchases            # 매입 목록
POST   /api/v1/purchases            # 매입 등록

GET    /api/v1/taxes                # 세금계산서 목록
POST   /api/v1/taxes                # 세금계산서 등록
```

응답 포맷은 모든 서비스 공통:
```json
{
  "success": true,
  "data": { ... },
  "error": null
}
```

---

## Kafka 이벤트 토픽

| 토픽 | 발행 서비스 | 구독 서비스 |
|------|-----------|-----------|
| `estimate.parsed` | estimate-service | site-service, notification-service |
| `purchase.registered` | purchase-service | site-service, notification-service |
| `tax.issued` | tax-service | notification-service |

---

## 테스트

```bash
# 단일 서비스 테스트 (권장)
cd estimate-service && ./gradlew test

# 전체 테스트
./gradlew test
```

---

## 프로젝트 배경

가업(건설 용접업)에서 실제로 겪는 현장 업무 비효율을 해소하기 위해 시작한 프로젝트.
공내역서를 수작업으로 견적서에 옮기는 반복 작업을 AI 파싱으로 자동화하고,
현장별 손익을 실시간으로 파악할 수 있도록 설계했습니다.
