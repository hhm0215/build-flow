# 경로·쿼리 타입 오류를 500 대신 400으로

- **시작일**: 2026-11-01
- **BACKLOG 항목**: P1 "경로·쿼리 타입 오류 400 처리" (이 작업으로 추가 후 완료·제거)
- **예상 규모**: S
- **상태**: DONE

## 목표
`GET /api/v1/sites/dashboard`처럼 Long 경로 변수·쿼리 파라미터에 숫자가 아닌 값이 오거나 필수 쿼리 파라미터가 빠지면 500 대신 400 `ApiResponse` 오류를 반환한다.

## 배경 / 동기
`MethodArgumentTypeMismatchException`·`MissingServletRequestParameterException`이 7개 서비스 `GlobalExceptionHandler`의 `Exception` 처리로 떨어져 500 "Unexpected error"와 스택이 남았다. 클라이언트 입력 오류라 장애 신호를 오염시킨다.

## 접근법
- 7개 서비스(auth, chat, estimate, notification, purchase, site, tax) 핸들러에 두 예외 처리 추가. 각 파일의 기존 가시성(public/protected)·`ResponseEntity.badRequest()`·`ApiResponse.error(message)` 형식을 따른다(서비스 공통 에러 코드 필드 없음).
- 메시지: `요청 값 형식이 올바르지 않습니다: {파라미터명}`, `필수 요청 값이 없습니다: {파라미터명}`. 입력값은 응답·로그에 반사하지 않고 파라미터명·기대 타입만 warn 로그.
- 두 예외 모두 기존에 처리하는 서비스가 없어 전부 추가.

## 산출물 체크리스트
- [x] 7개 `GlobalExceptionHandler` 처리 추가
- [x] 서비스별 `TypeMismatchHandlerTest`(standalone MockMvc + 프로브 컨트롤러, 기존 `UnreadableRequestHandlerTest` 관례) 3건씩

## 리스크 / 모르는 것
- 기존 테스트가 500을 기대하던 경로가 있으면 깨질 수 있음 → 전체 `./gradlew test`로 확인.

## 테스트 / 검증
- 경로 변수 비숫자 400·입력값 미반사, 쿼리 비숫자 400, 필수 쿼리 누락 400.
- 로컬 `./gradlew test`(JDK 17) 통과.

## 결과 (작업 후 기록)
- 7개 서비스 핸들러에 두 처리 추가, 서비스별 테스트 3건(총 21건) 추가. 로컬 `./gradlew test` 통과.
- 게이트웨이(WebFlux)는 범위 밖. 다른 4xx(예: `HttpMediaTypeNotSupportedException`)도 500으로 떨어질 수 있으나 이번 범위에서 제외.
