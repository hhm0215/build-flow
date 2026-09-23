# 매입 금액 검증 및 수정·삭제 UI

- **시작일**: 2026-09-24
- **BACKLOG 항목**: 실사용 UI 라이프사이클 연결
- **예상 규모**: M
- **완료일**: 2026-09-24
- **상태**: COMPLETE (Docker Desktop 로컬 장애로 컨테이너 스모크만 후속)

## 목표

직접 API 호출에서도 음수·과도한 소수 정밀도·DB 총액 범위를 벗어난 매입 금액을 거부하고, 화면에서 기존 매입을 안전하게 수정·삭제할 수 있게 한다. 변경 후 매입 목록과 비동기 Kafka 손익 캐시가 서로 다른 시점의 값을 오래 표시하지 않도록 관련 쿼리를 stale 처리한다.

## 계약

- 생성·수정 DTO 모두 수량 1 이상, 단가 0 이상, 단가 정수 10자리·소수 2자리 이하를 검증한다.
- 수량×단가는 `purchases.total_amount DECIMAL(15,2)` 범위 안이어야 한다.
- 수정 요청은 `siteId`와 서버 계산 `totalAmount`를 보내지 않는다. 기존 현장 연결은 바꾸지 않는다.
- 수정·삭제는 기존 서버의 `PESSIMISTIC_WRITE`와 revision/outbox 정책을 그대로 사용한다.
- 성공 시 모든 매입 목록을 무효화하고 해당 현장 손익은 즉시 재조회하지 않고 stale 처리한다. Kafka 집계가 도착한 뒤 다음 조회에서 최신 값을 읽게 한다.
- 없는 ID의 PUT/DELETE는 실서버와 MSW 모두 404 문자열 오류를 반환한다.

## 구현 범위

- purchase-service 생성·수정 금액 Bean Validation 및 경계 테스트
- 별도 `PurchaseUpdateRequest` 프론트 타입과 API mutation 계약
- 기존 값 사전 채움 수정 모달, 삭제 확인 모달
- 서버 오류 표시, 입력 유지, 재시도, 중복 요청과 진행 중 닫기 차단
- 목록 처리 열 및 수정·삭제 진입 버튼
- MSW PUT/DELETE 성공·404와 금액 400 계약
- 매입 목록·현장 손익 쿼리 invalidation 테스트
- API/학습/백로그/진행 문서 동기화

## 제외 / 후속

- 현장 연결 변경은 수정 범위에서 제외한다.
- 세금계산서 금액 검증과 수정·삭제 UI는 다음 별도 PR이다.
- Kafka 집계 직후 화면 자동 폴링은 추가하지 않는다. 현재 outbox·소비자 정책상 다음 조회에서 수렴시키며 필요하면 실사용 피드백으로 보강한다.
- Docker Desktop stale socket 장애가 지속되면 컨테이너 스모크는 엔진 복구 후 별도 확인한다.

## 체크리스트

- [x] 서버 금액 검증과 경계 회귀
- [x] 프론트 요청 타입 및 캐시 정책
- [x] 수정 모달과 테스트
- [x] 삭제 모달과 테스트
- [x] MSW 계약 테스트
- [x] 전체 Gradle·프론트 lint/test/build
- [x] 독립 역할 재검토
- [x] Docker health/API 스모크 또는 명시적 후속 기록 — Desktop stale socket 장애로 후속 기록
- [x] 문서·백로그·진행상황 갱신

## 검증 계획

- `./gradlew :purchase-service:test --rerun-tasks`
- `./gradlew test`
- `bun run lint`
- `bun run test -- --run`
- `bun run build`
- Docker 복구 시 임시 미확정 매입 생성→PUT→GET→DELETE→404 및 outbox revision 확인

## 결과 (작업 후 기록)

- create/update DTO와 Entity가 수량 정수·단가 부호/정밀도·단가/총액 DB 상한을 함께 보호한다. 소수 수량은 운영 ObjectMapper 설정에서도 역직렬화 400으로 거부한다.
- 잘못된 생성은 purchase/outbox를 남기지 않고 잘못된 수정은 기존 총액·revision·outbox를 보존한다. DB 상한에 근접한 정상 금액 저장도 H2 JPA로 검증했다.
- 별도 `PurchaseUpdateRequest`, 수정 사전 채움, 삭제 확인, 오류 표시·입력 보존·재시도, sync 중복 제출 방지를 구현했다. 생성 모달의 기존 중복 등록 위험도 같은 패턴으로 보강했다.
- nullable supplier/purchaseDate/memo를 타입에 반영하고 SiteList/SiteDetail 정렬·표시를 null-safe하게 바꿨다.
- 매입 변경 성공 후 목록은 무효화하고 해당 현장 손익과 dashboard prefix는 `refetchType:none`으로 stale 처리해 Kafka 도착 전 즉시 재조회 경합을 피한다.
- 독립 세 역할 리뷰에서 큰 정상 단가가 부동소수 오차로 거절되는 P0를 발견해 문자열→BigInt cents 계산으로 교체하고 `.12`·`.97` 회귀를 추가했다. 최종 재검토에는 병합 차단 결함이 없다.
- 전체 Gradle test, purchase-service 재실행, 프론트 Vitest 29파일 89개, lint, production build가 성공했다. 기존 500KB 번들 경고만 P2에 유지한다.
- Docker Desktop stale sailor socket 접근 거부가 지속되어 컨테이너 API 스모크는 엔진 복구 후 수행한다.
