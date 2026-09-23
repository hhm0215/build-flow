# 회계 확정 상태 변경 보호

- **시작일**: 2026-09-23
- **BACKLOG 항목**: 실사용 UI 라이프사이클 연결
- **예상 규모**: S
- **완료일**: 2026-09-24
- **상태**: COMPLETE (Docker Desktop 로컬 장애로 컨테이너 스모크만 후속)

## 목표

회계에 반영된 확정 견적과 입금 확인된 세금계산서가 API 직접 호출이나 동시 요청으로 수정·삭제되지 않도록 서버 불변식을 강제한다. UI 표시 여부와 무관하게 백엔드가 최종 보호선이어야 한다.

## 정책

- `EstimateStatus.CONFIRMED` 견적의 수정·재확정은 기존 `ESTIMATE_ALREADY_CONFIRMED`, 삭제는 전용 `CONFIRMED_ESTIMATE_DELETE_NOT_ALLOWED` 409 계약으로 거부한다.
- 확정 견적 삭제 시 `estimate.deleted`를 발행하지 않는다. 기존 확정 반영을 되돌리는 기능은 별도의 취소 workflow와 감사 이력으로 설계하기 전까지 제공하지 않는다.
- `paymentConfirmed=true` 세금계산서의 수정·삭제는 `409 CONFLICT`로 거부한다.
- 세금계산서 update/delete/confirm은 모두 같은 `PESSIMISTIC_WRITE` 행 잠금을 사용한다. 먼저 커밋한 요청을 기준으로 직렬화하며 confirm 이후 변경은 실패한다.
- 중복 입금 확인과 확정 후 변경 금지는 의미가 다르므로 별도 오류 코드를 사용한다.

## 구현 범위

- estimate-service delete의 CONFIRMED guard와 삭제 outbox 미생성 검증
- 기존 견적 삭제 MSW가 CONFIRMED 409를 모사하도록 계약 테스트 동기화
- tax-service entity 방어, update/delete 잠금 조회, immutable 오류 코드
- DRAFT/미입금 상태의 기존 수정·삭제 성공 회귀
- 상태 불변·행 유지·outbox 불변·비관적 잠금 회귀 테스트
- 학습 가이드 및 BACKLOG/PROGRESS 동기화

## 제외 / 후속 분할

- 매입 수정·삭제 UI는 다음 별도 PR로 구현한다.
- 세금계산서 수정·삭제 UI와 MSW confirmed 409 계약은 그 다음 별도 PR로 구현한다.
- purchase/tax 금액 DTO의 음수·소수 정밀도 서버 검증은 각 도메인 UI PR의 선행 안전 조건으로 함께 처리한다.
- 확정 견적 취소/정정본 발행, 입금 확인 취소는 별도 감사 가능한 workflow 없이는 추가하지 않는다.

## 체크리스트

- [x] 확정 견적 삭제 409 및 outbox/DB 불변 테스트
- [x] 미확정 견적 삭제 회귀
- [x] 입금 확인 세금계산서 update/delete 409
- [x] tax update/delete/confirm 동일 행 잠금
- [x] 미입금 세금계산서 update/delete 회귀
- [x] 전체 Gradle test와 독립 재검토
- [ ] Docker health 및 실제 409/DB 불변 스모크 — Docker Desktop 4.91의 stale sailor socket 접근 오류로 후속
- [x] 문서·백로그·진행상황 갱신

## 검증 계획

- `./gradlew :estimate-service:test :tax-service:test --rerun-tasks`
- `./gradlew test`
- Docker에서 임시 DRAFT 견적과 미입금 세금계산서를 생성해 확정/입금 확인 후 PUT·DELETE 409, 원본 행 유지, outbox 증가 없음 확인
- 임시 업무 데이터는 검증 후 서비스 API로 제거하되, 확정 상태라 삭제가 금지되는 항목은 테스트 전용 DB 식별자를 기록하고 안전한 정리 절차를 별도로 사용한다.

## 결과 (작업 후 기록)

- 확정 견적 DELETE와 입금 확인 세금계산서 PUT/DELETE가 전용 409 문자열 오류를 반환하도록 서버 불변식을 추가했다.
- estimate delete와 tax update/delete/confirm이 비관적 행 잠금 뒤 상태를 판정한다. H2 실제 서비스 경합 테스트는 `INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID`로 대기 상태를 확인한 뒤 선행 confirm 커밋 이후 변경 요청이 409가 되는 것을 검증한다.
- 거부된 요청 뒤 원본 행·금액·상태와 outbox 건수가 변하지 않으며, DRAFT 견적과 미입금 세금계산서의 기존 변경 경로는 유지된다.
- MockMvc로 실제 DELETE 라우팅→ControllerAdvice→409 JSON 응답을 검증했고 견적 MSW도 같은 계약을 모사한다.
- 전체 Gradle test, 프론트 Vitest 73개, lint, production build 및 독립 역할 재검토를 통과했다.
- Docker Desktop은 `sailor-ingest.sock`과 `.stale`의 Windows 접근 거부로 Linux 엔진이 기동하지 않았다. 0바이트 런타임 소켓만 제거하려 했으나 OS가 거부했으며 볼륨·이미지·DB 초기화는 하지 않았다. 재부팅 또는 4.92 업데이트 후 컨테이너 스모크를 재개한다.
