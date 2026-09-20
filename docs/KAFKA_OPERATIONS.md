# Kafka 소비 실패 운영 메모

## 범위

`site-service-group`은 견적·매입 이벤트를 손익에 반영하고, `notification-service-group`은 알림을 생성한다. 두 소비자는 `eventId`를 각 서비스 DB의 처리 기록에 저장한다. 처리 기록과 업무 변경은 같은 트랜잭션이므로 실패하면 둘 다 롤백된다.

소비 중 예외가 나면 제한 횟수 재시도한 뒤 원본 문자열과 오류 헤더를 `<원본 토픽>.DLT`에 보낸다. DLT 발행도 실패하면 원본 메시지를 성공 처리하지 않는다. DLT에 들어갔다고 업무 반영이 끝난 것은 아니다.

## 점검

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
- Phase 2 outbox 이전에는 DB 커밋과 Kafka 발행이 원자적이지 않다. DLT가 비어 있어도 발행 누락 가능성이 있으므로 원본 서비스 데이터와 집계를 별도로 대조해야 한다.
- 자동 재처리 도구는 아직 없다. 기존 데이터와 DLT를 삭제·초기화하지 말고, 재처리가 필요하면 원인·대상 이벤트·예상 손익 변화를 기록한 후 검증한다.
