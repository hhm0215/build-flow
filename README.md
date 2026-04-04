# BuildFlow

건설·시공 소규모 업체를 위한 **현장별 문서 통합 관리 + AI 챗봇 플랫폼**

한 현장에 대한 모든 문서(공내역서, 견적서, 세금계산서, 하자보증보험)를 묶어서 관리하고,
현장별 손익/마진/미수금을 실시간으로 파악하며, AI 챗봇으로 자연어 질의가 가능한 시스템입니다.

> 실제 사업(건설 용접업)의 업무 프로세스를 분석하여 설계한 프로젝트입니다.

---

## 프로젝트 개요

### 배경

건설·시공 소규모 업체에서는 현장별 문서를 엑셀, PDF, 카카오톡으로 분산 관리하고 있어
현장 수익이 얼마인지, 미수금이 얼마나 남았는지, 하자보증보험이 언제 만료되는지를
한눈에 파악하기 어려운 상황입니다.

BuildFlow는 이 문제를 해결하기 위해 **현장 단위로 모든 문서와 금액 정보를 통합**하고,
**AI가 데이터를 분석·요약**하여 빠른 의사결정을 돕는 플랫폼입니다.

### 핵심 기능

- **현장 중심 문서 관리** — 공내역서, 견적서, 세금계산서, 하자보증보험을 현장 단위로 묶어 관리
- **실시간 손익 대시보드** — 현장별 매출/매입/마진/마진율/미수금을 자동 계산
- **AI 챗봇 (RAG)** — "강남 현장 마진 얼마야?", "미수금 가장 많은 현장은?" 같은 자연어 질의
- **알림 시스템** — 하자보증보험 만료 경고, 미수금 연체 알림

---

## 시스템 아키텍처

```
                          ┌──────────────────┐
                          │   React Client   │
                          │   :5173          │
                          └────────┬─────────┘
                                   │
                          ┌────────▼─────────┐
                          │  Spring Cloud    │
                          │  Gateway :8080   │◄──► Redis (Rate Limiting, JWT 블랙리스트)
                          └────────┬─────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
     ┌────────▼────────┐ ┌────────▼────────┐ ┌────────▼────────┐
     │  auth-service   │ │estimate-service │ │  site-service   │
     │  :8081          │ │  :8082          │ │  :8083          │
     └─────────────────┘ └─────────────────┘ └────────┬────────┘
                                                      │
              ┌────────────────────┼──────────────────┤
              │                    │                  │
     ┌────────▼────────┐ ┌────────▼────────┐ ┌───────▼─────────┐
     │purchase-service │ │  tax-service    │ │notification-svc │
     │  :8084          │ │  :8085          │ │  :8086          │
     └─────────────────┘ └─────────────────┘ └─────────────────┘
              │                    │                  │
              └────────────────────┼──────────────────┘
                                   │
                          ┌────────▼─────────┐
                          │   Apache Kafka   │
                          │   (이벤트 버스)    │
                          └──────────────────┘

     ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
     │chat-service │  │Eureka Server│  │Config Server│
     │  :8087      │  │  :8761      │  │  :8888      │
     └─────────────┘  └─────────────┘  └─────────────┘
```

### 서비스 구성

| 서비스 | 역할 |
|--------|------|
| **gateway-server** | API 라우팅, JWT 검증, Rate Limiting |
| **auth-service** | 회원 인증, JWT 발급/갱신, Redis 블랙리스트 |
| **estimate-service** | 공내역서/견적서 파일 업로드·관리 |
| **site-service** | 현장 CRUD, 손익 계산, 거래처 관리, AI 요약 |
| **purchase-service** | 자재/서비스 매입 관리 |
| **tax-service** | 세금계산서 CRUD, 미수금 추적, 입금 확인 |
| **notification-service** | 알림, 하자보증보험 PDF + OCR |
| **chat-service** | AI 챗봇 (RAG, SSE 스트리밍) |
| **eureka-server** | 서비스 디스커버리 |
| **config-server** | 중앙 설정 관리 |

---

## 기술 스택

