# BuildFlow 프로젝트 학습 가이드

> 건설/시공 소규모 업체를 위한 현장 업무 관리 MSA 플랫폼  
> 이 문서는 프로젝트의 전체 구조, 기술 선택 이유, 구현 상세를 학습/면접 대비용으로 정리한 것입니다.

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [아키텍처 전체 구조](#2-아키텍처-전체-구조)
3. [각 서비스별 역할과 구현 상세](#3-각-서비스별-역할과-구현-상세)
4. [핵심 기술 스택 학습 포인트](#4-핵심-기술-스택-학습-포인트)
5. [API 설계 패턴](#5-api-설계-패턴)
6. [Kafka 이벤트 흐름](#6-kafka-이벤트-흐름)
7. [데이터베이스 설계](#7-데이터베이스-설계)
8. [프론트엔드 구성](#8-프론트엔드-구성)
9. [Docker 인프라](#9-docker-인프라)
10. [향후 구현 예정](#10-향후-구현-예정)

---

## 1. 프로젝트 개요

### BuildFlow란?

건설/시공 소규모 업체(1~10인)를 위한 **현장 업무 관리 플랫폼**이다. 현장별로 흩어져 있는 문서들(공내역서, 견적서, 세금계산서, 하자보증보험)을 하나로 묶어 관리하고, AI가 요약 대시보드를 생성하여 마진/손익을 한눈에 파악할 수 있도록 한다.

### 왜 MSA인가?

이 프로젝트는 1인 개발이지만 MSA(Microservice Architecture)를 채택했다. 그 이유는:

1. **백엔드 포트폴리오**: Spring Cloud 기반 MSA 설계 및 구현 능력을 증명하기 위함이다. 실무에서 MSA를 다루는 팀에 합류할 때 가장 강력한 포트폴리오가 된다.

2. **도메인 분리의 자연스러움**: 건설 업무 자체가 도메인별로 명확히 분리된다.
   - 인증(auth) / 견적(estimate) / 현장(site) / 매입(purchase) / 세금(tax) / 알림(notification)
   - 각 도메인이 독립적인 생명주기를 가지므로 MSA가 자연스럽다.

3. **실사용 + 학습**: 실제로 관리자(혜민)가 데이터를 입력하고, 열람자(아버지)가 확인하는 실사용 시스템이다. MSA 구조를 실제 운영 환경에서 경험하는 것이 목표다.

### 사용자와 역할

| 역할 | 사용자 | 권한 |
|------|--------|------|
| ADMIN | 혜민 | 전체 CRUD, 설정 관리 |
| VIEWER | 아버지 | 조회만 가능 |

---

## 2. 아키텍처 전체 구조

### 서비스 간 관계도

```
                            [Frontend :3000]
                                  |
                                  v
                          [Gateway :8080]
                      JWT 검증 + 라우팅
                     /     |      |     \
                    v      v      v      v
             [auth]  [estimate] [site] [purchase] [tax]
             :8081    :8082     :8083   :8084     :8085
               |        |         |       |        |
               |        |    Kafka|       |        |
               |        +----->---+<------+        |
               |                  |                |
              Redis            MySQL            MySQL
              :6379            :3306            :3306
                          (스키마 분리)

       [Eureka :8761] <--- 모든 서비스 등록
       [Config :8888] <--- 모든 서비스 설정 제공
       [Zipkin :9411] <--- 분산 트레이싱
       [Ollama :11434] <--- AI 파싱 (estimate-service 사용)
```

### 통신 방식

| 방식 | 기술 | 사용 사례 | 왜 이 방식인가? |
|------|------|----------|---------------|
| 동기 (Synchronous) | OpenFeign | 서비스 간 즉시 데이터 조회가 필요할 때 | 요청-응답 패턴이 필요한 경우 (예: 현장 정보 조회) |
| 비동기 (Asynchronous) | Kafka | 이벤트 발행/구독 | 서비스 간 결합도를 낮추고, 데이터 일관성을 eventual consistency로 보장 |

**왜 두 가지를 함께 쓰는가?**

- **동기가 필요한 경우**: "현장 상세 조회 시 견적 목록도 함께 보여줘야 한다" -> 즉시 응답이 필요하므로 OpenFeign
- **비동기가 적합한 경우**: "견적서가 확정되면 현장 손익을 재계산한다" -> 즉시 결과가 필요 없고, 서비스 간 결합도를 낮추고 싶으므로 Kafka

### 서비스 포트 & DB 스키마

| 서비스 | 포트 | DB 스키마 | 역할 |
|--------|------|-----------|------|
| eureka-server | 8761 | - | 서비스 디스커버리 |
| config-server | 8888 | - | 중앙 설정 관리 |
| gateway-server | 8080 | - | API Gateway, JWT 검증 |
| auth-service | 8081 | buildflow_auth | JWT 인증 + Redis 블랙리스트 |
| estimate-service | 8082 | buildflow_estimate | 견적서 CRUD + 공내역서 AI 파싱 |
| site-service | 8083 | buildflow_site | 현장 관리 + 손익 계산 |
| purchase-service | 8084 | buildflow_purchase | 매입 관리 |
| tax-service | 8085 | buildflow_tax | 세금계산서 + 미수금 |
| notification-service | 8086 | - | 알림 + 하자보증보험 OCR |
| frontend | 3000 | - | React SPA |

---

## 3. 각 서비스별 역할과 구현 상세

### 3.1 인프라 서비스

#### Eureka Server (서비스 디스커버리)

**왜 필요한가?**

MSA에서 서비스가 여러 개 떠 있으면, 서로의 위치(IP:Port)를 알아야 통신할 수 있다. 하드코딩하면 서비스가 추가/제거될 때마다 설정을 바꿔야 한다. Eureka는 모든 서비스가 자신을 등록(register)하고, 다른 서비스의 위치를 조회(discover)할 수 있는 전화번호부 역할을 한다.

**동작 흐름:**
```
1. auth-service 기동 → Eureka에 "나는 auth-service, 8081번에 있어" 등록
2. gateway-server가 auth-service 호출 필요 → Eureka에 "auth-service 어디야?" 질의
3. Eureka가 "localhost:8081이야" 응답
4. 30초마다 heartbeat → 서비스 장애 시 자동 제거
```

**실제 코드:**
```java
// EurekaServerApplication.java
@SpringBootApplication
@EnableEurekaServer  // 이 한 줄로 Eureka 서버 활성화
public class EurekaServerApplication { ... }
```

각 클라이언트 서비스의 `application.yml`에서 Eureka 등록:
```yaml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true
```

#### Config Server (중앙 설정 관리)

**왜 필요한가?**

6개 이상의 서비스가 각각 DB 접속 정보, JWT 시크릿, Kafka 주소 등을 가지고 있다. 이것을 각 서비스의 `application.yml`에 분산시키면:
- 설정 변경 시 모든 서비스를 재배포해야 한다
- 환경(dev/staging/prod)별 관리가 복잡하다
- 민감 정보가 여러 곳에 흩어진다

Config Server는 설정을 한 곳에서 관리하고, 각 서비스가 기동 시 Config Server에서 자신의 설정을 가져가는 구조다.

**동작 흐름:**
```
1. Config Server 기동 (설정 파일 로드)
2. auth-service 기동 → Config Server에 "auth-service 설정 줘" 요청
3. Config Server가 auth-service.yml 반환
4. auth-service가 해당 설정으로 기동
```

#### Gateway Server (API Gateway)

**왜 필요한가?**

프론트엔드는 하나의 URL(`localhost:8080`)로 요청을 보내고 싶다. 하지만 백엔드는 여러 서비스로 나뉘어 있다. Gateway는 단일 진입점 역할을 하며, 요청 경로에 따라 적절한 서비스로 라우팅한다.

추가로 **JWT 검증**을 Gateway에서 수행하여, 각 서비스가 직접 인증 로직을 갖지 않아도 된다.

**라우팅 규칙:**
```
/api/v1/auth/**       → auth-service     (인증 필터 없음 — 로그인/회원가입이니까)
/api/v1/estimates/**  → estimate-service  (AuthorizationHeaderFilter 적용)
/api/v1/sites/**      → site-service      (AuthorizationHeaderFilter 적용)
/api/v1/purchases/**  → purchase-service  (AuthorizationHeaderFilter 적용)
/api/v1/taxes/**      → tax-service       (AuthorizationHeaderFilter 적용)
```

**AuthorizationHeaderFilter 동작:**

```java
// Gateway에서 JWT 검증 후, 하위 서비스에 사용자 정보를 헤더로 전달
public GatewayFilter apply(Config config) {
    return (exchange, chain) -> {
        // 1. Authorization 헤더에서 Bearer 토큰 추출
        String token = authHeader.substring(7);

        // 2. JWT 검증
        if (!isValidToken(token)) {
            return onError(exchange, HttpStatus.UNAUTHORIZED);
        }

        // 3. 검증 성공 시, 토큰에서 userId와 role을 추출하여 헤더에 주입
        Claims claims = getClaims(token);
        exchange = exchange.mutate()
                .request(r -> r.header("X-User-Id", claims.getSubject())
                        .header("X-User-Role", claims.get("role", String.class)))
                .build();

        return chain.filter(exchange);
    };
}
```

**왜 이렇게 설계했는가?**

- 각 서비스는 `X-User-Id`, `X-User-Role` 헤더만 읽으면 된다 (JWT 파싱 불필요)
- 인증 로직이 Gateway 한 곳에 집중되어 유지보수가 쉽다
- auth-service 경로는 필터를 적용하지 않아, 로그인/회원가입은 인증 없이 접근 가능

---

### 3.2 auth-service (인증 서비스)

**역할**: 회원가입, 로그인, 토큰 갱신, 로그아웃

#### JWT 인증 흐름

```
[로그인 흐름]
1. POST /api/v1/auth/login { email, password }
2. email로 DB 조회 → 비밀번호 BCrypt 검증
3. Access Token 생성 (30분) + Refresh Token 생성 (7일)
4. Refresh Token을 Redis에 저장 (키: auth:refresh:{userId})
5. 두 토큰을 클라이언트에 반환

[토큰 갱신 흐름]
1. POST /api/v1/auth/refresh { refreshToken }
2. Refresh Token에서 userId 추출
3. Redis에 저장된 Refresh Token과 비교 (탈취 방지)
4. 일치하면 새 Access Token + 새 Refresh Token 발급
5. Redis의 Refresh Token도 새것으로 교체 (Rotation)

[로그아웃 흐름]
1. POST /api/v1/auth/logout (Authorization: Bearer {accessToken})
2. Access Token의 남은 만료 시간을 계산
3. Redis 블랙리스트에 추가 (키: auth:blacklist:{token}, TTL: 남은 시간)
4. Redis에서 Refresh Token 삭제
```

#### Redis 블랙리스트, 왜 쓰는가?

JWT는 stateless한 토큰이다. 서버가 토큰을 발급하면, 만료 전까지는 무효화할 방법이 없다. 하지만 로그아웃한 사용자의 토큰이 30분간 유효한 것은 보안 문제다.

**해결 방법**: 로그아웃 시 해당 Access Token을 Redis 블랙리스트에 등록한다. Gateway가 JWT 검증할 때 블랙리스트도 확인하면, 로그아웃된 토큰을 거부할 수 있다.

```
Redis 키 설계:
auth:refresh:{userId}    → Refresh Token 저장 (TTL: 7일)
auth:blacklist:{token}   → "logout" (TTL: Access Token 잔여 만료 시간)
```

**왜 Redis인가?**

- 토큰 검증은 **모든 API 요청마다** 발생 → 초고속 조회가 필요 → Redis (in-memory)
- TTL(Time To Live) 기능으로 만료 시간이 지나면 자동 삭제 → 수동 정리 불필요
- Access Token 만료 후에는 블랙리스트에 남아 있을 필요 없음 → TTL이 정확히 맞음

#### 패키지 구조

```
auth-service/src/main/java/com/buildflow/auth/
├── AuthServiceApplication.java
├── domain/
│   └── user/
│       ├── controller/AuthController.java     # REST API 엔드포인트
│       ├── dto/                               # 요청/응답 DTO
│       │   ├── LoginRequest.java
│       │   ├── SignUpRequest.java
│       │   ├── RefreshRequest.java
│       │   └── TokenResponse.java
│       ├── entity/                            # JPA 엔티티
│       │   ├── User.java
│       │   └── UserRole.java                  # ADMIN, VIEWER
│       ├── repository/UserRepository.java     # Spring Data JPA
│       └── service/AuthService.java           # 비즈니스 로직
└── global/
    ├── config/
    │   ├── PasswordEncoderConfig.java
    │   ├── RedisConfig.java
    │   └── SecurityConfig.java
    ├── exception/
    │   ├── BusinessException.java
    │   ├── ErrorCode.java
    │   └── GlobalExceptionHandler.java
    ├── jwt/JwtTokenProvider.java              # JWT 생성/검증
    └── response/ApiResponse.java              # 공통 응답 래퍼
```

---

### 3.3 estimate-service (견적 서비스)

**역할**: 견적서 CRUD + 공내역서 AI 파싱

#### 견적서 CRUD

- **생성**: `POST /api/v1/estimates` — 현장ID(siteId)에 연결된 견적서 생성
- **조회**: `GET /api/v1/estimates/{id}`, `GET /api/v1/estimates?siteId={siteId}` — 현장별 필터링
- **수정**: `PUT /api/v1/estimates/{id}` — DRAFT 상태일 때만 가능
- **삭제**: `DELETE /api/v1/estimates/{id}` — DRAFT 상태일 때만 가능
- **확정**: `PATCH /api/v1/estimates/{id}/confirm` — CONFIRMED 상태로 변경, 이후 수정/삭제 불가

**견적 상태 흐름:**
```
DRAFT (초안) → CONFIRMED (확정)
                    ↓
         수정/삭제 불가, Kafka로 이벤트 발행
```

#### 공내역서 AI 파싱 흐름

건설업에서 발주처가 보내는 "공내역서"는 엑셀 파일인데, 형식이 제각각이다. 이를 AI가 자동으로 구조화된 데이터로 변환한다.

```
[파싱 흐름]
1. POST /api/v1/estimates/parse (MultipartFile: 엑셀 파일)
2. ExcelParserService: Apache POI로 엑셀 → 텍스트 추출
3. OllamaService: 추출된 텍스트를 Ollama(qwen2.5:7b)에 전달
4. Ollama가 JSON 구조로 파싱 (품명, 규격, 수량, 단가, 금액)
5. ParseResult 반환 → 프론트에서 확인 후 견적서로 등록
```

**왜 Ollama(로컬 LLM)인가?**

- 초기에는 Claude API(claude-sonnet-4-6)를 사용했지만, API 호출 비용이 발생
- Ollama는 로컬에서 무료로 실행 가능 (qwen2.5:7b 모델 사용)
- Mac Apple Silicon GPU를 활용하면 충분한 속도
- 공내역서 파싱은 복잡한 추론이 필요하지 않아 7B 모델로 충분

#### 패키지 구조

```
estimate-service/src/main/java/com/buildflow/estimate/
├── EstimateServiceApplication.java
├── domain/
│   ├── estimate/
│   │   ├── controller/EstimateController.java
│   │   ├── dto/                              # Create/Update Request, Response
│   │   ├── entity/
│   │   │   ├── Estimate.java                 # 견적서 엔티티 (items와 1:N)
│   │   │   ├── EstimateItem.java             # 견적 항목 (품명, 수량, 단가)
│   │   │   └── EstimateStatus.java           # DRAFT, CONFIRMED
│   │   ├── event/EstimateParsedPayload.java  # Kafka 이벤트 페이로드
│   │   ├── repository/EstimateRepository.java
│   │   └── service/EstimateService.java
│   └── parse/
│       ├── controller/ParseController.java   # 공내역서 파싱 API
│       ├── dto/ParseResult.java, ParsedItemResult.java
│       └── service/
│           ├── ExcelParserService.java       # Apache POI 엑셀 파싱
│           ├── OllamaService.java            # Ollama LLM 호출
│           └── ParseService.java             # 파싱 오케스트레이션
└── global/
    ├── config/OllamaConfig.java              # Ollama API 설정
    ├── event/KafkaEvent.java                 # 범용 Kafka 이벤트 래퍼
    ├── exception/                            # 예외 처리
    ├── kafka/KafkaProducerService.java       # Kafka 발행
    └── response/ApiResponse.java
```

---

### 3.4 site-service (현장 서비스)

**역할**: 현장 관리 + 거래처 관리 + 손익 계산 + Kafka 연동

#### 현장(Site) CRUD

- **생성**: `POST /api/v1/sites` — 거래처(Client)와 연결
- **조회**: `GET /api/v1/sites`, `GET /api/v1/sites/{id}`
- **수정**: `PUT /api/v1/sites/{id}`
- **삭제**: `DELETE /api/v1/sites/{id}`
- **상태 변경**: `PATCH /api/v1/sites/{id}/status`

**현장 상태 흐름:**
```
IN_PROGRESS (시공중) → SETTLING (정산중) → WARRANTY (하자보증) → COMPLETED (완료)
```

#### 거래처(Client) CRUD

- `POST/GET/PUT /api/v1/clients` — 현장과 N:1 관계

#### 손익 계산 + Kafka 연동

현장의 손익은 **다른 서비스에서 발생하는 이벤트**에 의해 갱신된다. 이것이 Kafka를 사용하는 핵심 이유다.

```
[손익 계산 흐름]
1. estimate-service에서 견적서 확정 → Kafka로 estimate.parsed 발행
2. site-service의 KafkaConsumerService가 이벤트 수신
3. ProfitService.addEstimateAmount() 호출 → SiteProfit 갱신

4. purchase-service에서 매입 등록 → Kafka로 purchase.registered 발행
5. site-service의 KafkaConsumerService가 이벤트 수신
6. ProfitService.addPurchaseAmount() 호출 → SiteProfit 갱신
```

**SiteProfit 엔티티의 자동 재계산:**
```java
// 견적 금액이 추가되면 자동으로 마진/마진율 재계산
public void addEstimateAmount(BigDecimal amount) {
    this.totalEstimateAmount = this.totalEstimateAmount.add(amount);
    recalculate();
}

private void recalculate() {
    this.margin = this.totalEstimateAmount.subtract(this.totalPurchaseAmount);
    if (this.totalEstimateAmount.compareTo(BigDecimal.ZERO) > 0) {
        this.marginRate = this.margin
                .multiply(BigDecimal.valueOf(100))
                .divide(this.totalEstimateAmount, 2, RoundingMode.HALF_UP);
    }
}
```

**왜 이렇게 설계했는가?**

- 견적/매입 데이터는 각각 estimate-service, purchase-service가 소유한다 (Database per Service)
- site-service가 직접 다른 서비스의 DB를 조회하면 MSA 원칙 위반
- 따라서 Kafka 이벤트를 통해 **필요한 숫자(금액)만** 전달받아 손익을 계산한다
- 이 방식의 트레이드오프: 데이터의 즉각적 일관성(strong consistency)은 포기하지만, 서비스 간 결합도를 낮춘다 (eventual consistency)

#### 패키지 구조

```
site-service/src/main/java/com/buildflow/site/
├── SiteServiceApplication.java
├── domain/
│   ├── client/                               # 거래처 도메인
│   │   ├── controller/ClientController.java
│   │   ├── dto/
│   │   ├── entity/Client.java
│   │   ├── repository/ClientRepository.java
│   │   └── service/ClientService.java
│   ├── profit/                               # 손익 도메인
│   │   ├── controller/ProfitController.java
│   │   ├── dto/ProfitResponse.java
│   │   ├── entity/SiteProfit.java            # 현장별 손익 엔티티
│   │   ├── event/EstimateParsedPayload.java
│   │   ├── repository/SiteProfitRepository.java
│   │   └── service/ProfitService.java
│   └── site/                                 # 현장 도메인
│       ├── controller/SiteController.java
│       ├── dto/
│       ├── entity/
│       │   ├── Site.java                     # Client와 ManyToOne
│       │   └── SiteStatus.java               # IN_PROGRESS, SETTLING, WARRANTY, COMPLETED
│       ├── repository/SiteRepository.java
│       └── service/SiteService.java
└── global/
    ├── event/KafkaEvent.java
    ├── exception/
    ├── kafka/KafkaConsumerService.java        # estimate.parsed, purchase.registered 소비
    └── response/ApiResponse.java
```

---

## 4. 핵심 기술 스택 학습 포인트

### 4.1 Spring Cloud

#### Eureka (서비스 디스커버리)

**문제**: 서비스 A가 서비스 B를 호출하려면 B의 주소를 알아야 한다. 하드코딩하면 스케일 아웃이나 장애 복구 시 주소가 바뀌면 문제가 된다.

**해결**: 모든 서비스가 Eureka에 자신을 등록하고, 호출 시 Eureka에서 상대방 주소를 조회한다.

**면접 포인트**:
- "서비스 디스커버리가 왜 필요한가?" → IP/Port 하드코딩 문제, 동적 스케일링 지원
- "Eureka가 죽으면?" → 클라이언트가 캐시를 가지고 있어 일시적으로 동작, 하지만 SPOF이므로 운영 환경에서는 클러스터링 필요

#### Config Server (중앙 설정)

**문제**: 서비스마다 DB 접속 정보, API 키 등을 각각 관리하면 설정 변경 시 모든 서비스를 재배포해야 한다.

**해결**: Git 저장소(또는 파일 시스템)에 설정을 모아두고, Config Server가 이를 제공한다. 서비스는 기동 시 Config Server에서 설정을 가져간다.

**면접 포인트**:
- "환경별 설정은 어떻게 관리하나?" → 프로파일(dev/staging/prod)별 yml 파일 분리
- "런타임 설정 변경은?" → Spring Cloud Bus + Kafka로 `/actuator/refresh` 전파 가능

#### Gateway (API Gateway)

**문제**: 프론트엔드가 6개 서비스의 주소를 각각 알아야 하고, 인증 로직이 모든 서비스에 중복된다.

**해결**: Gateway가 단일 진입점이 되어 라우팅 + 인증을 처리한다.

**면접 포인트**:
- "Gateway에서 인증을 처리하면 각 서비스는?" → X-User-Id, X-User-Role 헤더만 읽으면 됨
- "Gateway가 병목이 되지 않나?" → Spring Cloud Gateway는 Netty 기반 비동기(Reactive)로 높은 처리량 보장

#### OpenFeign (동기 통신)

**문제**: RestTemplate은 보일러플레이트 코드가 많다.

**해결**: 인터페이스만 선언하면 HTTP 클라이언트를 자동 생성한다. Eureka와 연동하여 서비스 이름으로 호출 가능.

```java
// 예시: site-service에서 estimate-service 호출
@FeignClient(name = "estimate-service")
public interface EstimateClient {
    @GetMapping("/api/v1/estimates")
    ApiResponse<List<EstimateResponse>> getEstimatesBySiteId(@RequestParam Long siteId);
}
```

### 4.2 Kafka (이벤트 기반 아키텍처)

**왜 Kafka인가?**

1. **서비스 간 결합도 제거**: estimate-service가 site-service의 존재를 모른다. 그냥 이벤트를 발행할 뿐.
2. **장애 격리**: site-service가 다운되어도 estimate-service는 정상 동작. Kafka가 메시지를 보관하고 있다가, site-service 복구 시 처리.
3. **확장성**: 나중에 notification-service도 같은 이벤트를 구독하여 알림을 보낼 수 있다 (1:N 발행).

**KRaft 모드**: ZooKeeper 없이 Kafka 자체적으로 메타데이터를 관리한다. 운영 복잡도가 줄고, 소규모 프로젝트에 적합하다.

**Producer/Consumer 패턴:**
```java
// Producer (estimate-service)
kafkaTemplate.send("estimate.parsed", eventKey, kafkaEvent);

// Consumer (site-service)
@KafkaListener(topics = "estimate.parsed", groupId = "site-service-group")
public void consumeEstimateParsed(String message) {
    KafkaEvent<EstimateParsedPayload> event = objectMapper.readValue(message, ...);
    profitService.addEstimateAmount(payload.getSiteId(), payload.getTotalAmount());
}
```

**면접 포인트**:
- "Kafka 메시지가 중복 전달되면?" → eventId 기반 멱등성 처리
- "Consumer가 처리 실패하면?" → Kafka가 offset을 commit하지 않으면 재시도
- "왜 RabbitMQ가 아닌 Kafka?" → 이벤트 로그 보존, 높은 처리량, Consumer Group 지원

### 4.3 JWT + Redis (토큰 인증)

**JWT를 선택한 이유:**
- MSA 환경에서 세션 기반 인증은 서비스마다 세션 저장소를 공유해야 하는 문제가 있다
- JWT는 토큰 자체에 사용자 정보가 담겨 있어 stateless하다
- Gateway에서 한 번만 검증하면, 각 서비스는 헤더에서 사용자 정보를 읽을 수 있다

**Redis를 함께 쓰는 이유:**
- JWT의 단점(로그아웃 시 즉시 무효화 불가)을 보완
- Refresh Token 저장 및 Rotation으로 보안 강화
- 토큰 블랙리스트는 TTL 기반 자동 만료

**토큰 전략:**

| 토큰 | 유효기간 | 저장 위치 | 용도 |
|------|---------|----------|------|
| Access Token | 30분 | 클라이언트 메모리 | API 인증 |
| Refresh Token | 7일 | Redis + 클라이언트 | Access Token 갱신 |

### 4.4 JPA/Hibernate

#### Entity 설계 원칙

1. **@Data 금지**: `@Data`는 `equals()`, `hashCode()`, `toString()`을 자동 생성하는데, JPA 프록시와 충돌하고 양방향 관계에서 무한 루프를 발생시킬 수 있다. `@Getter`만 사용.

2. **@NoArgsConstructor(access = PROTECTED)**: JPA는 기본 생성자가 필요하지만, 외부에서 무분별하게 `new Entity()`를 호출하면 안 된다. `PROTECTED`로 JPA만 사용하도록 제한.

3. **@Builder**: 객체 생성 시 어떤 값이 어떤 필드에 들어가는지 명확하게 표현.

```java
// 좋은 예
User user = User.builder()
        .email("admin@buildflow.com")
        .password(encodedPassword)
        .name("혜민")
        .role(UserRole.ADMIN)
        .build();

// 나쁜 예 (어떤 값이 어떤 필드인지 불명확)
User user = new User("admin@buildflow.com", encodedPassword, "혜민", UserRole.ADMIN);
```

#### JPA Auditing

`@CreatedDate`, `@LastModifiedDate`로 생성/수정 시간을 자동 기록한다. 모든 엔티티에 공통 적용.

```java
@EntityListeners(AuditingEntityListener.class)
public class Site {
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
```

---

## 5. API 설계 패턴

### REST API 컨벤션

```
경로: /api/v1/{resource}
목록 조회: GET    /api/v1/estimates
단건 조회: GET    /api/v1/estimates/{id}
생성:     POST   /api/v1/estimates
수정:     PUT    /api/v1/estimates/{id}
삭제:     DELETE /api/v1/estimates/{id}
상태 변경: PATCH  /api/v1/sites/{id}/status
```

**왜 `/api/v1`을 쓰는가?**
- API 버전 관리. 나중에 v2를 만들어도 v1과 공존 가능
- 프론트엔드가 점진적으로 마이그레이션 가능

### ApiResponse 래퍼

모든 API 응답을 `ApiResponse<T>`로 감싼다. 프론트엔드가 일관된 형태로 응답을 처리할 수 있다.

```java
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private final boolean success;
    private final T data;
    private final String error;

    public static <T> ApiResponse<T> success(T data) { ... }
    public static <T> ApiResponse<T> error(String message) { ... }
}
```

**응답 예시:**
```json
// 성공
{
  "success": true,
  "data": { "id": 1, "title": "견적서 A", ... }
}

// 실패
{
  "success": false,
  "error": "해당 견적서를 찾을 수 없습니다."
}
```

**왜 이렇게 했는가?**
- `success` 필드로 프론트엔드가 HTTP 상태 코드 외에도 성공/실패를 판단 가능
- `@JsonInclude(NON_NULL)`로 성공 시 `error` 필드, 실패 시 `data` 필드가 생략되어 깔끔함
- 모든 서비스가 동일한 구조를 사용하여 프론트엔드 파싱 코드 통일

### 예외 처리 패턴

3단계 예외 처리 구조를 사용한다:

```
1. ErrorCode (enum) — 비즈니스 에러 코드 정의
2. BusinessException — ErrorCode를 감싸는 커스텀 예외
3. GlobalExceptionHandler — @RestControllerAdvice로 일괄 처리
```

```java
// 1. ErrorCode 정의
public enum ErrorCode {
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 존재하는 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다.");

    private final HttpStatus status;
    private final String message;
}

// 2. 비즈니스 로직에서 예외 발생
if (userRepository.existsByEmail(request.email())) {
    throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
}

// 3. GlobalExceptionHandler가 자동으로 ApiResponse.error()로 변환
@ExceptionHandler(BusinessException.class)
public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
    return ResponseEntity
            .status(e.getErrorCode().getStatus())
            .body(ApiResponse.error(e.getMessage()));
}
```

**면접 포인트**:
- "왜 ErrorCode를 enum으로?" → HTTP 상태 코드와 메시지를 한 곳에서 관리, 새로운 에러 추가 시 enum만 추가하면 됨
- "GlobalExceptionHandler의 장점?" → 컨트롤러마다 try-catch 없이, 한 곳에서 일관된 에러 응답 생성

---

## 6. Kafka 이벤트 흐름

### KafkaEvent 공통 구조

모든 Kafka 메시지는 `KafkaEvent<T>` 래퍼를 사용한다:

```java
public class KafkaEvent<T> {
    private String eventId;        // UUID — 멱등성 보장용
    private String eventType;      // "ESTIMATE_PARSED", "PURCHASE_REGISTERED"
    private LocalDateTime timestamp;
    private T payload;             // 실제 데이터

    public static <T> KafkaEvent<T> of(String eventType, T payload) {
        return KafkaEvent.<T>builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .timestamp(LocalDateTime.now())
                .payload(payload)
                .build();
    }
}
```

### 토픽별 데이터 흐름

#### `estimate.parsed`

```
[발행] estimate-service
  견적서 확정(confirm) 시 발행
  → KafkaEvent<EstimateParsedPayload>
    payload: { estimateId, siteId, totalAmount }

[소비] site-service
  ProfitService.addEstimateAmount(siteId, totalAmount)
  → SiteProfit.totalEstimateAmount 갱신
  → 마진/마진율 자동 재계산

[소비 예정] notification-service
  → "현장 A에 견적서가 확정되었습니다" 알림 생성
```

#### `purchase.registered`

```
[발행] purchase-service (구현 완료)
  매입 등록 시 발행
  → KafkaEvent<PurchaseRegisteredPayload>
    payload: { purchaseId, siteId, totalAmount }

[소비] site-service (이미 구현)
  ProfitService.addPurchaseAmount(siteId, totalAmount)
  → SiteProfit.totalPurchaseAmount 갱신
  → 마진/마진율 자동 재계산
```

### 멱등성 처리

**문제**: 네트워크 장애 등으로 Kafka 메시지가 중복 전달될 수 있다.

**해결**: `eventId`(UUID)를 이벤트마다 부여하고, Consumer가 처리 전에 이미 처리한 eventId인지 확인한다. 중복이면 무시.

```
컨벤션:
- 토픽 이름: {domain}.{event} (예: estimate.parsed)
- Consumer Group: {service}-group (예: site-service-group)
- 메시지 키: 관련 엔티티 ID (파티션 키로 순서 보장)
```

---

## 7. 데이터베이스 설계

### Database per Service 패턴

각 서비스가 자신만의 DB 스키마를 소유한다. 물리적으로는 하나의 MySQL 인스턴스지만, 논리적으로 분리한다.

```
MySQL 8.0 (localhost:3306)
├── buildflow_auth       → auth-service 전용
├── buildflow_estimate   → estimate-service 전용
├── buildflow_site       → site-service 전용
├── buildflow_purchase   → purchase-service 전용
└── buildflow_tax        → tax-service 전용
```

**왜 스키마를 분리하는가?**

1. **독립적 배포**: estimate-service의 테이블 구조가 바뀌어도 다른 서비스에 영향 없음
2. **서비스 간 직접 DB 접근 금지**: 다른 서비스의 데이터가 필요하면 OpenFeign이나 Kafka를 통해야 함. 이것이 MSA의 핵심 원칙
3. **장애 격리**: 하나의 스키마에 문제가 생겨도 다른 서비스는 정상 동작

**트레이드오프:**
- JOIN이 불가능 → 서비스 간 데이터 조합이 필요하면 API 호출 필요
- 트랜잭션이 서비스를 넘어갈 수 없음 → eventual consistency 수용
- 데이터 중복 가능 → 비정규화를 의도적으로 수행 (예: SiteProfit에 totalAmount를 별도 저장)

### 주요 테이블 설계

#### buildflow_auth

```
users
├── id (PK, BIGINT, AUTO_INCREMENT)
├── email (UNIQUE, VARCHAR(100))
├── password (VARCHAR, BCrypt 해시)
├── name (VARCHAR(50))
├── role (ENUM: ADMIN, VIEWER)
└── created_at (DATETIME)
```

#### buildflow_estimate

```
estimates
├── id (PK)
├── site_id (BIGINT — site-service의 Site ID를 참조하지만, FK 없음)
├── title (VARCHAR(200))
├── status (ENUM: DRAFT, CONFIRMED)
├── estimate_date (DATE)
├── total_amount (DECIMAL(15,2))
├── memo (VARCHAR(1000))
├── created_at, updated_at

estimate_items
├── id (PK)
├── estimate_id (FK → estimates)
├── item_name, specification, unit
├── quantity, unit_price, amount
```

**왜 site_id에 FK 제약을 걸지 않는가?**

MSA에서 서비스 간 FK는 원칙적으로 금지다. estimate-service의 DB에서 site-service의 테이블을 참조할 수 없다. `site_id`는 그냥 숫자 값이며, 유효성은 비즈니스 로직(OpenFeign 호출)으로 검증한다.

#### buildflow_site

```
sites
├── id (PK)
├── site_name (VARCHAR(200))
├── client_id (FK → clients)
├── address, status, start_date, end_date, memo
├── created_at, updated_at

clients
├── id (PK)
├── company_name, representative_name
├── business_number, phone, address
├── created_at, updated_at

site_profits
├── id (PK)
├── site_id (UNIQUE — 현장당 1개)
├── total_estimate_amount (DECIMAL(15,2))
├── total_purchase_amount (DECIMAL(15,2))
├── margin, margin_rate
├── created_at, updated_at
```

---

## 8. 프론트엔드 구성

### 기술 스택

| 기술 | 역할 | 왜 선택했는가 |
|------|------|-------------|
| React 18 | UI 라이브러리 | 컴포넌트 기반 개발, 생태계 |
| TypeScript | 타입 안정성 | 런타임 에러 방지, IDE 지원 |
| Vite | 빌드 도구 | Webpack 대비 10배 빠른 HMR |
| Ant Design | UI 컴포넌트 (dark theme) | 관리자 대시보드에 적합한 기업용 UI |
| Zustand | 클라이언트 상태 관리 | Redux 대비 보일러플레이트 최소화 |
| TanStack Query | 서버 상태 관리 | 캐싱, 자동 갱신, 로딩/에러 상태 관리 |
| Motion (framer-motion v11+) | 애니메이션 | 선언적 애니메이션 API |
| Lucide React | 아이콘 | 가볍고 깔끔한 아이콘 세트 |
| MSW | API 모킹 | 백엔드 없이도 프론트 개발 가능 |
| bun | 패키지 매니저 + 런타임 | npm 대비 빠른 설치 속도 |

### 디렉토리 구조

```
frontend/src/
├── App.tsx              # 라우터 설정
├── main.tsx             # 엔트리포인트
├── index.css            # 글로벌 스타일
├── api/                 # axios 인스턴스 + API 함수
├── components/          # 공용 컴포넌트
├── layouts/             # 레이아웃 컴포넌트
├── pages/               # 페이지 컴포넌트
├── stores/              # Zustand 스토어
├── types/               # TypeScript 타입 정의
└── mocks/               # MSW 핸들러 (API 모킹)
```

### 상태 관리 전략

**왜 Zustand + TanStack Query를 함께 쓰는가?**

상태를 두 가지로 분류한다:
- **클라이언트 상태** (UI 상태, 모달 열림/닫힘, 현재 선택된 탭): Zustand
- **서버 상태** (API에서 가져온 데이터, 견적서 목록, 현장 정보): TanStack Query

TanStack Query가 서버 상태를 관리하면:
- 자동 캐싱 및 백그라운드 갱신
- 로딩/에러/성공 상태 자동 관리
- 동일 데이터 중복 요청 방지 (deduplication)
- 페이지 포커스 시 자동 refetch

### JWT 인터셉터

axios 인스턴스에 JWT 인터셉터를 설정하여:
- 모든 요청에 `Authorization: Bearer {token}` 헤더 자동 추가
- 401 응답 시 자동으로 refresh token으로 갱신 시도
- 갱신 실패 시 로그인 페이지로 리다이렉트

### 파일 네이밍 컨벤션

```
컴포넌트: PascalCase.tsx  (SiteListPage.tsx, EstimateCard.tsx)
유틸리티: camelCase.ts    (formatDate.ts, calculateMargin.ts)
```

---

## 9. Docker 인프라

### docker-compose 구성

```yaml
services:
  mysql:        # MySQL 8.0 — 서비스별 스키마 분리
  redis:        # Redis 7.2 — JWT 블랙리스트, 캐시
  kafka:        # Kafka 3.7 (KRaft) — 이벤트 브로커
  ollama:       # Ollama — 로컬 LLM (공내역서 AI 파싱)
  zipkin:       # Zipkin 3 — 분산 트레이싱
```

**각 인프라의 역할:**

| 인프라 | 포트 | 역할 | 왜 필요한가 |
|--------|------|------|-----------|
| MySQL 8.0 | 3306 | 영구 데이터 저장 | 서비스별 스키마 분리로 Database per Service 구현 |
| Redis 7.2 | 6379 | 인메모리 캐시 | JWT 블랙리스트, Refresh Token 저장, 향후 캐시 |
| Kafka 3.7 | 9094(외부)/9092(내부) | 메시지 브로커 | 서비스 간 비동기 이벤트 통신 |
| Ollama | 11434 | 로컬 LLM | 공내역서 AI 파싱 (qwen2.5:7b) |
| Zipkin 3 | 9411 | 분산 트레이싱 | MSA 서비스 간 요청 추적, 병목 분석 |

### Kafka 네트워크 설정 상세

```yaml
KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:9094
KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://localhost:9094
```

- `PLAINTEXT://kafka:9092` — Docker 컨테이너 간 내부 통신 (서비스가 Docker로 뜰 때)
- `EXTERNAL://localhost:9094` — 호스트에서 접근 (개발 시 IDE에서 실행할 때)

**왜 두 개의 리스너가 필요한가?**

Docker 내부와 외부에서 Kafka에 접근하는 주소가 다르기 때문이다. 컨테이너는 `kafka:9092`로, 호스트는 `localhost:9094`로 접근한다.

### 서비스 기동 순서

```
1. docker compose up -d          # 인프라 (MySQL, Redis, Kafka, Ollama, Zipkin)
2. Eureka Server (:8761)         # 서비스 디스커버리 (다른 서비스가 등록할 곳)
3. Config Server (:8888)         # 설정 서버 (다른 서비스가 설정을 가져갈 곳)
4. Gateway Server (:8080)        # API Gateway
5. auth, estimate, site, ...     # 비즈니스 서비스 (순서 무관)
```

**왜 이 순서인가?**
- Eureka가 먼저 떠야 다른 서비스가 자신을 등록할 수 있다
- Config Server가 떠야 다른 서비스가 설정을 가져올 수 있다
- Gateway가 떠야 프론트엔드가 API를 호출할 수 있다

### Docker Compose 파일 분리

```bash
docker-compose.yml         # 인프라만 (MySQL, Redis, Kafka, Ollama, Zipkin)
docker-compose.app.yml     # 애플리케이션 서비스 (auth, estimate, site, ...)
```

개발 시에는 인프라만 Docker로, 서비스는 IDE에서 실행한다. 배포 시에는 둘 다 Docker로 실행.

```bash
# 개발 환경 (인프라만)
docker compose up -d

# 전체 실행 (인프라 + 앱)
docker compose -f docker-compose.yml -f docker-compose.app.yml up -d
```

---

## 10. 향후 구현 예정

### tax-service (세금계산서, Port 8085)

**역할**: 매출/매입 세금계산서 관리 및 미수금 추적

**구현 내용:**
- 세금계산서 Entity: 현장ID, 구분(매출/매입), 공급가액, 세액, 발행일, 입금여부
- CRUD + 미수금 조회: 발행했지만 입금 안 된 세금계산서 필터링
- 입금 확인: `PATCH /api/v1/taxes/{id}/confirm-payment`

**왜 필요한가**: 건설업에서 "돈 받을 게 얼마나 남았는지"가 가장 중요한 경영 정보다. 미수금 = 매출 세금계산서 합계 - 입금 확인 합계.

### notification-service (알림, Port 8086)

**역할**: Kafka 이벤트 구독 → 인앱 알림 + 하자보증보험 관리

**구현 내용:**
- 모든 Kafka 토픽 구독 → 인앱 알림 저장
- 하자보증보험 PDF 업로드 + Tesseract OCR로 만료일 자동 추출
- 만료 D-30, D-7 경고 스케줄러

**왜 필요한가**: 건설업에서 하자보증보험이 만료되면 법적 문제가 생긴다. 자동 알림으로 놓치지 않도록 한다.

### site-service AI 요약 대시보드

**역할**: 전체 현장의 종합 손익을 AI가 요약하여 대시보드로 제공

**구현 내용:**
- 전체 현장 손익 데이터 집계
- Ollama(또는 Claude API)로 자연어 요약 생성
- "이번 달 마진율이 가장 낮은 현장은 A현장(12%)입니다. 매입 비용이 예상보다 30% 초과..." 같은 요약

### 프론트엔드 API 연동

현재 UI만 구현된 상태이며, 실제 API 연동이 필요하다:
- 견적서 CRUD UI → estimate-service
- 현장 관리 UI → site-service
- 로그인 → auth-service (완료)

---

## 부록: 현장 문서 라이프사이클

건설업 업무 흐름에 맞춘 전체 라이프사이클:

```
수주 단계:
  1. 발주처로부터 공내역서(엑셀) 수신
  2. AI 파싱으로 항목 자동 추출 (estimate-service)
  3. 견적서 작성 및 확정 → Kafka 이벤트 발행
  4. 현장 생성 (site-service)

시공 단계:
  5. 자재 매입 등록 (purchase-service) → Kafka 이벤트 → 손익 갱신
  6. 매출/매입 세금계산서 등록 (tax-service)
  7. 추가공사 발생 시 새 견적서 작성

정산 단계:
  8. 미수금 확인 (tax-service)
  9. 입금 처리 → 미수금 감소
  10. 손익 확정 → 현장 상태 "정산중"

완료 단계:
  11. 하자보증보험 등록 (notification-service, PDF+OCR)
  12. 만료 알림 스케줄러 가동
  13. 현장 마감 → 상태 "완료"
```

이 라이프사이클이 BuildFlow의 전체 비즈니스 로직을 관통하며, 각 서비스가 어느 단계에서 어떤 역할을 하는지를 보여준다. 면접에서 "서비스를 왜 이렇게 나눴는가?"에 대한 답이 된다.
