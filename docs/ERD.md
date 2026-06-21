# BuildFlow ERD 설계서

> 서비스별 MySQL 스키마 및 테이블 설계.
> Claude Code가 Entity, Repository, 쿼리를 생성할 때 이 문서를 참조한다.

---

## 설계 원칙

- Database per Service: 각 서비스는 자기 스키마만 접근
- 다른 서비스의 데이터는 ID만 저장 (FK 없음, 외래키 제약 없음)
- 모든 테이블에 created_at, updated_at 포함
- PK는 BIGINT AUTO_INCREMENT
- 금액 컬럼은 BIGINT (원 단위, 소수점 없음)
- soft delete가 필요한 테이블은 deleted_at 컬럼 추가

---

## 1. buildflow_auth (auth-service)

### users
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| email | VARCHAR(100) UNIQUE | 로그인 ID |
| password | VARCHAR(255) | BCrypt 해시 |
| name | VARCHAR(50) | 이름 |
| role | ENUM('ADMIN','VIEWER') | 권한 |
| created_at | DATETIME | |
| updated_at | DATETIME | |

---

## 2. buildflow_estimate (estimate-service)

### specifications (공내역서)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| site_id | BIGINT | 현장 ID (site-service 참조, FK 없음) |
| file_name | VARCHAR(255) | 원본 파일명 |
| file_path | VARCHAR(500) | 저장 경로 |
| file_size | BIGINT | 파일 크기 (bytes) |
| sender | VARCHAR(100) | 발주처명 |
| memo | TEXT | 메모 |
| created_at | DATETIME | |
| updated_at | DATETIME | |

### estimates (견적서)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| site_id | BIGINT | 현장 ID |
| estimate_no | VARCHAR(20) UNIQUE | 견적번호 (20260404-001) |
| title | VARCHAR(200) | 견적서 제목 |
| file_name | VARCHAR(255) | 원본 파일명 |
| file_path | VARCHAR(500) | 저장 경로 |
| total_amount | BIGINT | 합계 금액 (파싱 또는 수동 입력) |
| memo | TEXT | 메모 |
| created_at | DATETIME | |
| updated_at | DATETIME | |

### estimate_items (견적서 품목 — 선택적 파싱 시)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| estimate_id | BIGINT FK | estimates.id |
| item_order | INT | 순번 |
| item_name | VARCHAR(200) | 품명 |
| size | VARCHAR(100) | 사이즈 |
| standard | VARCHAR(100) | 규격 |
| quantity | INT | 수량 |
| total_quantity | INT | 총수량 |
| unit_price | BIGINT | 단가 |
| amount | BIGINT | 금액 |
| remark | VARCHAR(500) | 비고 |

---

## 3. buildflow_site (site-service)

### sites (현장)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| site_name | VARCHAR(200) | 현장명 |
| client_id | BIGINT FK | clients.id (발주처) |
| address | VARCHAR(500) | 현장 주소 |
| status | ENUM('IN_PROGRESS','SETTLING','WARRANTY','COMPLETED') | 상태 |
| start_date | DATE | 공사 시작일 |
| end_date | DATE | 공사 종료일 (예정) |
| memo | TEXT | 메모 |
| created_at | DATETIME | |
| updated_at | DATETIME | |

### clients (거래처/발주처)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| company_name | VARCHAR(200) | 회사명 |
| representative | VARCHAR(50) | 대표자명 |
| business_no | VARCHAR(20) | 사업자번호 |
| phone | VARCHAR(20) | 연락처 |
| email | VARCHAR(100) | 이메일 |
| address | VARCHAR(500) | 주소 |
| memo | TEXT | 메모 |
| created_at | DATETIME | |
| updated_at | DATETIME | |

### site_profit_cache (현장 손익 캐시 — 비정규화)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| site_id | BIGINT FK UNIQUE | sites.id |
| total_revenue | BIGINT | 총 매출 (견적서 합계) |
| total_expense | BIGINT | 총 매입 |
| margin | BIGINT | 마진 (매출 - 매입) |
| margin_rate | DECIMAL(5,2) | 마진율 (%) |
| outstanding | BIGINT | 미수금 |
| last_calculated_at | DATETIME | 마지막 계산 시점 |

---

## 4. buildflow_purchase (purchase-service)

### purchases (매입)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| site_id | BIGINT | 현장 ID |
| purchase_type | ENUM('MATERIAL','SERVICE') | 자재/서비스 구분 |
| supplier_name | VARCHAR(200) | 공급업체명 |
| description | VARCHAR(500) | 내용 |
| amount | BIGINT | 금액 |
| purchase_date | DATE | 매입일 |
| memo | TEXT | 메모 |
| created_at | DATETIME | |
| updated_at | DATETIME | |

---

## 5. buildflow_tax (tax-service)

