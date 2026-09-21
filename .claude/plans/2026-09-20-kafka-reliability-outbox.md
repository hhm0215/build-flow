# Kafka 손익 집계 신뢰성 — Phase 2 트랜잭셔널 outbox

- **시작일**: 2026-09-20
- **BACKLOG 항목**: P0 Kafka 손익 집계 신뢰성 보강
- **예상 규모**: L
- **상태**: IN_PROGRESS

## 목표
견적·매입·세금계산서·보증보험 이벤트를 원본 DB 변경과 같은 트랜잭션에 저장하고, 커밋된 outbox 행만 Kafka에 발행한다. DB 롤백 이벤트 유출과 broker 일시 장애 시 발행 유실을 막는다. 이 단계만으로 매입 토픽 간 순서 역전은 해결하지 않는다.

## 배경 / 동기
현재 estimate/purchase/tax의 `KafkaTemplate.send()`는 업무 트랜잭션 중 비동기 호출로 ACK와 DB 커밋의 성공 여부가 연결되지 않는다. 보증보험 스케줄러는 ACK 뒤 DB 쿨다운을 기록해 반대 방향의 중복 위험이 있다. Phase 1은 소비자 중복·실패에 대한 보호를 갖췄으므로, 동일 eventId 재발행이 가능한 outbox를 다음 선행 단계로 둔다.

## 접근법
- 네 서비스의 자체 DB에 동일한 `outbox_events` 모델을 둔다: `eventId` UUID PK, 토픽/record key, 최초 직렬화한 이벤트 JSON, `PENDING/CLAIMED/SENT`, 시도 횟수·다음 시도 시각·claim token/lease 만료·생성/전송 시각·오류 요약. 변경 서비스 트랜잭션 안에서 `PENDING` 행을 한 번만 기록한다.
- Dispatcher는 짧은 DB 트랜잭션에서 MySQL 8 `FOR UPDATE SKIP LOCKED`로 대기/lease 만료 행 하나를 claim한다. 잠금 해제 후 전용 StringSerializer producer로 저장된 원문 JSON을 전송하고 broker ACK를 기다린다. ACK 후 claim token 조건부로 `SENT` 처리한다. 실패 시 token이 아직 자기 것일 때만 지수 백오프로 `PENDING` 복귀한다. ACK 직후 프로세스가 중단되면 같은 eventId/JSON을 다시 보내고 Phase 1 소비자 기록이 중복을 흡수한다.
- 한 번의 send 대기 한도 5초, lease 60초를 기본으로 하며, 스케줄러는 처리량과 장애 시 연결 점유가 제한되도록 회당 처리 건수에 상한을 둔다. `acks=all`, producer idempotence를 유지한다.
- 견적 확정/삭제, 매입 등록/수정/삭제, 세금계산서 등록/입금 확인의 기존 이벤트 envelope와 key를 그대로 보존한다. tax 입금 확인·매입 수정/삭제에는 행 잠금을 넣어 동시 업무 변경과 중복 이벤트를 직렬화한다.
- 보증보험 스케줄러는 보증 행 잠금 후 자격/쿨다운을 재검사하고, 동일 트랜잭션에 outbox enqueue와 쿨다운 표시를 기록한다. broker 장애에도 대기 행이 재전송되며 이중 enqueue되지 않는다.
- direct Kafka send와 outbox send를 한 경로에서 동시에 실행하지 않는다. 기존 DB/토픽 데이터를 삭제·초기화하지 않는다.

## 산출물 체크리스트
- [x] estimate-service outbox·dispatcher·테스트
- [x] purchase-service outbox·dispatcher·동시 업무 잠금·테스트
- [x] tax-service outbox·dispatcher·입금 확인 잠금·테스트
- [x] notification-service warranty outbox·scheduler 경합 보호·테스트
- [x] eventId/JSON·ACK/재시도/lease·롤백 회귀 검증, 독립 정적 리뷰
- [ ] 전체 Gradle test·Docker 비영속 점검·문서·PR/CI/SHA/병합

## 리스크 / 모르는 것
- MySQL `SKIP LOCKED`와 실제 Kafka ACK·offset은 H2/Mockito만으로 입증할 수 없다. Docker 검증 범위를 분리해 기록한다.
- `ddl-auto: update`는 로컬 개발 설정이다. 실데이터 환경의 버전형 마이그레이션은 P1 별도 항목이며 임의 초기화하지 않는다.
- `SENT`는 broker 수락만 의미하고 소비 완료를 뜻하지 않는다. DLT 및 Phase 1 기록으로 소비 실패를 확인한다.
- Phase 3 매입 revision/projection이 끝나기 전에는 토픽 간 순서 역전이 남는다. 기존 손익·원본 데이터 대조 없이는 P0 완료로 표시하지 않는다.

## 테스트 / 검증
- 업무 롤백 시 outbox 행 없음, broker 장애 시 업무 데이터+PENDING 유지, ACK 후 SENT, ACK 직후 상태 갱신 실패 시 같은 eventId/JSON 재발행.
- 다중 dispatcher 단일 claim, lease 회수와 stale token 보호, 실패 백오프.
- tax 동시 입금 확인 한 건, 매입 동시 수정/삭제 직렬화, 보증 스케줄러 중복 enqueue 방지.
- 서비스별 테스트/전체 Gradle test, Docker 기동·테이블 생성·비영속 API/health, CI 및 PR SHA 집합 검증.

## 결과 (작업 후 기록)
네 서비스의 업무 트랜잭션 안에 동일 eventId/JSON의 outbox 행을 기록하고, broker ACK 뒤에만 `SENT`로 바꾸는 dispatcher를 연결했다. 매입 수정·삭제와 세금 입금 확인은 행 잠금으로 직렬화했다. 보증보험은 행 잠금·대기 outbox 조회로 장기 장애 중 중복 enqueue를 막고 ACK 날짜에 쿨다운을 다시 시작한다. 네 서비스 모두 `send()` 자체 대기와 ACK 대기를 각각 5초로 제한한다.

전체 Gradle test 및 보완 후 4개 서비스 test가 통과했다. 독립 교차 리뷰에서 보증보험 장기 장애 중복, 스케줄러 지연, 매입 interrupt 후 추가 claim, Kafka `send()` 사전 차단을 발견해 수정·회귀 테스트를 추가했다. 기존 확정 견적 삭제와 입금 완료 세금계산서 수정·삭제 정책은 이번 outbox 범위가 아니며 별도 P0 후속이다. MySQL/Kafka 실제 ACK와 테이블 생성·비영속 API 스모크는 Docker 엔진 기동 후 확인한다. 엔진이 중지되어 현재 미검증이며 PR·병합도 아직 수행하지 않았다.
