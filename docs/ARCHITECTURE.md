# BuildFlow 아키텍처 설계서

> 이 문서는 BuildFlow의 전체 시스템 구조를 정의한다.
> Claude Code가 서비스 간 통신이나 인프라 구성을 판단할 때 이 문서를 참조한다.

---

## 1. 시스템 전체 구조

```
[React Client :5173]
       │
       ▼
[Spring Cloud Gateway :8080]  ←→  [Redis :6379] (Rate Limiting, JWT 블랙리스트)
       │
       ├─→ [auth-service :8081]
       ├─→ [estimate-service :8082]
       ├─→ [site-service :8083]
       ├─→ [purchase-service :8084]
       ├─→ [tax-service :8085]
       └─→ [notification-service :8086]
              │
              ▼
       [Eureka Server :8761] (서비스 등록/발견)
       [Config Server :8888] (중앙 설정)
       [Kafka :9092] (이벤트 메시징)
       [Zipkin :9411] (분산 트레이싱)
       [MySQL :3306] (서비스별 스키마)
```

### 기동 순서 (반드시 준수)

```
1단계: docker compose up -d        → MySQL, Redis, Kafka, Zipkin
2단계: eureka-server                → 서비스 레지스트리 준비
3단계: config-server                → Eureka에 등록 후 설정 제공 시작
4단계: gateway-server               → Eureka에서 서비스 목록 조회 가능
5단계: 나머지 서비스 (순서 무관)       → 각각 Eureka에 등록
```

---

## 2. 서비스 간 통신

### 2.1 동기 통신 (OpenFeign)

요청-응답이 즉시 필요한 경우. 호출하는 쪽이 응답을 기다린다.

| 호출자 | 대상 | 용도 |
|--------|------|------|
| site-service | estimate-service | 현장별 견적서 금액 합계 조회 |
| site-service | tax-service | 현장별 매입 세금계산서 합계 조회 |
| site-service | tax-service | 현장별 미수금 조회 |
| site-service | notification-service | 현장별 하자보증보험 정보 조회 |
| chat-service | site-service | 챗봇 질문에 필요한 현장 데이터 조회 |
| chat-service | estimate-service | 챗봇 질문에 필요한 견적 데이터 조회 |
| chat-service | tax-service | 챗봇 질문에 필요한 세금계산서/미수금 조회 |
| gateway-server | auth-service | JWT 토큰 검증 |

### 2.2 비동기 통신 (Kafka)

즉시 응답이 필요 없고, 이벤트 발생을 알리는 경우.
발행자는 이벤트를 던지고 끝. 소비자가 각자 처리.

#### 구현된 토픽 (2026-06-09 기준)

| 토픽 | 발행자 | 소비자 | 설명 |
|------|--------|--------|------|
| estimate.parsed | estimate-service | site-service · notification-service | 공내역 AI 파싱 완료 → 손익 재계산 + 알림 |
| estimate.deleted | estimate-service | site-service · notification-service | 견적서 삭제 → 손익 재계산 |
| purchase.registered | purchase-service | site-service · notification-service | 매입 등록 → 손익 재계산 + 알림 |
| purchase.updated | purchase-service | site-service | 매입 수정 → 손익 재계산 |
| purchase.deleted | purchase-service | site-service | 매입 삭제 → 손익 재계산 |
| tax.registered | tax-service | site-service · notification-service | 세금계산서 등록 → 미수금 갱신 + 알림 |
| tax.payment.confirmed | tax-service | site-service | 입금 확인 → 미수금 차감 |

#### 계획 토픽 (미구현)

| 토픽 | 발행자 | 소비자 | 설명 |
|------|--------|--------|------|
| warranty.expiring | notification-service | notification-service | 하자보증 만료 임박 → 알림 (스케줄러 미구현) |
| site.status.changed | site-service | notification-service | 현장 상태 변경 → 알림 |

