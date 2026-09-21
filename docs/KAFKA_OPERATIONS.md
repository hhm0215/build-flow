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

## 재처리 주의

- 소비자 그룹 offset을 임의로 초기화하거나 오래된 토픽 전체를 재생하지 않는다. 새 처리 기록에는 도입 이전 이벤트가 들어 있지 않아 기존 손익에 이중 반영되거나 기존 알림이 중복 생성될 수 있다. 배포 전후 소비자 그룹의 offset과 기존 손익·알림 건수를 기록한다.
- 매입 등록·수정·삭제는 토픽이 달라 순서가 바뀔 수 있다. Phase 3 source projection이 준비되기 전에는 DLT 이벤트를 무조건 재발행하지 말고 현장별 원본 금액과 대조한다.
- outbox는 신규 업무 이벤트만 보호한다. 도입 이전의 DB 변경에 발행 누락이 있었는지는 DLT만으로 확인할 수 없으므로 원본 서비스 데이터와 집계를 대조한다. 보증보험의 `lastExpiringAlertSentAt`은 enqueue 시 우선 기록해 장기 대기 중 재등록을 막고, broker ACK가 나면 실제 발행일로 갱신해 쿨다운을 다시 시작한다. 아직 대기 중인 outbox가 있으면 날짜가 경과해도 같은 보증보험의 신규 이벤트를 만들지 않는다.
- 자동 재처리 도구는 아직 없다. 기존 데이터와 DLT를 삭제·초기화하지 말고, 재처리가 필요하면 원인·대상 이벤트·예상 손익 변화를 기록한 후 검증한다.
