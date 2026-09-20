# Kafka 손익 집계 신뢰성 — Phase 1 소비자 보호

- **시작일**: 2026-09-20
- **BACKLOG 항목**: P0 Kafka 손익 집계 신뢰성 보강
- **예상 규모**: M
- **상태**: DONE

## 목표
site-service 손익 갱신과 notification-service 알림 생성이 Kafka 중복 전송 및 일시적 실패에도 유실·중복 없이 처리되도록 한다. Phase 1은 소비 측 보호이며 전체 P0 완료로 표시하지 않는다.

## 배경 / 동기
현재 두 소비자는 예외를 로그만 남기고 삼켜 오프셋이 성공 처리될 수 있다. `eventId`는 메시지에 있지만 검사하지 않으며, `SiteProfit`의 최초 생성 및 누적 갱신은 동시성에 취약하다.

## 접근법
- 두 서비스에 `eventId` 고유 처리 기록을 두고 업무 반영과 동일 DB 트랜잭션으로 저장한다. 잘못된 이벤트는 성공 처리하지 않는다.
- site-service는 같은 현장의 동시 이벤트를 현장 행 잠금으로 직렬화해 최초 손익 행 생성도 보호한다.
- 공통 Spring Kafka 오류 처리에서 제한된 재시도 후 DLT에 원문을 보내고, DLT 발행 실패는 원본 오프셋을 확정하지 않도록 한다. DLT는 운영자가 원인을 확인하고 재처리할 수 있도록 보존한다.
- 새 기록 테이블은 기존 손익 값을 자동 복구하지 않는다. 기존 데이터 삭제·초기화는 하지 않는다.
- 기존 손익·알림에는 `eventId` 기록이 없으므로 배포 전후 consumer group offset과 기존 건수를 대조하고 레거시 토픽 전체 재생을 금지한다.
- 배포 전 로컬 기준선: Kafka 두 소비 그룹의 구독 토픽은 모두 log-end-offset 0이었고, 서비스 API의 현장·견적·세금계산서·알림 목록은 각 0건이었다. 이는 현재 확인한 로컬 환경에만 해당하며 다른 환경의 데이터 존재 여부를 추정하지 않는다.

## 산출물 체크리스트
- [x] site-service 멱등 처리·현장별 직렬화·검증 및 회귀 테스트
- [x] notification-service 멱등 처리·검증 및 회귀 테스트
- [x] bounded retry/DLT 및 실패 전파 테스트
- [x] 문서·정적 리뷰·전체 테스트·Docker 점검·PR/CI/SHA 검증

## 리스크 / 모르는 것
- 발행이 DB 커밋 전에 일어나는 문제는 Phase 2 outbox에서 해결한다.
- 다른 토픽 간 등록·수정·삭제 순서 역전은 Phase 3 projection/revision에서 해결한다. 롤백된 높은 revision 이벤트가 projection에 남지 않도록 outbox를 먼저 도입하며, Phase 1만으로 순서 안전을 주장하지 않는다.
- DLT는 자동 정정이 아니므로 모니터링·재처리 런북이 필요하다.
- 실제 Kafka offset 보존은 단위 테스트만으로 증명할 수 없어 CI와 Docker 점검 범위를 명확히 구분한다.
- `ddl-auto: update` 상태이므로 새 테이블은 로컬 개발 DB에서만 자동 생성된다. 실데이터 배포 마이그레이션은 별도 P1에서 다룬다.

## 테스트 / 검증
- 동일 `eventId` 중복이 손익/알림을 한 번만 반영, 실패 시 기록과 업무 변경 동시 롤백.
- 같은 현장 동시 이벤트가 최초 행을 하나만 만들고 금액을 보존.
- 잘못된 메시지·업무 예외가 재시도/DLT로 흐르고 예외를 삼키지 않음.
- `./gradlew test`, Docker 실서비스 비영속 스모크, CI 검사·SHA 집합 확인.

## 결과 (작업 후 기록)
site-service는 현장 행 잠금 아래 손익 변경과 고유 `eventId` 처리 기록을 같은 트랜잭션으로 저장한다. notification-service도 알림과 처리 기록을 원자적으로 저장한다. 두 소비자는 잘못된 이벤트/업무 예외를 삼키지 않고 제한 재시도 후 원문 DLT에 보내며, DLT 발행 실패를 성공으로 처리하지 않는다. 세금 이벤트는 실제 소비자(notification-service)와 원본 DB 미수금 계산으로 문서를 정정했다.

전체 Gradle test 통과. site-service H2 통합·동시성·롤백 포함 11개 테스트와 notification-service H2 중복·롤백 테스트가 통과했다. 독립 정적 리뷰에서 CRITICAL/HIGH 신규 결함은 없었다. Docker 재빌드 후 두 서비스 health/목록 GET 200, 새 처리 기록 테이블 생성 확인. 현재 로컬 구독 토픽 log-end-offset 0, 현장·견적·세금계산서·알림 목록 각 0건이었다. MySQL 실제 동시 잠금과 Kafka 컨테이너의 DLT 오프셋 보존은 단위/H2 검증만으로 증명하지 못했으므로 제한 사항으로 남긴다.

Phase 1은 소비 측 보호만 완료한다. Phase 2 outbox, Phase 3 매입 revision/projection, 기존 집계 대조를 마쳐야 Kafka P0 전체를 완료 처리한다. [PR #54](https://github.com/hhm0215/build-flow/pull/54)는 GitHub 검사 6개 성공·커밋 2개 SHA 집합 일치 후 merge commit `637ec5c`으로 병합했다.
