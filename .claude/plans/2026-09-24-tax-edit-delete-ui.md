# 세금계산서 금액 검증 및 수정·삭제 UI

- **시작일**: 2026-09-24
- **BACKLOG 항목**: 실사용 UI 라이프사이클 연결
- **예상 규모**: M
- **완료일**: 2026-09-24
- **상태**: COMPLETE (Docker Desktop 로컬 장애로 컨테이너 스모크만 후속)

## 목표

직접 API 호출에서도 음수·과도한 소수 정밀도·DB 합계 범위를 벗어난 세금계산서 금액을 거부하고, 화면에서 미입금 세금계산서를 안전하게 수정·삭제할 수 있게 한다. 입금 확인된 건의 409 불변식과 원본 데이터는 그대로 보존한다.

## 계약

- 생성·수정 DTO에서 `siteId` 양수, 공급가액·세액 0 이상, 각각 정수 13자리·소수 2자리 이하를 검증한다.
- 공급가액과 세액의 합계는 `tax_invoices.total_amount DECIMAL(15,2)` 범위 안이어야 하며 반올림 없이 정확히 저장한다.
- 수정 요청은 `siteId`, 서버 계산 `totalAmount`, 입금 상태를 보내지 않는다. 기존 현장 연결은 바꾸지 않는다.
- 수정·삭제는 기존 `PESSIMISTIC_WRITE`와 입금 확인 후 409 보호를 그대로 사용한다.
- 입금 확인은 매출(`SALES`) 세금계산서에만 허용하며, 매입(`PURCHASE`) 직접 API 요청은 DB·outbox 변경 없이 409로 거부한다.
- 성공 시 전체 세금계산서 목록·현장별 목록·미수금 조회를 포함하는 tax query prefix를 무효화한다.
- 없는 ID의 PUT/DELETE는 실서버와 MSW 모두 404 문자열 오류를 반환한다.

## 구현 범위

- tax-service 생성·수정 금액 Bean Validation, Entity 합계 검증, malformed JSON 400 계약
- 매입 세금계산서 입금 확인 차단과 HTTP 409 계약
- invalid create/update의 DB·outbox·기존 필드 불변 테스트
- 별도 `TaxInvoiceUpdateRequest` 프론트 타입과 API mutation 계약
- 미입금 건의 기존 값 사전 채움 수정 모달, 삭제 확인 모달
- 서버 오류 표시, 입력 유지, 재시도, 중복 요청과 진행 중 닫기 차단
- 등록 모달에도 같은 금액 검증과 제출 보호 적용
- nullable 응답 안전 렌더링 및 검색·날짜 처리
- MSW PUT/DELETE 성공·404·409와 금액 400 계약
- API/ERD/학습/백로그/진행 문서 동기화

## 제외 / 후속

- 현장 연결 변경, 부분 입금, 입금 확정 취소·정정은 이번 범위에서 제외한다.
- 세금계산서 변경 Kafka 이벤트와 site-service 손익 집계는 현재 제품 계약에 없으므로 추가하지 않는다. 미수금은 tax-service 원본 조회로 계산한다.
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

- `./gradlew :tax-service:test --rerun-tasks`
- `./gradlew test --rerun-tasks`
- `bun run lint`
- `bun run test`
- `bun run build`
- Docker 복구 시 임시 미입금 세금계산서 생성→PUT→GET→DELETE→404 및 입금확정 건 PUT/DELETE 409 확인

## 결과 (작업 후 기록)

- create/update DTO와 Entity가 공급가액·세액의 부호·정밀도·각 금액/합계 DB 상한을 함께 보호한다. invalid create/update의 DB·outbox·기존 필드 불변, 최대 경계, malformed JSON과 운영 ObjectMapper 설정을 검증했다.
- 확정 건 update는 entity 상태 409를 금액 계산보다 먼저 판정한다. 입금 확인은 SALES만 허용하며 PURCHASE 요청은 전용 409와 원본/outbox 불변을 보장한다.
- 별도 `TaxInvoiceUpdateRequest`, 수정 사전 채움, 삭제 확인, 오류 표시·입력 보존·재시도, 중복 제출/진행 중 닫기 방지를 구현했다. nullable counterparty/issueDate/memo와 현장 화면 정렬·표시도 안전하게 맞췄다.
- MSW는 GET detail, PUT/DELETE 404·409, 금액 400, 합계 재계산과 정적 outstanding 경로를 실제 API와 맞췄다. 금액·미수금은 BigInt cents로 계산해 부동소수 오차를 제거했다.
- 독립 리뷰가 직전 매입 MSW의 큰 정상 단가 오거절·소수 총액 오차, tax outstanding 라우트 순서·합산 오차, 불가능한 확정 PURCHASE fixture를 발견했고 모두 회귀 테스트와 함께 수정했다. 최종 재검토에는 병합 차단 결함이 없다.
- 전체 Gradle 41 tasks, tax-service 26 tests, 프론트 Vitest 32파일 108개, lint, production build가 성공했다. 기존 500KB 번들 경고만 P2에 유지한다.
- Docker Desktop stale sailor socket 접근 거부가 지속되어 컨테이너 API 스모크는 엔진 복구 후 수행한다.