### Backend
| 기술 | 용도 |
|------|------|
| Java 17 / Spring Boot 3.x | 애플리케이션 프레임워크 |
| Spring Cloud Gateway | API Gateway, JWT 필터, Rate Limiting |
| Spring Cloud Netflix Eureka | 서비스 디스커버리 |
| Spring Cloud Config | Git 기반 중앙 설정 관리 |
| Spring Cloud OpenFeign | 서비스 간 동기 통신 |
| Apache Kafka (KRaft) | 이벤트 드리븐 비동기 통신 |
| Redis / Redisson | 캐시(Cache Aside), JWT 블랙리스트, 분산락 |
| Spring Data JPA / QueryDSL | ORM, 복잡한 쿼리 처리 |
| MySQL 8 | 서비스별 스키마 분리 (Database per Service) |
| Zipkin + Micrometer | 분산 트레이싱 |
| Tesseract OCR | 하자보증보험 PDF 텍스트 추출 |

### Frontend
| 기술 | 용도 |
|------|------|
| React 18 / TypeScript | UI 프레임워크 |
| Ant Design | UI 컴포넌트 라이브러리 |
| Zustand | 클라이언트 상태 관리 |
| TanStack Query | 서버 상태 관리 (캐싱, 리페치) |
| Axios | HTTP 클라이언트 (JWT 인터셉터) |

### Infra
| 기술 | 용도 |
|------|------|
| Docker Compose | 로컬 개발 환경 통합 |
| Gradle (Groovy) | 빌드 도구 |
| GitHub Actions | CI/CD (추후) |

---

## 아키텍처 설계 포인트

### 이벤트 드리븐 아키텍처

서비스 간 데이터 동기화는 Kafka를 통한 비동기 이벤트로 처리합니다.
견적서가 업로드되면 `estimate.uploaded` 이벤트가 발행되고,
site-service가 이를 소비하여 현장 손익을 재계산합니다.

```
estimate-service  ──estimate.uploaded──►  site-service (손익 재계산)
purchase-service  ──purchase.registered──►  site-service (손익 재계산)
tax-service       ──tax.invoice.created──►  site-service (미수금 갱신)
tax-service       ──tax.payment.confirmed──►  site-service (미수금 차감)
```

모든 Kafka 메시지에는 UUID 기반 eventId를 포함하며,
소비자 측에서 Idempotent Consumer 패턴으로 중복 처리를 방지합니다.

### Database per Service

MySQL 1개 인스턴스에 서비스별 스키마를 분리하여 논리적 격리를 달성합니다.
서비스 간 데이터 조회는 반드시 API(OpenFeign)를 통해 수행하며,
직접 DB 접근은 금지합니다.

손익 계산처럼 여러 서비스의 데이터가 필요한 경우,
API Composition 패턴으로 site-service가 각 서비스의 API를 호출하여
애플리케이션 레벨에서 합산합니다.

### Redis 활용 (3가지 용도)

| 용도 | 패턴 | 설명 |
|------|------|------|
| 캐시 | Cache Aside | 현장 목록/손익 데이터 캐싱 (TTL 5분) |
| JWT 블랙리스트 | Key-Value | 로그아웃 시 토큰 무효화 (TTL = 토큰 잔여시간) |
| 분산락 | Redisson | 동시 견적서 수정 방지 (TTL 30초) |

### AI 챗봇 (RAG without Vector DB)

데이터 대부분이 정형 데이터(MySQL)이므로 벡터DB를 사용하지 않습니다.
LLM의 function calling으로 질문 의도를 파악하고,
적절한 SQL 쿼리 결과를 LLM 컨텍스트에 주입하여 답변을 생성합니다.

```
사용자: "강남 현장 마진 얼마야?"
  → function calling: getSiteProfit(siteName="강남")
  → DB 조회: 매출 5,200만원, 매입 3,800만원
  → LLM 답변: "강남 리모델링 현장은 마진 1,400만원(26.9%)입니다."
```

---

## 현장 문서 라이프사이클

이 시스템은 건설 현장의 실제 업무 흐름을 반영하여 설계되었습니다.

```
수주  ─►  공내역서 수신 (발주처에서 카톡/이메일)
          견적서 작성 (우리가 직접 단가 산정 후 엑셀 업로드)
          → 계약 확정 → 현장 생성

시공  ─►  자재 매입 시 세금계산서(매입) 등록
          발주처에 세금계산서(매출) 발행
          추가공사 발생 시 견적서 추가 업로드

정산  ─►  미수금 확인 (매출 세금계산서 vs 입금 내역)
          입금 확인 처리 → 현장 손익 확정

완료  ─►  하자보증보험 PDF 등록 + OCR 자동 추출
          보험 만료 후 → 현장 마감
```