### 2.3 Kafka 메시지 포맷

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "estimate.uploaded",
  "timestamp": "2026-04-04T10:30:00Z",
  "payload": {
    "siteId": 1,
    "estimateId": 42,
    "totalAmount": 52000000
  }
}
```

- eventId: UUID. 멱등성 보장용 (소비자가 중복 처리 방지)
- Consumer 그룹: {서비스명}-group (예: site-service-group)
- 직렬화: JSON (JsonSerializer/JsonDeserializer)

---

## 3. 서비스별 포트 & 스키마

| 서비스 | 포트 | MySQL 스키마 | 설명 |
|--------|------|-------------|------|
| eureka-server | 8761 | - | 서비스 디스커버리 |
| config-server | 8888 | - | 중앙 설정 관리 |
| gateway-server | 8080 | - | API Gateway |
| auth-service | 8081 | buildflow_auth | 인증/인가 |
| estimate-service | 8082 | buildflow_estimate | 견적서/공내역서 |
| site-service | 8083 | buildflow_site | 현장/손익/거래처 |
| purchase-service | 8084 | buildflow_purchase | 매입 관리 |
| tax-service | 8085 | buildflow_tax | 세금계산서/미수금 |
| notification-service | 8086 | buildflow_notification | 알림/하자보증보험 |
| chat-service | 8087 | buildflow_chat | AI 챗봇 (RAG) |

---

## 4. Gateway 라우팅

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: lb://AUTH-SERVICE
          predicates:
            - Path=/api/v1/auth/**
        - id: estimate-service
          uri: lb://ESTIMATE-SERVICE
          predicates:
            - Path=/api/v1/estimates/**,/api/v1/specifications/**
        - id: site-service
          uri: lb://SITE-SERVICE
          predicates:
            - Path=/api/v1/sites/**,/api/v1/clients/**
        - id: purchase-service
          uri: lb://PURCHASE-SERVICE
          predicates:
            - Path=/api/v1/purchases/**
        - id: tax-service
          uri: lb://TAX-SERVICE
          predicates:
            - Path=/api/v1/tax-invoices/**,/api/v1/payments/**
        - id: notification-service
          uri: lb://NOTIFICATION-SERVICE
          predicates:
            - Path=/api/v1/notifications/**,/api/v1/warranties/**
        - id: chat-service
          uri: lb://CHAT-SERVICE
          predicates:
            - Path=/api/v1/chat/**
```

---

## 5. Redis 사용처

| 용도 | 키 패턴 | TTL | 서비스 |
|------|---------|-----|--------|
| JWT 블랙리스트 | auth:blacklist:{jti} | 토큰 남은 유효시간 | auth-service |
| 현장 목록 캐시 | site:list:user:{userId} | 5분 | site-service |
| 현장 손익 캐시 | site:profit:{siteId} | 5분 | site-service |
| Rate Limiting | ratelimit:{ip} | 1분 | gateway-server |
| 분산락 (견적서 수정) | lock:estimate:{estimateId} | 30초 | estimate-service |
| 챗봇 세션 | chat:session:{sessionId} | 30분 | chat-service |

---

## 6. 면접 대비 — 아키텍처 관련 예상 질문

### Q: 왜 MSA를 선택했나요?
현장/견적/세금계산서/알림이 각각 독립적인 도메인이고, 변경 주기가 다릅니다.
견적서 기능을 수정할 때 알림 서비스에 영향이 가면 안 되니까요.
또한 MSA를 직접 구축하면서 서비스 간 통신, 분산 트랜잭션,
이벤트 드리븐 아키텍처를 실무 수준으로 경험하고 싶었습니다.

### Q: 동기 vs 비동기 통신을 어떤 기준으로 나눴나요?
데이터가 즉시 필요하면 OpenFeign(동기), 알려주기만 하면 되면 Kafka(비동기).
예를 들어 챗봇이 현장 마진을 답변하려면 즉시 데이터가 필요하니까 OpenFeign,
견적서 업로드 후 손익 재계산은 약간 늦어도 괜찮으니까 Kafka.

### Q: Kafka에서 메시지 유실이나 중복 처리는 어떻게 하나요?
eventId(UUID)를 메시지에 포함하고, 소비자 측에서 processed_events 테이블에
eventId를 저장합니다. 이미 처리된 eventId가 오면 스킵하는 방식으로 멱등성을 보장합니다.

### Q: Redis를 어디에 썼나요?
세 가지 용도로 씁니다.
1) JWT 블랙리스트 — 로그아웃 시 토큰을 무효화
2) 캐시 — 현장 목록, 손익 데이터를 Cache Aside 패턴으로 캐싱
3) 분산락 — 동시에 같은 견적서를 수정하는 것을 방지 (Redisson)

### Q: 서비스 간 직접 DB 접근을 왜 금지했나요?
DB 스키마가 서비스의 내부 구현입니다. 다른 서비스가 직접 접근하면
스키마 변경 시 모든 관련 서비스가 동시에 수정되어야 해서 독립 배포가 불가능해집니다.
API(OpenFeign)나 이벤트(Kafka)로만 통신해야 서비스 간 결합도가 낮아집니다.

### Q: Database per Service인데 조인은 어떻게 하나요?
현장 손익을 계산할 때 견적서(estimate-service)와 세금계산서(tax-service) 데이터가
모두 필요합니다. 이 경우 site-service가 OpenFeign으로 각 서비스의 API를 호출해서
애플리케이션 레벨에서 합산합니다. DB 조인 대신 API Composition 패턴을 사용하는 거죠.

---

## 변경 이력

| 버전 | 날짜 | 변경 |
|------|------|------|
| v1.0 | 2026-04-04 | 초안 |