### tax_invoices (세금계산서)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| site_id | BIGINT | 현장 ID |
| invoice_type | ENUM('SALES','PURCHASE') | 매출/매입 구분 |
| counterpart_name | VARCHAR(200) | 거래처명 |
| counterpart_biz_no | VARCHAR(20) | 사업자번호 |
| supply_amount | BIGINT | 공급가액 |
| tax_amount | BIGINT | 세액 |
| total_amount | BIGINT | 합계 (공급가액 + 세액) |
| issue_date | DATE | 발행일 |
| memo | TEXT | 메모 |
| created_at | DATETIME | |
| updated_at | DATETIME | |

### payments (입금 확인)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| tax_invoice_id | BIGINT FK | tax_invoices.id (매출 세금계산서) |
| amount | BIGINT | 입금 금액 |
| payment_date | DATE | 입금일 |
| memo | TEXT | 메모 |
| created_at | DATETIME | |

---

## 6. buildflow_notification (notification-service)

### defect_warranties (하자보증보험)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| site_id | BIGINT NOT NULL | 현장 ID |
| insurance_company | VARCHAR(200) | 보험사명 (OCR 추출 또는 사용자 입력, MANUAL 외 nullable) |
| policy_number | VARCHAR(100) | 증권번호 (OCR 추출) |
| start_date | DATE | 보증 시작일 (OCR 추출) |
| end_date | DATE | 보증 만료일 (OCR 추출) |
| coverage_amount | BIGINT | 보증금액 (사용자 입력, OCR 미추출) |
| file_path | VARCHAR(500) | PDF 저장 경로 |
| memo | TEXT | 메모 |
| ocr_status | ENUM('PENDING','SUCCESS','FAILED','MANUAL') NOT NULL | OCR 처리 상태 (MANUAL=사용자 직접 입력) |
| last_expiring_alert_sent_at | DATE | 만료 임박 알림 마지막 발송일 (스케줄러 cooldown 7일) |
| created_at | DATETIME NOT NULL | |
| updated_at | DATETIME | |

### notifications (알림)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| user_id | BIGINT | 대상 사용자 ID |
| type | ENUM('WARRANTY_EXPIRING','PAYMENT_OVERDUE','SITE_STATUS') | 알림 유형 |
| title | VARCHAR(200) | 알림 제목 |
| message | TEXT | 알림 내용 |
| is_read | BOOLEAN DEFAULT FALSE | 읽음 여부 |
| reference_id | BIGINT | 관련 엔티티 ID |
| reference_type | VARCHAR(50) | 관련 엔티티 타입 |
| created_at | DATETIME | |

---

## 7. buildflow_chat (chat-service)

### chat_sessions (챗봇 세션)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| user_id | BIGINT | 사용자 ID |
| title | VARCHAR(200) | 대화 제목 (자동 생성) |
| created_at | DATETIME | |
| updated_at | DATETIME | |

### chat_messages (챗봇 메시지)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| session_id | BIGINT FK | chat_sessions.id |
| role | ENUM('USER','ASSISTANT') | 발화자 |
| content | TEXT | 메시지 내용 |
| created_at | DATETIME | |

---

## 8. 공통 — Kafka 멱등성

### processed_events (각 소비 서비스의 스키마에 존재)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| event_id | VARCHAR(36) UNIQUE | UUID (Kafka 메시지의 eventId) |
| event_type | VARCHAR(100) | 이벤트 타입 |
| processed_at | DATETIME | 처리 시점 |

---

## 면접 대비 — ERD 관련 예상 질문

### Q: 서비스 간 외래키(FK)를 안 쓰는 이유는?
MSA에서 각 서비스의 DB는 독립적입니다. 서비스 A의 테이블이 서비스 B의 테이블을
직접 참조하면, B의 스키마가 바뀔 때 A도 영향을 받습니다.
그래서 다른 서비스의 ID만 BIGINT으로 저장하고, 실제 데이터는 API로 조회합니다.

### Q: site_profit_cache 테이블은 왜 비정규화했나요?
현장 손익을 조회할 때마다 estimate-service, tax-service에 OpenFeign 호출하면
응답이 느려집니다. Kafka 이벤트로 변경이 발생할 때만 재계산해서
캐시 테이블에 저장해두면, 대시보드 조회는 자기 DB만 읽으면 됩니다.
이게 CQRS(Command Query Responsibility Segregation)의 간소화 버전입니다.

### Q: 금액을 왜 BIGINT으로 했나요?
건설업 견적서에서 소수점이 나오는 경우가 거의 없고,
원 단위로 저장하면 부동소수점 오차를 완전히 피할 수 있습니다.
필요하면 DECIMAL(15,0)도 가능하지만 BIGINT이 연산 성능이 더 좋습니다.

### Q: processed_events 테이블의 역할은?
Kafka 소비자의 멱등성을 보장합니다. 네트워크 문제로 같은 메시지가
두 번 들어올 수 있는데, eventId가 이미 있으면 스킵합니다.
이걸 Idempotent Consumer 패턴이라고 합니다.

---

## 변경 이력

| 버전 | 날짜 | 변경 |
|------|------|------|
| v1.0 | 2026-04-04 | 초안 |
