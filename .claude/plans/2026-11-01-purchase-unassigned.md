# 현장 미배정 매입 + 화면 배정

- **시작일**: 2026-11-01
- **BACKLOG 항목**: 홈택스 매입 세금계산서 적재 준비(사용자 직접 지시 — PROGRESS·BACKLOG·HANDOFF는 이번 작업에서 수정하지 않음)
- **예상 규모**: M
- **상태**: DONE (PR 생성, 병합 전)

## 목표
홈택스 매입 세금계산서처럼 현장 정보가 없는 매입을 '현장 미배정'으로 한꺼번에 저장하고, 가족이 매입 화면에서 현장을 골라(한 건/여러 건) 배정하면 그때 해당 현장 손익에 반영되게 한다.

## 배경 / 동기
설계 결정 출처: **사용자 2026-11-01 선택** — "홈택스 매입 세금계산서는 현장 정보가 없으니 '미배정'으로 한꺼번에 올리고 가족이 화면에서 현장을 골라 배정". 이번 범위는 purchase-service만이며 tax-service 매입 계산서의 siteId 필수는 그대로 둔다.

## 접근법
- **스키마**: `purchases.site_id` NOT NULL → NULL. 기존 행 값은 바꾸지 않는 제약 완화만.
  - opt-in `purchase-flyway` 프로파일: `V3__purchase_site_unassigned.sql`(현재 IS_NULLABLE 확인 후 MODIFY — 기존 V2 관례처럼 baseline 후 migrate·이미 패치된 DB에서도 무해).
  - 기본 프로파일(`ddl-auto: update`, 로컬 실사용 DB 포함): Hibernate update는 기존 컬럼 nullability를 바꾸지 않으므로 `PurchaseSiteIdNullableSchemaPatch`(Flyway 비활성일 때만 등록되는 ApplicationRunner)가 같은 변경을 1회 수행. 이미 NULL 허용이면 no-op, 실패는 error 로그만 남기고 기동은 막지 않음(기존 기능 영향 없음, 미배정 저장만 실패).
- **이벤트 처리 선택**: 미배정 매입은 `purchase.*` 손익 이벤트를 **발행하지 않는다**(site-service에서 무시하는 방식 대신).
  - 근거 1: site-service `applyPurchaseEvent`는 siteId 필수·현장 행 잠금·projection의 현장 불변을 전제로 하고, purchase-service `profit-source` 원천 검증도 payload `siteId` 양수를 요구한다. siteId null 이벤트를 발행하면 소비자 DLT·재계산 원천 503이 생긴다.
  - 근거 2: outbox/eventId 멱등 구조는 손대지 않는다. 배정 순간이 이 매입의 첫 손익 이벤트이므로 site projection 입장에서 신규 매입 → 증가한 revision의 `purchase.registered`. 이후 수정·삭제는 기존 흐름 그대로.
  - 미배정 중 수정은 revision만 증가(이벤트 없음) → 배정 시 revision 단조성 유지.
- **배정 변경**: 이미 배정된 매입의 다른 현장 이동은 409. site projection이 매입 ID별 현장을 고정(`같은 매입의 siteId가 변경되었습니다` 예외)하므로 이동을 지원하려면 site-service 손익 projection의 두 현장 동시 잠금·기여분 이전 설계가 필요 — 이번 범위 밖. 같은 현장 재요청은 멱등 200. 잘못 배정 시 삭제 후 재등록.
- **API**: `POST /purchases` siteId 선택, `GET /purchases?unassigned=true`(siteId와 동시 400), `PATCH /purchases/{id}/site {siteId}`(Feign 현장 확인, 보관 400, 커밋 직전 재확인 롤백), 미배정 증빙 연결 400.
- **site-service**: 코드 변경 없음. siteId 누락 매입 이벤트는 이미 `IllegalArgumentException`(재시도 없음 → DLT)으로 손익 무변경 — 회귀 테스트 추가.
- **프론트**: 매입 목록 '현장 배정' 필터(URL `assignment=unassigned`), 미배정 행 태그 + 검색 가능한 현장 Select + 배정 버튼, 체크박스 다중 선택 일괄 배정(건별 순차, 실패 건은 선택 유지), 미배정 증빙 버튼 비활성, 미배정 건수·합계 안내. 대시보드에 미배정 매입 안내 1줄(클릭 시 필터된 목록). 타입·API·MSW를 백엔드 DTO 기준으로 동기화.

## 산출물 체크리스트
- [x] V3 Flyway + 기본 프로파일 기동 패치
- [x] purchase-service 미배정 생성·수정·삭제, 배정 API, 미배정 필터, 미배정 증빙 거부
- [x] site consumer siteId null 회귀 테스트
- [x] 프론트 필터·태그·배정·일괄 배정·대시보드 안내, MSW·타입
- [x] 백엔드/프론트 단위 테스트, 격리 MySQL 8 마이그레이션 확인
- [x] `docs/API_SPEC.md`, `docs/ERD.md`, `docs/KAFKA_OPERATIONS.md`

## 리스크 / 모르는 것
- 기본 프로파일 기동 패치는 앱이 DDL을 실행한다(기존 `ddl-auto: update`와 같은 권한 범위). 운영 Flyway 전환 시 V3가 같은 일을 하고 패치는 Flyway 활성 시 등록되지 않는다.
- 로컬 실사용 DB에는 이 작업에서 직접 적용하지 않았다(격리 MySQL로 검증). 다음 풀 Docker 재기동 때 purchase 로그 "site_id NOT NULL 제약을 해제했습니다" 1회를 확인.
- 매입 계산서 실제 일괄 적재 스크립트는 후속.

## 결과
- 격리 MySQL 8(tmpfs, 임시 컨테이너): V1+V2 기존 스키마에 합성 2행 → V3 2회 실행 → IS_NULLABLE NO→YES, 기존 행 id·site_id·revision·금액 불변, 두 번째 실행 no-op, NULL 행 저장 가능. 빈 DB `PurchaseFlywayIntegrationTest`(V3까지·Hibernate validate) PASS. 컨테이너 정리 완료.
- 테스트 결과·PR은 PR 본문 참조.
