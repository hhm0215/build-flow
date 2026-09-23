# Kafka 발행·소비 실패 운영 메모

## 범위

`site-service-group`은 견적·매입 이벤트를 손익에 반영하고, `notification-service-group`은 알림을 생성한다. 두 소비자는 `eventId`를 각 서비스 DB의 처리 기록에 저장한다. 처리 기록과 업무 변경은 같은 트랜잭션이므로 실패하면 둘 다 롤백된다.

소비 중 예외가 나면 제한 횟수 재시도한 뒤 원본 문자열과 오류 헤더를 `<원본 토픽>.DLT`에 보낸다. DLT 발행도 실패하면 원본 메시지를 성공 처리하지 않는다. DLT에 들어갔다고 업무 반영이 끝난 것은 아니다.

## 점검

견적·매입·세금계산서·보증보험 발행 서비스는 업무 변경과 동일한 DB 트랜잭션에 `outbox_events`를 기록한다. 각 서비스 DB에서 `status`, `attempts`, `next_attempt_at`, `lease_until`, `last_error`를 확인한다. `PENDING`은 전송 대기/재시도, `CLAIMED`는 최대 60초의 발행 lease, `SENT`는 Kafka broker 수락을 뜻하며 **소비 완료를 뜻하지 않는다**. 발행기는 1초마다 최대 100건을 확인하고 Kafka `send()`의 메타데이터/버퍼 대기 및 ACK 대기를 각각 최대 5초로 제한한다. 실패는 1초부터 최대 5분까지 백오프한다.

```sql
SELECT status, COUNT(*) FROM outbox_events GROUP BY status;
SELECT event_id, topic, record_key, attempts, next_attempt_at, lease_until, last_error
FROM outbox_events WHERE status <> 'SENT' ORDER BY created_at LIMIT 50;
```

broker 장애 때는 업무 트랜잭션이 성공해도 `PENDING` 행이 남으며, 복구 후 동일 `eventId`·원본 JSON으로 재전송한다. ACK 직후 `SENT` 기록에 실패해 같은 이벤트가 재전송될 수 있으므로 소비자 처리 기록을 임의로 제거하지 않는다. 장시간 `CLAIMED` 상태인 행은 먼저 발행기·DB 로그 및 실제 lease 만료 여부를 확인한다. 상태를 수동으로 `SENT` 처리하거나 outbox 행을 삭제하면 유실될 수 있다.

로컬 Docker Kafka에서 DLT 토픽 목록을 확인한다.

```powershell
docker compose exec -T kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
```

해당 토픽의 메시지·헤더는 필요한 범위만 확인한다. 원문에 거래 정보가 포함될 수 있으므로 출력 공유와 보관에 주의한다.

```powershell
docker compose exec -T kafka kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic purchase.registered.DLT --from-beginning --timeout-ms 5000 --property print.headers=true
```

문제 확인 순서: 원본 토픽·partition·offset과 `eventId` 기록 → 애플리케이션 오류 로그/DB 상태 확인 → 원인 수정 → 원본 서비스 상태와 손익/알림 결과 대조 → 필요한 이벤트만 재처리. 재처리할 때는 원본 `eventId`를 유지해야 처리 기록의 중복 방지가 동작한다.

## 매입 revision/projection 운영 규칙

`purchase.registered`, `purchase.updated`, `purchase.deleted`는 서로 다른 토픽이라 동일한 `purchaseId`를 record key로 사용해도 토픽 간 도착 순서는 보장되지 않는다. purchase-service는 생성 시 revision 1을 부여하고 수정·삭제마다 행 잠금 아래 1씩 증가시킨다. site-service는 `purchase_profit_projections`의 마지막 전체 상태와 `site_profits`의 기여분을 같은 트랜잭션으로 변경한다.

- 낮은 revision: 지연 이벤트로 간주해 `processed_profit_events`만 기록하고 손익은 바꾸지 않는다.
- 같은 revision·같은 상태: 다른 eventId라도 no-op 처리한다.
- 같은 revision·다른 상태: 계약 충돌로 실패시켜 DLT로 보낸다.
- 높은 revision: 중간 revision 누락을 허용하고 이벤트의 전체 현재 상태로 교체한다.
- 삭제 선도착: 금액 기여가 0인 tombstone을 보존해 늦은 등록·수정이 매입을 부활시키지 못하게 한다.
- revision 누락·0·음수: 값을 추정하지 않고 DLT로 보내 원본 purchase와 대조한다.

배포 전에 기존 데이터와 대기 이벤트가 없는지 확인한다. 아래 조회는 각 서비스가 소유한 스키마에서 따로 실행하며, 서비스 간 직접 DB 접근을 애플리케이션 기능으로 추가하지 않는다.