> 공내역서와 견적서는 각각 독립된 문서입니다.
> 공내역서는 발주처가 보낸 원본이고, 견적서는 우리가 직접 단가를 정해 작성합니다.
> AI가 하나를 보고 다른 것을 자동 생성하지 않습니다.

---

## 실행 방법

### 사전 요구사항
- Java 17
- Node.js 20+
- Docker Desktop

### 실행

```bash
# 1. 인프라 기동
docker compose up -d

# 2. 인프라 서비스 (순서 중요)
cd eureka-server && ./gradlew bootRun    # 1st
cd config-server && ./gradlew bootRun    # 2nd
cd gateway-server && ./gradlew bootRun   # 3rd

# 3. 비즈니스 서비스
cd auth-service && ./gradlew bootRun
cd estimate-service && ./gradlew bootRun
cd site-service && ./gradlew bootRun
cd purchase-service && ./gradlew bootRun
cd tax-service && ./gradlew bootRun
cd notification-service && ./gradlew bootRun
cd chat-service && ./gradlew bootRun

# 4. 프론트엔드
cd frontend && npm install && npm run dev
```

### 접속
- 프론트엔드: http://localhost:5173
- Gateway: http://localhost:8080
- Eureka Dashboard: http://localhost:8761
- Zipkin: http://localhost:9411

---

## 프로젝트 구조

```
buildflow/
├── eureka-server/            # 서비스 디스커버리
├── config-server/            # 중앙 설정 관리
├── gateway-server/           # API Gateway
├── auth-service/             # 인증/인가
├── estimate-service/         # 견적서/공내역서
├── site-service/             # 현장/손익/거래처
├── purchase-service/         # 매입 관리
├── tax-service/              # 세금계산서/미수금
├── notification-service/     # 알림/하자보증보험
├── chat-service/             # AI 챗봇 (RAG)
├── frontend/                 # React + TypeScript
├── docs/                     # 설계 문서
│   ├── PLANNING.md           #   기획서
│   ├── ARCHITECTURE.md       #   아키텍처 설계
│   ├── ERD.md                #   DB 스키마 설계
│   ├── API_SPEC.md           #   API 명세
│   ├── DECISIONS.md          #   기술 결정 기록 (ADR)
│   └── PROGRESS.md           #   진행 상황
├── docker-compose.yml        # 로컬 인프라
└── README.md
```

---

## 기술적 도전과 해결

### 1. 서비스 간 데이터 정합성

**문제**: 견적서가 업로드되면 현장 손익이 갱신되어야 하는데, 서비스가 분리되어 있어 트랜잭션을 공유할 수 없음

**해결**: Saga 패턴의 Choreography 방식 적용. Kafka 이벤트로 각 서비스가 자신의 상태를 독립적으로 갱신. site-service는 `site_profit_cache` 비정규화 테이블에 손익을 캐싱하여 조회 성능 확보 (CQRS 간소화)

### 2. Kafka 메시지 중복 처리

**문제**: 네트워크 장애 시 같은 이벤트가 두 번 전달될 수 있음

**해결**: Idempotent Consumer 패턴. 모든 메시지에 UUID eventId를 포함하고, 소비자는 `processed_events` 테이블에서 중복 여부를 확인 후 처리

### 3. AI 챗봇 정확도

**문제**: 일반적인 RAG는 벡터 유사도 검색을 사용하지만, 금액·날짜·현장명 같은 정형 데이터에서는 정확도가 떨어짐

**해결**: 벡터DB 없이, LLM function calling으로 질문 의도를 파악하고 SQL 쿼리 결과를 직접 컨텍스트에 주입. 정형 데이터는 정확한 값을 반환하므로 할루시네이션 위험 최소화

### 4. 분산 환경에서의 동시성 제어

**문제**: MSA 환경에서 같은 견적서를 두 명이 동시에 수정하면 데이터 충돌

**해결**: Redis + Redisson 분산락. 수정 시작 시 `lock:estimate:{id}` 키로 락 획득 (TTL 30초), 실패 시 "다른 사용자가 수정 중" 응답

---

## 개발 환경

- macOS
- IntelliJ IDEA (Backend)
- VS Code (Frontend)
- Docker Desktop

---

## 라이선스

이 프로젝트는 학습 및 포트폴리오 목적으로 제작되었습니다.
