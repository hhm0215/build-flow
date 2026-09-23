# Kafka 손익 집계 신뢰성 Phase 3 — 매입 revision/projection

- **시작일**: 2026-09-23
- **BACKLOG 항목**: Kafka 손익 집계 신뢰성 보강
- **예상 규모**: L
- **상태**: COMPLETED

## 목표

`purchase.registered`, `purchase.updated`, `purchase.deleted` 세 토픽이 순서와 다르게 도착해도 site-service의 매입 손익이 purchase-service의 최신 상태로 수렴하도록 한다. 중복·지연·삭제 선도착·revision gap에서도 projection, 처리 ledger, 손익이 한 트랜잭션으로 일관되게 유지되어야 한다.

## 배경 / 동기

Kafka는 한 토픽·파티션 안에서만 순서를 보장한다. 현재 매입 세 이벤트는 서로 다른 토픽이며 소비자는 `oldTotalAmount` 기반 증감을 그대로 누적한다. outbox 재시도 중 후속 이벤트가 먼저 발행되면 수정→등록 또는 삭제→수정 순서가 되어 총매입이 오염될 수 있다.

## 접근법

### 발행 측

- `Purchase.eventRevision`을 명시적인 도메인 revision으로 사용한다. 생성은 1, 행 잠금 아래 수정·삭제 직전에 1씩 증가한다.
- `@Version`은 flush 시점 증가라 이벤트 payload와 어긋날 수 있으므로 사용하지 않는다.
- 등록·수정·삭제 payload에 `revision`과 현재 상태 전체를 넣는다. update의 `oldTotalAmount`는 진단/호환용으로 유지하지만 projection 계산에는 사용하지 않는다.
- 삭제는 별도 `PurchaseDeletedPayload`를 사용한다.
- 엔터티 변경·삭제와 outbox JSON 저장은 기존처럼 같은 트랜잭션으로 묶는다.

### 소비 측

- `PurchaseProfitProjection`을 `purchaseId` PK로 저장한다: `siteId`, `lastRevision`, `currentAmount`, `deleted`, `lastEventId`.
- site 행 잠금 뒤 eventId ledger와 projection을 확인한다.
- 낮은 revision은 stale로 ledger만 기록하고 성공 처리한다.
- 같은 revision·같은 상태는 no-op, 같은 revision·다른 상태는 계약 충돌로 실패시켜 DLT로 보낸다.
- 높은 revision은 gap을 허용하고 저장된 이전 기여분에서 이벤트의 전체 현재 상태로 delta를 계산한다.
- 삭제가 먼저 와도 tombstone을 남겨 늦은 등록/수정이 부활시키지 못하게 한다.
- projection·SiteProfit·ProcessedProfitEvent는 한 트랜잭션으로 commit/rollback한다.

### 레거시·배포 정책

- revision 없는 매입 이벤트를 revision 1로 추정하지 않는다. validation 실패로 DLT 처리하고 원본 DB와 대조한다.
- 배포 전에 실제 `purchases`, purchase outbox PENDING/CLAIMED, purchase DLT/lag, site total purchase 기준선을 확인한다.
- 사용자가 실업무 데이터가 없다고 확인했고, 이번 세션 Docker baseline에서도 `purchases=0`, purchase consumer lag 0, DLT 0을 재확인했다. 과거 스모크의 stale 총매입은 아래 결과 절차대로 reconciliation했다.
- 데이터가 있으면 서비스 간 DB 직접 접근 없이 snapshot API/bootstrap event를 별도 설계하고, projection seed와 site total reconciliation을 먼저 수행한다.

## 산출물 체크리스트

- [x] purchase-service 명시적 revision과 세 payload 계약
- [x] site-service purchase projection/tombstone과 delta 반영
- [x] listener revision·전체 상태 validation
- [x] 발행 outbox revision/rollback 테스트
- [x] 주요 lifecycle 순열·stale·동일 revision 충돌·다중 purchase·원자 rollback 테스트
- [x] Kafka 운영 문서·아키텍처·ERD·학습 가이드 동기화
- [x] 전체 Gradle test와 독립 코드 리뷰
- [x] Docker baseline 확인·reconciliation 및 실제 역순 이벤트 스모크

## 리스크 / 모르는 것

- 기존 집계가 있는데 projection만 비어 있으면 첫 이벤트가 중복 가산된다.
- tombstone을 조기 삭제하면 늦은 이벤트가 매입을 부활시킨다. 보존 기준 확정 전 tombstone은 삭제하지 않는다.
- `ddl-auto:update`에서 기존 non-null 컬럼 backfill이 DB별로 다르므로 데이터 0건 확인 없이 운영 적용하지 않는다.
- 현장 물리삭제와 지연 이벤트가 겹치면 DLT가 발생할 수 있으며 현장 삭제 정책은 별도 후속 검토가 필요하다.

## 테스트 / 검증

- create/update/delete revision `1→2→3` 및 outbox 실패 시 revision rollback
- 세 lifecycle 이벤트 주요 순열이 최신 tombstone/금액으로 수렴
- update-before-register, delete-before-register, revision gap
- 동일 eventId 중복, 다른 eventId의 동일 revision 동일/상충 payload
- 서로 다른 purchase revision 독립 처리 및 같은 site 동시 처리
- projection/손익/ledger 중 하나 실패 시 전체 rollback
- revision 누락·0·음수 JSON 거부
- 전체 `./gradlew test`
- Docker 기동 후 실제 DB/outbox/DLT baseline 및 Gateway/서비스 health, 격리 토픽 역순 스모크

## 결과 (작업 후 기록)

- purchase/site 테스트를 `--rerun-tasks`로 실행하고 전체 `gradlew test`를 통과했다.
- 트랜잭션·동시성, 이벤트 계약·테스트, 배포·운영 세 역할의 독립 리뷰에서 P0는 없었다. 기존 데이터 backfill/seed 위험은 배포 게이트와 운영 문서로 명시했고, projection 우회 API 제거 및 유형별 revision 실패 테스트를 추가했다. 동일 eventId는 immutable event라는 계약을 문서화했다.
- Docker baseline은 purchases 0, purchase consumer lag 0, DLT 0이었다. 과거 스모크의 SENT outbox 1건은 보존했고 source 0건과 어긋난 site 총매입 400,000원은 정확히 1행만 조건부로 0원 reconciliation했다.
- 새 site/purchase 이미지를 함께 배포해 health UP, `purchases.event_revision BIGINT NOT NULL`, `purchase_profit_projections` 생성을 확인했다.
- 격리 purchaseId의 `delete r3 → update r2 → register r1` 실제 Kafka 스모크는 revision 3 삭제 tombstone과 ledger 3건, site 총매입 0으로 수렴했다. tombstone/ledger는 토픽 재생 안전을 위해 보존한다.