```sql
-- buildflow_purchase
SELECT COUNT(*) AS purchases FROM purchases;
SELECT status, COUNT(*) FROM outbox_events
WHERE topic IN ('purchase.registered', 'purchase.updated', 'purchase.deleted')
GROUP BY status;

-- buildflow_site
SELECT COUNT(*) AS projections FROM purchase_profit_projections;
SELECT site_id, total_purchase_amount FROM site_profits ORDER BY site_id;
```

기존 purchase 또는 기존 총매입이 있는데 projection이 비어 있으면 신규 이벤트를 바로 소비시키지 않는다. 원본 snapshot 기반 seed/reconciliation 절차를 먼저 설계해야 한다. 현재 로컬 파일럿은 0건 기준선을 확인한 뒤 배포하고, 등록→수정→삭제 및 삭제→수정→등록 역순 스모크에서 최종 tombstone과 총매입 0을 확인한다.

### 혼합 버전 방지 배포 순서

producer와 consumer는 revision 계약을 동시에 전환해야 한다. 신·구 버전이 섞이면 revision 없는 이벤트가 DLT로 가거나 projection 없이 이미 반영된 금액이 다시 더해질 수 있으므로 다음 순서를 유지보수 창에서 지킨다.

1. 매입 생성·수정·삭제 입력을 중지한다.
2. purchase outbox의 `PENDING`·`CLAIMED`가 0인지 확인한다.
3. `site-service-group`의 세 purchase 토픽 lag가 0인지 확인하고 세 DLT를 점검한다.
4. purchase-service와 site-service를 모두 중지하고 아직 신버전을 기동하지 않는다.
5. `purchases=0`, 기존 총매입 0, 미처리 outbox 0인 빈 환경은 두 서비스를 같은 release 이미지로 기동해 신규 테이블/컬럼 생성을 확인한다.
6. 데이터가 있는 환경은 서비스를 중지한 상태에서 명시적 migration으로 nullable `event_revision` 추가 → 기존 purchase revision 1 backfill → NOT NULL 전환 → projection seed/site total reconciliation을 완료하고 검증한 뒤에만 같은 release의 두 서비스를 기동한다.
7. 두 서비스 health와 revision 1 등록 이벤트 처리를 확인한 후 매입 입력을 재개한다.

```powershell
docker compose exec -T kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group site-service-group --describe
docker compose exec -T kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
$phase3DltTopics = 'purchase.registered.DLT', 'purchase.updated.DLT', 'purchase.deleted.DLT'
foreach ($phase3DltTopic in $phase3DltTopics) {
  docker compose exec -T kafka kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic $phase3DltTopic --from-beginning --timeout-ms 5000 --max-messages 1
}
```

같은 `eventId`는 같은 의미의 불변 이벤트를 식별해야 하며 producer outbox의 `payload_json`은 전송 재시도 중 바뀌면 안 된다. 동일 eventId의 내용 변조가 의심되면 consumer ledger만 보고 재발행하지 말고 outbox 원문과 Kafka 원문을 대조한다.

## 재처리 주의

- 소비자 그룹 offset을 임의로 초기화하거나 오래된 토픽 전체를 재생하지 않는다. 새 처리 기록에는 도입 이전 이벤트가 들어 있지 않아 기존 손익에 이중 반영되거나 기존 알림이 중복 생성될 수 있다. 배포 전후 소비자 그룹의 offset과 기존 손익·알림 건수를 기록한다.
- 매입 이벤트는 revision/projection으로 순서 역전에 안전하지만 DLT를 무조건 재발행하지 않는다. 동일 revision 충돌, revision 누락, siteId 불일치는 원본 purchase와 projection을 대조해 계약 위반을 먼저 해결한다.
- 삭제 이벤트는 원본 purchase 행이 이미 없으므로 purchase-service의 immutable outbox 원문과 projection tombstone을 대조한다.

```sql
SELECT event_id, topic, record_key, status, payload_json
FROM outbox_events
WHERE event_id = '확인할-eventId';
```
- outbox는 신규 업무 이벤트만 보호한다. 도입 이전의 DB 변경에 발행 누락이 있었는지는 DLT만으로 확인할 수 없으므로 원본 서비스 데이터와 집계를 대조한다. 보증보험의 `lastExpiringAlertSentAt`은 enqueue 시 우선 기록해 장기 대기 중 재등록을 막고, broker ACK가 나면 실제 발행일로 갱신해 쿨다운을 다시 시작한다. 아직 대기 중인 outbox가 있으면 날짜가 경과해도 같은 보증보험의 신규 이벤트를 만들지 않는다.
- 자동 재처리 도구는 아직 없다. 기존 데이터와 DLT를 삭제·초기화하지 말고, 재처리가 필요하면 원인·대상 이벤트·예상 손익 변화를 기록한 후 검증한다.
