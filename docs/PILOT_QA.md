# 공개 테스트 파일럿 업무 QA

> 2026-10-08 기준. 공개 HTTPS는 테스트 데이터 전용이다. 이 문서는 기능 검증 범위를 기록하며 `docs/SECURITY_OPERATIONS.md`의 실데이터 운영 게이트를 대체하지 않는다. 기존 데이터는 테스트 대상으로 임의 수정·삭제하지 않는다.

## 실행 원칙

2026-10-29 견적 생성·현장 보관 경합: [격리 CI](https://github.com/hhm0215/build-flow/actions/runs/40876771175)에서 실제 MySQL 잠금 대기와 Feign 재확인으로 보관 후 400 및 견적·항목·outbox 롤백을 검증했다. 활성 현장 생성도 PASS. 세금계산서·보증 경로와 재확인 이후 커밋 전 경합은 이 검증 범위 밖이다.

2026-10-31 세금계산서 생성·현장 보관 경합: `scripts/db/test-archive-race-docker.py`에 `exercise_tax`를 추가했다. 아카이브 스크립트 전용 Compose override로 tax-service만 같은 격리 프로젝트(nonce 라벨·호스트 포트/볼륨 없음·읽기 전용 JAR, outbox는 도달 불가 브로커)에 넣고, 공용 하네스와 손익 복구 검증은 바꾸지 않았다. `[QA]` 합성 현장에서 `buildflow_tax.tax_invoices` WRITE 잠금 → 첫 Feign 확인 후 INSERT의 `Waiting for table metadata lock` 확인 → 현장 보관 → 잠금 해제 → 커밋 직전 재확인(`recheckBeforeCommit`)에서 400, 세금계산서·`outbox_events` 0건을 단언하고, 활성 현장 대조군 201·각 1건 커밋을 단언한다. `py_compile`과 병합 Compose 설정 격리 검증(`validate_config`)은 로컬 PASS, 컨테이너 실행은 PR #240 CI `손익 복구` job에서 PASS(`[PASS] MySQL-blocked tax invoice rechecked archived site via Feign and rolled back invoice and outbox`, 대조군 PASS). 보증 경로와 재확인 이후 커밋 전 구간은 여전히 범위 밖이다.

2026-10-31 보증 직접 등록·현장 보관 경합: `exercise_warranty`를 추가했다. 아카이브 스크립트 전용 Compose override(`add_notification_service`)로 notification-service만 같은 격리 프로젝트(nonce 라벨·호스트 포트/볼륨 없음·읽기 전용 JAR·`/tmp` tmpfs, Kafka 리스너 자동 시작 끔, outbox는 도달 불가 브로커, 만료·보존 정리 cron 비활성)에 넣고 공용 하네스는 바꾸지 않았다. 직접 등록(PDF 업로드 아님)은 업로드 디렉터리·Tesseract를 쓰지 않는다. `[QA]` 합성 현장에서 `buildflow_notification.defect_warranties` WRITE 잠금 → 첫 Feign 확인 후 INSERT의 `Waiting for table metadata lock` 확인 → 현장 보관 → 잠금 해제 → 커밋 직전 재확인(`recheckBeforeCommit`)에서 400, 보증·`warranty_audit_events`·`outbox_events` 0건을 단언하고, 활성 현장 대조군 201·보증 1건·감사 이력 1건을 단언한다(직접 등록은 outbox를 쓰지 않음). `py_compile`과 병합 Compose 격리 검증(`validate_config`)만 로컬 확인, 컨테이너 실행은 PR #242 CI `손익 복구` job에서 PASS(`[PASS] MySQL-blocked warranty rechecked archived site via Feign and rolled back warranty and audit`, 대조군 PASS). PDF 업로드 경로, 수정·삭제·확정 경로의 실제 경합, 재확인 이후 커밋 전 구간은 범위 밖이다.

2026-10-31 변경 경로·현장 보관 경합(매입 수정·견적 확정): `exercise_purchase_update`·`exercise_estimate_confirm`을 같은 스크립트에 추가했다. 코드 확인 결과 두 경로 모두 대상 행을 `SELECT ... FOR UPDATE`로 잠근 뒤 첫 보관 검사(`requireSiteNotArchived`) → 변경·outbox 기록 → flush → 커밋 직전 재확인(`recheckNotArchivedBeforeCommit`) 순서이고, 보관 현장이면 409(`SITE_ARCHIVED_DOCUMENT_LOCKED`)다. 문서 테이블을 WRITE 잠금하면 행 잠금 SELECT가 첫 검사 전에 막히므로, 첫 검사 직후 첫 SQL인 outbox 기록(할당 ID `OutboxEvent`의 merge 조회/INSERT)을 막도록 `outbox_events`만 WRITE 잠금했다. 대기 증명은 `Waiting for table metadata lock`인 세션이 대상 문서 행의 InnoDB X 잠금을 쥐고 있음(`performance_schema.data_locks`)으로 판단해, 같은 테이블을 폴링하는 매입 outbox 디스패처와 구분한다(시간 대기로 순서를 정하지 않음). `[QA]` 합성 현장에서 잠금 → 대기 확인 → 현장 보관 → 잠금 해제 → 409, 매입 단가·`event_revision` 그대로·견적 `DRAFT` 그대로·각 `outbox_events` 건수 증가 없음을 단언하고, 활성 현장 대조군은 수정 200(단가 200)·확정 200(`CONFIRMED`)·outbox 1건 증가를 단언한다. 컨테이너 실행은 PR #244 CI `손익 복구` job에서 PASS(매입 수정·견적 확정 모두 409·값과 outbox 불변, 대조군 PASS). 매입 삭제·견적 수정/삭제·세금계산서 변경·입금 취소·증빙 연결·보증 변경 경로, 보증 PDF 업로드, 재확인 이후 커밋 전 구간은 범위 밖이다.

2026-10-31 변경 경로·현장 보관 경합 2차(매입 삭제·세금계산서 수정·입금 취소·보증 수정): `mutation_race`에 잠글 테이블 인자(`lock_table`, 기본 `outbox_events`)를 더해 기존 두 테스트 동작은 그대로 두고, `exercise_purchase_delete`·`exercise_tax_update`·`exercise_payment_cancel`·`exercise_estimate_update`·`exercise_warranty_update`를 추가했다. 코드 확인 결과 다섯 경로 모두 대상 행 `SELECT ... FOR UPDATE` → 첫 보관 검사 → 변경 → flush → `recheckNotArchivedBeforeCommit` 순서이고 보관 현장이면 409(`SITE_ARCHIVED_DOCUMENT_LOCKED`)다. 첫 검사 직후 첫 SQL에 맞춰 잠근 테이블은 매입 삭제 `outbox_events`(삭제 revision 증가는 dirty 상태만, 첫 SQL은 `purchase.deleted` outbox merge), 세금계산서 수정 `tax_payments`(유효 입금 합계 조회), 입금 취소 `tax_payments`(부분 입금이라 이관 보정은 SQL 없이 건너뛰고 입금 행 조회가 첫 SQL), 견적 수정 `estimate_items`(지연 항목 컬렉션 적재 — 고아 삭제·새 항목 INSERT·`estimates` UPDATE보다 앞섬), 보증 수정 `warranty_audit_events`(outbox 없음, `MANUAL_UPDATED` 이력 IDENTITY INSERT)다. 대기 증명은 1차와 같이 `Waiting for table metadata lock` + 실행 SQL에 잠근 테이블명 + 같은 세션의 대상 행 InnoDB X 잠금이며, 이 조건이 행 잠금 SELECT가 잠근 테이블에 먼저 막히지 않았음도 함께 보인다. `[QA]` 합성 현장에서 409와 매입 행·`event_revision`, 계산서 금액, 입금 행 `canceled_at`·계산서 입금 상태, 견적 제목·총액·항목 수/합계, 보증 보험사·보증금액·만료일, 보증 이력 건수, 각 서비스 `outbox_events` 건수가 그대로인지 단언하고, 활성 현장 대조군은 삭제·수정·취소 200과 반영(매입 outbox 1건, 보증 이력 1건 증가)을 단언한다. PR #245 CI `손익 복구` job에서 매입 삭제·세금계산서 수정·입금 취소는 PASS. **견적 수정은 FAIL로 제외**했다: `estimate_items` WRITE 잠금이 FK 메타데이터 잠금으로 부모 `estimates` 행 잠금 SELECT까지 먼저 막아 "행 잠금 보유 중 대기" 조건이 성립하지 않았다(결정적 FAIL, 거짓 PASS 아님). 견적 초안 수정에는 outbox가 없어 다른 잠금 테이블이 없으므로, 고아 항목 DELETE를 InnoDB 행 잠금으로 막고 `data_lock_waits`로 대기를 증명하는 방식이 후속 후보다. 견적 수정 제외 후 재실행에서 보증 수정도 PASS(409, 보증 행·이력 불변, 대조군 PASS). 견적·세금계산서·보증 삭제, 증빙 연결/해제, 보증 PDF 업로드, 재확인 이후 커밋 전 구간은 범위 밖이다.

2026-10-31 변경 경로·현장 보관 경합 3차(견적 초안 수정, 행 잠금 방식): 2차에서 제외한 견적 초안 수정을 테이블 잠금 대신 InnoDB 행 잠금으로 순서를 만들어 `exercise_estimate_update`로 다시 추가했다(새 헬퍼 `row_lock_race`, 기존 `mutation_race`와 다른 테스트는 무변경, 스크립트 마지막에 실행). 코드·매핑 확인: `EstimateService.update`는 `estimates` `FOR UPDATE` → 첫 보관 검사 → `requireDraft` → `estimate.update` → `clearItems`(지연 `PersistentBag` 적재 SELECT — 일관성 읽기라 행 잠금에 막히지 않음) → `addItems` → flush → `recheckNotArchivedBeforeCommit`이며 outbox는 없다. `items`는 `@OneToMany(mappedBy, cascade=ALL, orphanRemoval=true)`, `EstimateItem` ID는 IDENTITY라 트랜잭션 안의 flush cascade에서 새 항목 INSERT가 즉시 실행되고, 그 뒤 ActionQueue가 고아 항목 DELETE → `estimates` UPDATE 순으로 실행한다. 별도 mysql 세션(REPEATABLE READ)이 `START TRANSACTION; SELECT COUNT(*) FROM estimate_items WHERE estimate_id=<id> FOR UPDATE`로 FK 인덱스 next-key·다음 키까지의 갭·항목 PK X 잠금을 잡고(`data_locks`로 보유 확인 후 PUT 전송), 수정 요청의 첫 쓰기인 새 항목 INSERT(갭 삽입 의도 잠금)가 대기한다(RC였다면 고아 DELETE가 PK 잠금에서 대기 — 어느 쪽이든 `estimate_items`). 대기 증명은 `performance_schema.data_lock_waits`에서 요청 잠금이 `estimate_items`·WAITING이고 차단자가 정확히 그 잠금 세션 스레드이며, 같은 요청 트랜잭션이 `estimates` 대상 PRIMARY 행 X 레코드 잠금을 GRANTED로 보유하고 PROCESSLIST 실행 SQL이 `estimate_items` 문장임을 조인으로 확인한다(sleep으로 순서를 정하지 않음, 8초 폴링 상한 — `innodb_lock_wait_timeout` 50초·probe HTTP 10초 이내). 대기 문장 동사는 CI 로그 `[RUN]` 줄에 남긴다. 그 다음 현장 보관 → 잠금 세션 ROLLBACK → 409, 견적 제목·총액·`DRAFT`·항목 수/단가 합계·`outbox_events` 건수 불변, 활성 현장 대조군은 수정 200(제목·총액 350·항목 2건)을 단언한다. `[QA]` 합성 데이터만 쓴다. PR #246 CI `손익 복구` job에서 PASS: 요청이 `estimates` X 잠금을 보유한 채 `estimate_items` INSERT에서 대기함을 확인(`[RUN] Estimate update waits in insert on estimate_items holding estimates X lock`)했고, 보관 후 409·견적과 항목 불변, 대조군 PASS.

2026-10-31 변경 경로·현장 보관 경합 4차(견적 초안·세금계산서·보증 삭제, 매입 증빙 연결/해제): 브랜치 `claude/delete-archive-race`에서 `exercise_estimate_delete`·`exercise_tax_delete`·`exercise_warranty_delete`·`exercise_evidence_link`·`exercise_evidence_unlink`를 스크립트 끝에 추가했다(기존 헬퍼·테스트와 공용 하네스 `test-profit-reconcile-docker.py`는 무변경, 운영 코드 무변경). 코드 확인 결과 다섯 경로 모두 대상 행 `SELECT ... FOR UPDATE` → 첫 보관 검사 → 변경 → flush → `recheckNotArchivedBeforeCommit` 순서이며 보관 현장이면 409(`SITE_ARCHIVED_DOCUMENT_LOCKED`)다. 견적 초안 삭제(`DELETE /api/v1/estimates/{id}`)는 상태 검사 뒤 cascade REMOVE가 항목을 일관성 읽기로 적재하고 flush에서 자식 `estimate_items` DELETE가 부모 DELETE보다 먼저 실행되므로, 3차와 같은 `row_lock_race`(`estimate_items` 행 잠금 — 테이블 잠금은 FK 메타데이터 잠금으로 부모 행 잠금까지 막음)를 쓴다. 세금계산서 삭제(`DELETE /api/v1/taxes/{id}`, 미입금 SALES)는 첫 SQL이 유효 입금 합계 조회라 `mutation_race(lock_table=tax_payments)`이고, SALES라 매입 증빙 Feign 조회는 없다; 취소된 입금 1건을 미리 남겨 원장 삭제 롤백도 확인한다. 보증 삭제(`DELETE /api/v1/warranties/{id}`, 직접 등록이라 파일 보존 기록 SQL 없음)는 첫 SQL이 `DELETED` 이력 IDENTITY INSERT라 `mutation_race(lock_table=warranty_audit_events)`다. 증빙 연결(`PUT /api/v1/purchases/{id}/evidence`)은 첫 검사 뒤 tax-service Feign 조회(같은 현장 매입 계산서 확인)가 끝난 다음 자기 행 `purchases` UPDATE가 유일한 SQL이고, 해제(`DELETE /api/v1/purchases/{id}/evidence`)는 Feign 없이 같은 UPDATE뿐이다. 두 경로는 다른 테이블이 없고 `purchases` 테이블 잠금은 행 잠금 SELECT를 먼저 막으므로 새 헬퍼 `index_gap_race`를 쓴다: 별도 REPEATABLE READ 세션이 `FORCE INDEX (idx_purchases_evidence_tax_invoice)` 동등 잠금 읽기(연결은 `evidence_tax_invoice_id=<계산서>` 0건, 해제는 `IS NULL`)로 바뀔 인덱스 위치의 갭을 잡고(대상 PRIMARY 행은 잠그지 않음을 `data_locks`로 먼저 확인), UPDATE의 보조 인덱스 삽입이 그 갭에서 대기한다. 증명은 `data_lock_waits`에서 요청 잠금이 그 인덱스·WAITING, 차단자가 정확히 그 세션, 같은 트랜잭션이 `purchases` 대상 PRIMARY X 잠금 GRANTED, 실행 SQL이 `update ... purchases`인 조인이며, Feign 조회가 대기 전에 끝나므로 Feign 읽기 타임아웃(3초)과 무관하다. 이를 위해 아카이브 전용 Compose override로 purchase-service에 `purchaseTaxInvoiceReferenceClient.url=http://tax:8085`만 더한다. `[QA]` 합성 데이터에서 409와 견적 제목·총액·`DRAFT`·항목 수/합계, 계산서 금액·입금 상태·입금 원장 건수, 보증 행·이력·보존 기록 건수, 매입 `evidence_tax_invoice_id`·`event_revision`, 각 서비스 `outbox_events` 건수 불변을 단언하고, 활성 현장 대조군은 삭제 200·행 제거(보증 이력 1건 증가), 연결 200·계산서 ID 저장, 해제 200·NULL을 단언한다. PR #248 CI `손익 복구` job에서 5경로 모두 PASS. 첫 실행에서 증빙 연결 대조군이 준비 단계의 새 매입 outbox(+1)를 기준선 전에 세어 FAIL했고(테스트 결함, 제품 정상), 기준선을 준비 뒤로 옮겨 재실행 PASS. 보증 PDF 업로드와 재확인 이후 커밋 전 구간은 범위 밖이다.

2026-10-31 실행 방식 정리: 변경 경로 12개 시나리오는 각자 합성 현장을 쓰고 잠금 세션을 finally에서 닫으므로, 하나가 실패해도 나머지를 계속 실행하고 실패 목록을 모아 마지막에 FAIL로 끝낸다(서비스 기동을 겸하는 생성 4개는 기존처럼 즉시 중단). 지금까지 실패가 CI 첫 실행에서만 드러난 원인이 로컬 미실행이었으므로, 로컬 Docker(격리 nonce 프로젝트, 호스트 포트·사용자 볼륨 미사용)에서 전체 16개 시나리오 PASS(3분 25초)를 확인한 뒤 올린다.

2026-10-31 보증 PDF 업로드·현장 보관 경합: 브랜치 `claude/warranty-upload-race`에서 `exercise_warranty_upload`를 실패 격리 루프(보증 삭제 다음)에 추가했다(공용 하네스·기존 헬퍼·운영 코드 무변경). 코드 확인: `POST /api/v1/warranties/upload`(multipart `siteId`·`file`, 성공 **202** ACCEPTED·`ocrStatus=PENDING`)의 `DefectWarrantyService.createFromOcr`는 첫 현장 확인 → `UPLOAD_DIR`에 UUID `.pdf` 저장(20 MiB·`%PDF-` 서명·암호화 아님·1~10쪽·300 DPI 렌더 한도 검증) → `TransactionTemplate` 안에서 PENDING 행 IDENTITY INSERT(첫 SQL) → `UPLOADED` 이력 → flush → `recheckBeforeCommit` → 커밋 후 `processAsync` OCR 예약 순서다. 보관 현장이면 400(`SITE_ARCHIVED`). 재확인 실패는 콜백 안 예외라 `callbackCompleted=false`이므로 `pendingRowMayBeCommitted`·`existsByFilePath` 재조회 없이 저장 파일을 지운다(커밋 단계 예외일 때만 행 존재 재조회로 보존 판단). 그래서 생성 경로 패턴(`defect_warranties` LOCK TABLES, `Waiting for table metadata lock` + 실행 SQL에 `defect_warranties`)으로 INSERT를 첫 검사 뒤에 멈췄고, 대기 중 컨테이너 tmpfs `/tmp/warranties`의 PDF가 1개 늘었음을 먼저 확인해 이후 정리가 관찰 가능함을 보인다. 합성 PDF는 스크립트가 메모리에서 만든 1쪽 빈 PDF(xref 포함 최소 유효 구조)이고, 공용 `h.api`가 JSON 전용이라 probe 컨테이너에서 python으로 multipart를 보내는 최소 헬퍼를 이 스크립트에만 추가했다. 단언: 보관 후 400·`SITE_ARCHIVED` 메시지, 해당 현장 보증 행 0건, 이력·`outbox_events` 건수 불변, 업로드 디렉터리 PDF 수가 대기 전 기준선으로 복귀(정책대로 삭제), 서비스 로그에 해당 현장 접수 줄(`siteId=<id> status=`)과 새 `OCR 시작` 줄 없음(OCR 미예약). 활성 현장 대조군은 202·PENDING, 행 1건, 이력 증가, PDF 1개 보존, 접수 줄 1건이다. 이미지에 Tesseract가 없어 대조군 비동기 OCR은 실패할 수 있으나 결과를 단언하지 않는다. 로컬 Docker(격리 nonce 프로젝트, 호스트 포트·사용자 볼륨 미사용, 기존 `build-flow_*` 볼륨 4개 유지) 전체 17개 시나리오 2회 연속 PASS(종료 코드 0, PASS 34줄), 업로드 줄 원문:

```
[PASS] MySQL-blocked warranty upload rechecked archived site via Feign: 400, no row/audit, PDF removed, OCR not scheduled
[PASS] Active-site control upload returned 202 PENDING with one row and one stored PDF
```

PR #252 CI `손익 복구` job: PASS(같은 두 줄). 재확인 이후 커밋 전 구간(커밋 단계 예외 시 행 존재 재조회 분기)은 이 실측 범위 밖이다.

- 각 시나리오는 `[QA]` 식별자가 붙은 새 합성 현장과 합성 거래처만 사용한다. 실제 업체명·금액·USB 원본을 입력하지 않는다.
- 생성/수정/조회 결과와 실패 화면을 기록한다. 정리할 때는 테스트로 생성한 정확한 ID만 대상으로 삼고, 다른 데이터에 대한 연쇄 삭제 여부를 먼저 확인한다.
- API 단위 테스트 통과와 VPS 화면의 저장·재조회·집계 통과를 구분한다. 실패 시 화면, 작업, 입력 유형, HTTP 상태와 재현 순서를 남기되 토큰·비밀번호·업무 원문은 수집하지 않는다.

## 동선별 확인표

2026-10-26 복원 후 손익 재계산(PR #205): 삭제된 매입의 projection이 아직 없는 상태에서 재계산→지연 등록만 수신하는 경우를 H2/JPA 회귀로 재현하고 수정했다. 기본 dry-run 무변경, apply와 수신기록의 원자적 반영, 삭제 이벤트 재전달 없이 원가 0 유지, 새 매입 정상 반영, 구 원천 503·수신기록 충돌409·고아 견적 apply409를 검증했다. 2026-10-28 [CI 40623076273](https://github.com/hhm0215/build-flow/actions/runs/40623076273)의 SHA `dad73de`에서 `scripts/db/test-profit-reconcile-docker.py`로 실제 MySQL·Feign·Kafka 경로도 PASS. 자동 outbox 발행은 이 시험에서 비활성화/격리하고 커밋된 합성 이벤트를 선택 재생했다. 공개 VPS·실제 복원본 검증을 대신하지 않는다.

정적 코드 확인 기준 `C/R/U/D`는 API 존재 여부이며, 운영 화면 실측 통과와 동일하지 않다. `—`는 의도적으로 제공하지 않거나 별도 업무 동작이다.

| 대상 | API C/R/U/D | 화면에서 확인한 제약 |
|---|---|---|
| 거래처 | C/R/U/보관 | 삭제 API 없음. 2026-10-17 보관·복원(보관 거래처 수정 409·새 현장 연결 400) 로컬 Docker 실측 PASS |
| 현장 | C/R/U/보관 | 물리 삭제는 409 유지. 2026-10-17 상세 화면 보관/복원·목록 보관 필터 추가, 로컬 Docker 실측 PASS |
| 견적 | C/R/U/D | 초안만 수정·삭제, 확정 후 금액 반영·변경 차단 |
| 매입 | C/R/U/D | 수정/삭제 가능; 삭제 공개 UI 실측 전 |
| 세금계산서 | C/R/U/D | 유효 입금 없는 항목만 수정·삭제. 매출은 분할 입금 기록·취소(2026-10-23, main·VPS 미반영) |
| 보증보험 | C/R/U/D | OCR 처리 중 수정 제한; 파일 삭제는 원본 보존 정책과 충돌 가능 |
| 알림 | 이벤트 생성/R/읽음/— | 일반 CRUD가 아니라 이벤트 소비·읽음 관리 |
| AI 채팅 | 대화 API/조회/—/— | 모델 미기동 시 질문 차단; 업무 데이터 변경 도구 아님 |

| 동선 | 확인할 작업 | 2026-10-07 상태 |
|---|---|---|
| 로그인·세션 | 로그인, 새로고침, 로그아웃 전 동일 토큰 재사용 401 | private PASS; 새 공개 도메인 동일 토큰 검증 PENDING |
| 거래처→현장 | 새 거래처 생성·선택, 현장 생성·목록·상세·수정·상태 변경 | 공개 VPS `[QA]` 거래처·현장 생성·연결·상세 PASS. 현장 상태 `시공 중`→`정산 중`, 메모 수정·재조회 PASS. 단, 견적이 현장보다 먼저 생기는 업무 순서는 미지원 |
| 현장 삭제 | 기존/없는 ID의 DELETE 상태와 연결 자료 보존 확인 | ADR-021의 409/404 코드·HTTP 회귀 PASS, PR #89의 공개 VPS site-service 반영 및 내부 health PASS. 인증된 공개 DELETE 409/404·보관/복원 실측은 PENDING |
| 거래처 수정·삭제 | 잘못 입력한 거래처 정정과 연결 현장 처리 | 현장 상세의 거래처 수정 UI·PUT·연결 현장 캐시 갱신 프론트 회귀 PASS, 공개 VPS 저장·새로고침 재조회 PASS. 삭제 API 없음, ADR-021 보관/복원 구현 PENDING |
| 견적 | 초안 생성·수정·삭제, 확정, 목록·상세·손익 반영 | `[QA]` 2,000원 초안 생성→3,000원 수정·재조회, 초안 손익 제외, 확정 후 손익 3,000원 반영 PASS. 초안 삭제·파일 파싱 PENDING; 확정본 수정/삭제는 정책상 차단 |
| 매입 | 생성·수정·삭제, 손익 재집계 | `[QA]` 600원 생성→800원 수정·재조회, 손익 2,200원 반영 PASS. 삭제 PENDING |
| 세금계산서 | 생성·수정·삭제, 입금 확인, 미수금 집계 | `[QA]` 매출 1,100원 생성→1,320원 수정·재조회→입금 확인 후 미수금 0원 PASS. 삭제·매입 세금계산서·분할 입금 PENDING/미지원 구분 필요 |
| 보증보험 | 생성·수정·삭제, PDF 업로드·OCR 상태/수동 보정 | `[QA]` 수동 생성·보증금액 수정 1,000→1,200원·현장 상세 재조회 PASS. 2026-10-13 합성 PDF 입력 거부·트랜잭션/상태 저장 로컬 회귀 PASS, 보증보험 서비스만 공개 VPS의 검증된 `main` SHA로 배포하고 HTTPS/health/무인증 401 PASS. 실제 PDF/OCR·삭제·만료 알림은 PENDING |
| 알림·대시보드 | 업무 이벤트 알림, 현장별/전체 손익, AI 요약 | `[QA]` 견적·매입·세금계산서 이벤트 수신과 현장별 3,000/800/2,200원, 전체 대시보드 반영 PASS. 알림 읽음 조작은 기존 항목 영향 때문에 미실행. AI 요약 모델 미기동 |
| AI 채팅·파싱 | 모델 준비 상태, 질문·답변, 파싱/요약 | VPS 모델 미기동으로 답변·파싱 불가. 가용성 표시·전송 차단·무인증 401은 공개 실측 PASS, 무저장 거절은 단위 테스트 PASS, 로컬 실제 Ollama 답변·스트리밍 PASS(2026-10-28), VPS 실제 답변 PENDING |

2026-10-22 로컬 풀 Docker 실동선(`develop` `a75bb9f`, 서비스 포트 직접 호출 — Gateway·화면 경유 아님, 합성 `[QA]` 자료): 거래처·현장 생성, 현장명·견적 제목 201자 400, 견적 초안 생성→수정→**삭제→재조회 404**, 확정, 매입 2건 생성 후 손익(견적 5,000/매입 1,100/마진 3,900)→**매입 1건 삭제 후 재집계**(매입 800/마진 4,200), 매출 세금계산서 생성→**미입금 삭제**, 입금 확인→입금 확인 건 삭제 409, 새 알림 `createdAt`이 현재 KST와 1분 이내, 현장·거래처 `created_at`과 `archived_at` 원시값이 같은 KST 기준(차이 1분 이내) 18/18 PASS. 정리: 현장 15·거래처 2 보관. 공개 VPS·Gateway 인증 경로는 별도 PENDING.

2026-10-19 로컬 대시보드 회귀: AI 요약 요청 실패를 정상적인 빈 요약과 구분해 오류·재시도 버튼을 표시한다. 캐시된 오래된 요약이 있어도 최신 요청 실패 시 이를 현재 결과처럼 노출하지 않는다. 실제 Ollama 요약 성공과 공개 VPS 화면 확인은 여전히 PENDING이다.

2026-10-19 로컬 현장 상세 회귀: 견적·매입·세금계산서·보증보험 조회 실패를 각 탭의 `0건`·빈 목록과 구분해 `조회 실패` 및 재시도를 표시한다. 이전 캐시의 문서도 실패 시 현재 조회 결과처럼 노출하지 않는다. 공개 VPS에서의 실제 오류·복구 화면은 PENDING이다.

2026-10-19 로컬 알림 회귀: 미읽음 건수 조회 실패 때 이전 캐시 숫자를 페이지·헤더 배지에서 숨기고 실패 안내를 표시한다. 페이지에서는 재시도하고 `전체 읽음`을 비활성화하며, 조회 성공 시에만 허용한다. 공개 VPS 화면과 실제 API 장애·복구는 PENDING이다.

파싱 업로드 UI가 안내하던 `.xls`는 백엔드에서 원래 거부하던 형식이다. 지원 표기를 `.xlsx`로 맞추고, 파일명·파싱 예외·AI 응답 원문이 서버 로그로 흘러가지 않도록 회귀 테스트를 추가했다. PR #88 배포 후 로그인된 공개 화면에서 `.xlsx` 안내를 확인했지만, 실제 VPS의 파일 파싱 성공이나 모델 가동을 뜻하지 않는다.

2026-10-08 로그인된 공개 사이트를 새로고침한 뒤 견적 버튼 `공내역서 AI 파싱`과 알림의 `견적/매입/세금계산서/입금/보증` 사용자용 분류를 읽기 전용으로 확인했다. 기존 SPA 탭은 재로드 전까지 이전 번들을 표시할 수 있다.

### 2026-10-17 생성 경로 현장 참조 — 로컬 풀 Docker 실측

`develop` `7652cea` 이미지로 site·purchase·estimate·tax·notification을 순차 재빌드하고 기존 `build-flow_*` 볼륨을 유지한 채 Eureka→Config→나머지 순으로 기동했다. 각 서비스의 loopback 포트로 직접 호출해 실제 Feign/Eureka 경로를 확인했다. Gateway/공개 VPS 실측이 아니다.

| 경로 | 없는 현장 | 존재하는 `[QA]` 현장 | site-service 중지 |
|---|---|---|---|
| 견적 생성 | 400 | 201 (DRAFT, 이후 삭제) | 503 (첫 호출 약 20초) |
| 매입 생성 | 400 | 미실행(손익 이벤트 회피) | 503 (약 4초) |
| 세금계산서 생성 | 400 | 미실행(알림 이벤트 회피) | 503 (약 4초) |
| 보증 직접 등록 | 400 | 201 (이후 삭제) | 503 (약 4초) |
| 보증 PDF 업로드 | 400 | 미실행 | 503 (즉시) |

거부된 요청 뒤 매입·세금계산서 0행, 업로드 볼륨 파일 0개를 확인했다. `[QA]` 현장 1건은 현장 삭제가 설계상 409라 로컬 DB에 남는다. Windows Git Bash에서 한글 JSON을 인자로 보내면 cp949로 전송되어 500이 나므로 UTF-8 본문 파일로 실측했다.

후속 수정 후 재실측: 현장 참조 Feign 클라이언트에 연결 2초·읽기 3초를 명시해 site-service 중지 시 네 생성 경로의 503이 두 차례 모두 약 4초로 제한됐다(이전 첫 호출 약 20초). 깨진 UTF-8 JSON은 현장·견적·보증 서비스에서 500 대신 400이며 로그에는 본문 없이 예외 종류만 남는다.

### 2026-10-17 현장 보관·복원 — 로컬 풀 Docker 실측

기존 `build-flow_mysql_data`에서 site-service가 `archived_at`(nullable datetime)을 추가했고 기존 `[QA]` 현장 1행은 NULL(미보관)로 유지됐다. 그 현장을 보관하자 재보관은 최초 시각 유지, 수정 409, 견적·매입·세금계산서·보증 직접 등록·PDF 업로드는 모두 400("보관된 현장에는 새 자료를 등록할 수 없습니다"), 손익 조회 200, `archived=true/false` 목록 1/0건이었다. 복원 후 견적 201(즉시 삭제) 확인 뒤 다시 보관해 정리했다. 로그인 화면의 버튼 조작은 관리자 자격 증명이 필요해 Vitest·빌드로만 확인했다.

2차(기존 문서 잠금) 실측: 새 `[QA]` 현장에 견적 초안·보증을 만든 뒤 보관하자 견적 수정·확정·삭제와 보증 수정·삭제가 모두 409였고, 복원 후 삭제 200으로 정리한 뒤 다시 보관했다. 매출 입금 확인 허용은 Vitest(MSW)·단위 테스트로 확인했다(로컬 알림 이벤트 생성을 피하려 Docker에서는 세금계산서를 만들지 않음). 기동 직후 Eureka 등록 전에는 생성이 503으로 실패-닫힘 처리됐다.

2026-10-28 현장 보관 ↔ 다른 서비스 생성 경합: 저장 전 현장 검증을 통과한 직후 site-service에서 현장이 보관되면 서로 다른 DB라 행 잠금으로 직렬화할 수 없다. **에이전트 결정(사용자 위임)**으로 견적 생성(수정본·추가공사 포함)·매입 생성·세금계산서 생성·보증 직접 등록·PDF 업로드에서 문서와 outbox(보증은 감사 이력)를 저장·flush한 뒤 커밋 직전에 현장을 한 번 더 확인하고, 보관 400·없는 현장 400·확인 장애 503이면 트랜잭션 전체를 롤백한다(fail-closed). PDF 업로드는 PENDING 행·업로드 이력·재확인을 한 트랜잭션으로 묶고 실패 시 이미 쓴 파일을 지우며, OCR 예약은 커밋 후 그대로다. 분산 잠금(Redis)은 외부 의존성 추가라 보류했다. 재확인과 커밋 사이 수 ms 구간은 단일 관리자 저동시성 환경에서 허용하고, 발생하면 현장을 복원해 정정한다. 2026-10-29 [격리 CI](https://github.com/hhm0215/build-flow/actions/runs/40872587199): 매입 생성의 첫 Feign 확인 후 MySQL INSERT가 잠금을 기다리는 것을 확인하고 현장 보관 → 잠금 해제 → 두 번째 Feign 확인에서 400, 매입과 outbox 0건 PASS. 활성 현장 생성·outbox 1건도 PASS. 견적·세금계산서·보증과 수정·삭제·확정의 실제 동시 경합은 PENDING이며, 이는 재확인 이후 커밋 전 구간의 원자성을 증명하지 않는다.

2026-10-28 변경·확정 경로 확장: 독립 리뷰가 기존 문서 변경 경로도 첫 보관 검사 직후 보관되면 보관 현장 문서가 수정·삭제되거나 견적 확정으로 `estimate.parsed`/`estimate.deleted` 손익 이벤트가 쌓일 수 있다고 지적했다. **에이전트 결정(사용자 위임)**으로 견적 수정·삭제·확정(기존 확정본 대체 포함), 매입 수정·삭제, 세금계산서 수정·삭제·입금 취소, 매입 증빙 연결·해제, 보증 수동 보정·삭제에서 변경·outbox(보증은 감사 이력·원본 보존 기록)를 flush한 뒤 커밋 직전에 보관 여부를 다시 확인하고, 보관 409(`SITE_ARCHIVED_DOCUMENT_LOCKED`)·확인 장애 503이면 전체를 롤백한다. 현장 404(고아 자료) 허용은 첫 검사와 같다. 보관 현장에서도 허용하는 입금 기록(`record`·`payRemaining`, 이를 위임하는 `confirmPayment`)만 기존 결정대로 제외했고, 보관 현장을 409로 막는 입금 취소와 증빙 연결·해제는 같은 재확인을 적용했다. 보증 원본 PDF는 삭제 시 지우지 않고 보존 기록만 남기므로 롤백돼도 파일은 그대로다. 단위·H2 롤백 회귀만 PASS, 실제 MySQL 동시 경합 실측은 PENDING.

변경 경로는 행 잠금을 쥔 채 site-service Feign을 2회 호출하므로 최대 약 10초(connect 2s + read 3s, 2회) 잠금이 유지될 수 있다. 단일 관리자 저동시성 환경에서 허용한다.

### 2026-10-17 삭제 동선·합성 스캔 PDF OCR — 로컬 풀 Docker 실측

- **삭제 동선**: `[QA]` 현장에 확정 견적 3,000원·매입 800원·매출 세금계산서 1,100원 생성 → 손익(견적 3,000/매입 800/마진 2,200/73.33%)·미수금 1,100원 반영 확인 → 매입·세금계산서 삭제 200 → Kafka 재집계로 매입 0·마진 3,000·미수금 0, 확정 견적 삭제는 409.
- **OCR(합성 스캔 PDF, 한글 이미지만 있고 텍스트 층 없음)**: VPS와 같은 notification 제한(640 MiB, `-Xmx320m`)에서 1페이지 약 2~6초·10페이지(허용 최대) 약 20~24초에 `SUCCESS`, 보험사·보험기간 필수 3필드 정확. 증권번호는 Tesseract가 한글 라벨 "증권번호"를 오인식해 빠짐(선택 필드, 사용자 확인 단계에서 보정) — 라벨 없이 번호를 추측하는 규칙은 오인식 위험이 커 도입하지 않음.
- **메모리**: 10페이지 OCR에서 640 MiB 한도에 상시 접촉(cgroup `memory.max` 이벤트 수백 회, 파일 캐시 아님·anon 약 626 MiB). 로컬은 스왑으로 OOM 없이 통과했으나 VPS 보장 없음. 흑백 렌더링·`OMP_THREAD_LIMIT=1`·`MALLOC_ARENA_MAX=2`로 1페이지 최대 584→458 MiB로 줄었고, 1 GiB 한도에서 10페이지 반복 시 자연 최대 약 707 MiB라 VPS 한도를 896m로 상향(배포 시 적용, 미배포). 합성 자료는 삭제·현장 보관으로 정리, 업로드 파일 0개 확인.

### 2026-10-17 합성 혼합 PDF(텍스트 층 + 스캔 이미지) — 로컬 Docker 실측

PDFBox로 텍스트 층(한글 폰트 내장)과 텍스트 없는 이미지가 섞인 합성 PDF를 만들어 VPS 동일 제한(896m, `-Xmx320m`)에서 업로드했다.

| 합성 파일 | 수정 전 | 수정 후 |
|---|---|---|
| 텍스트 층 보험사·증권번호 + 이미지 보험기간 | 4필드 정확하지만 **FAILED** | SUCCESS |
| 텍스트 층 보험사 + 이미지 보험기간 | SUCCESS | SUCCESS |
| 텍스트 층 서울보증보험 + 이미지 삼성화재·다른 기간 | FAILED(실은 증권번호 오인식 때문) → 증권번호 수정만 하면 SUCCESS로 충돌이 가려짐 | **FAILED**(수동 확인), 값 보존 |

- 원인 1: 페이지 전체 OCR이 텍스트 층의 증권번호 `QA-2026-2001`을 `04-2026-2001`로 다시 읽어 같은 번호를 충돌로 판정했다(컨테이너 안 Tess4J 진단으로 확인). 증권번호 비교에 OCR 혼동 글자(O·Q·D→0, I·L→1, Z→2, A→4, S→5, G→6, B→8)와 구분자 정규화를 적용했다.
- 원인 2: 전체 OCR이 텍스트 층과 이미지의 보험사를 함께 읽고 목록 순서상 첫 보험사만 골라 충돌이 가려졌다. 한 문서에서 서로 다른 알려진 보험사가 둘 이상 읽히면 모호(충돌)로 표시해 수동 확인으로 넘긴다.
- 합성 PDF 생성 시 Windows 기본 인코딩으로 소스를 읽어 한글이 깨졌던 시험 도구 실수는 UTF-8 지정으로 바로잡았다(제품 결함 아님). 합성 보증 20건 삭제, 현장 보관, 업로드 파일 0개 확인.

### 2026-10-17 OCR 별도 프로세스 전환·강제 타임아웃 — 로컬 Docker 실측

Tesseract를 JVM 안(Tess4J/JNA)에서 부르던 방식을 페이지별 별도 프로세스(CLI)로 바꿨다. 멈춘 OCR을 끊을 수 없던 문제(단일 작업자 영구 점유)를 페이지 시간 제한(기본 60초)·강제 종료로 막는다.

- CLI 기본 페이지 분할(PSM 3)은 글이 빽빽한 10페이지 합성 스캔에서 해상도를 잘못 추정해 필드를 하나도 읽지 못했고(`--dpi` 70/150/300 모두 실패), `--dpi 300`은 1페이지의 "보험회사" 줄도 놓쳤다. `--psm 6`(단일 글 블록)에서 1·10페이지 모두 읽혀 이를 채택했다.
- 결과(VPS 동일 896m·`-Xmx320m`): 스캔 1페이지 SUCCESS 2초, 10페이지 SUCCESS 20~22초, 혼합 보완형·증권번호 없음 SUCCESS, 혼합 충돌형 FAILED(값 보존). 최대 anon 742 MiB(Tesseract 프로세스 포함), `memory.max` 접촉 0회. 대기 중 약 650 MiB는 JVM 자체 사용량이라 JNA 제거만으로 줄지는 않았다.
- 강제 타임아웃: 페이지 제한을 1초로 낮춰 10페이지를 올리자 2초 만에 프로세스 종료·`FAILED`, 남은 Tesseract 프로세스·임시 파일 0개. 정상 설정으로 복원했다. 합성 보증 19건 삭제·현장 보관·업로드 파일 0개.

### 2026-10-17 보증 확인 감사 이력 — 로컬 Docker 실측

`warranty_audit_events`가 자동 생성됐고, 합성 보증의 직접 등록→수동 수정→삭제가 `X-User-Id`(`metal`)로, PDF 업로드는 `metal`·OCR 반영은 `system`·헤더 없는 삭제는 `unknown`으로 시간순 기록됐다. 삭제 후에도 `GET /api/v1/warranties/{id}/history`로 조회된다. 공유 관리자 계정이라 기록자는 계정 단위이며 사람을 증명하지 않는다. Gateway 경유 실제 토큰 경로와 보존 기간·폐기 정책은 PENDING.

2026-10-21 보증 원본 보존(로컬 풀 Docker, 합성 PDF): 보증을 삭제해도 원본 PDF는 업로드 폴더에 그대로 남고 `warranty_file_retentions`에 보존 기록(삭제 시각·폐기 예정일=KST 오늘+365일)이 생긴다. 기한이 지난 행만 매일 03:30 KST에 폐기하며, 앱이 만든 UUID 파일명이 아니거나 업로드 폴더를 벗어나는 항목은 지우지 않는다(JPA 회귀 4건). 보존 기간 365일은 에이전트 결정(추천안, 사용자 위임) — 대안: 즉시 삭제(복구 불가) 또는 무기한 보존(디스크 누적). 기록 시각 원시값이 `created_at`보다 +9h로 보이는 시간대 불일치는 BACKLOG P1에 별도 등록. VPS 미반영.

### 2026-10-19 현장 관계 조회 실패 표시 — 프론트 합성 회귀

- 현장 목록은 견적·매입·세금계산서 목록을 각각 조회해 합계·마진을 만든다. 하나가 실패해도 기존 화면은 `data ?? []`를 0건·0원으로 표시해 실제 금액 누락처럼 보였다.
- 각 조회 실패를 해당 목록의 오류·재시도로 구분하고, 실패하거나 로딩 중인 합계·마진은 금액으로 확정 표시하지 않는다. 세금계산서 실패·로딩 때 미수금도 표시하지 않는다.
- `SiteListPage.test.tsx`의 정상 빈 목록·3개 조회 실패·매입만 실패·로딩·양수 금액·캐시 재조회 실패·데이터 부재 7개 회귀와 프론트 전체 테스트(42파일/151건), 빌드/lint는 로컬 PASS. 실제 VPS API 장애와 공개 화면, `main`/VPS 반영은 PENDING. 기존 운영 금액 자체를 수정하지 않는다.

### 2026-10-19 대시보드 현장별 손익 이동 — 프론트 합성 회귀

- 현장별 손익 카드의 `전체 보기` 버튼은 클릭 동작이 없어 업무 동선이 끊겼다. 클릭 시 `/sites`로 이동하게 하고 라우터 회귀 1건을 추가했다. 공개 VPS 화면과 `main`/VPS 반영은 PENDING.

### 2026-10-28 주간 기능 Gateway 경유 실측 스크립트 — 2026-10-29 실측 49/49 PASS

`scripts/qa/local_e2e.py`는 이번 주 기능 5개를 로컬 풀 Docker에서 **Gateway(`127.0.0.1:8080`) 경유 JWT 인증**으로 실측한다. 작성 시점 이 PC의 Docker 엔진이 꺼져 있어 스크립트는 실행하지 않았다. 단위 테스트(호스트 검사·기대값 계산·HTTP 스텁 응답 파싱·자격 처리)만 PASS다.

실행 방법:

1. Docker Desktop 복구 후 `docker compose -f docker-compose.yml -f docker-compose.app.yml up -d` (기존 볼륨 유지, 초기화 금지). 전체 병렬 빌드가 네트워크 오류로 실패하면 변경 서비스만 순차 재빌드한다.
2. 스택을 검증 대상 커밋으로 최신화: 이번 주 기능이 포함된 `develop`을 checkout한 작업 트리에서 estimate·site·purchase·tax·notification·gateway·auth 이미지를 재빌드하고 Gateway health 200을 확인한다.
3. 저장소 루트에서 `python -I -X utf8 scripts/qa/local_e2e.py` (기본 `--base-url http://127.0.0.1:8080`). 종료 코드 0=전부 PASS, 1=FAIL 있음, 2=대상·로그인 준비 실패. 출력 표를 이 문서에 날짜·SHA와 함께 기록한다.

시나리오(합성 `[QA-주간-<타임스탬프>]` 접두사 데이터를 만들고 지우지 않는다. 정책 검증용 삭제만 수행):

| 시나리오 | 검사 |
|---|---|
| S0 Gateway 인증 | 무인증 현장 목록·재계산 401, 인증 후 200 |
| S1 분할 입금(#193) | 매출 1,100,000 계산서 UNPAID → 300,000·500,000 입금 PARTIAL → 초과 입금 400 → 1차 취소 후 잔액 600,000 → 중복 취소 409 → 미수금 집계 → 유효 입금 있는 계산서 삭제 409 → 잔액 입금 확인 PAID·미수금 0 → 내역 3건·취소 1건 보존 |
| S2 수주 전(#192) | BIDDING 생성 → 500,000 견적 확정·손익 수렴 → 대시보드 전체 합계 = 현장별 목록 중 BIDDING 제외 합계(우리 현장은 목록에 표시) → IN_PROGRESS 전환 200 후 합계 포함 → BIDDING 되돌리기 400 |
| S3 매입 증빙(#196) | 매입 100,000/30,000/20,000, 매입 계산서 130,000+13,000 → 매출·다른 현장 계산서 연결 400 → 2건 연결 → 대조 요약(연결 130,000·미연결 20,000·공급가 차이 0·합계 차이 13,000) → 연결 계산서 삭제 409 → 해제 후 삭제 200·요약 갱신 |
| S4 견적 수정본(#201) | 원 견적 1,000,000 확정·매입 200,000 → 수정본 1,200,000 확정 시 원본 SUPERSEDED → 추가공사 300,000 새 체인 → 손익 1,500,000/200,000/86.67% 수렴(최대 30초) → 대체본 수정·확정·삭제 409 → 초안을 원 견적으로 연결 400 |
| S5 손익 재계산(#203/#205) | S4 현장 dry-run `matched`·건수 0·원천=현재=기대 손익 → apply=true 무변경(`applied=false`)·손익 불변 → 없는 현장 404 |

**에이전트 결정(사용자 위임, 2026-10-28)**: 로컬 테스트 관리자는 실행 환경마다 `secrets` 모듈 무작위 비밀번호로 만들어 auth-service의 기존 `AdminBootstrapRunner`(비웹 실행, 표준입력 JSON 1줄 `loginId/name/password`)를 `docker exec -i buildflow-auth java -jar /app/app.jar --spring.main.web-application-type=none --eureka.client.enabled=false --app.admin-bootstrap.enabled=true`로 호출해 등록한다. 값은 `.gitignore`된 `.local/qa-admin.env`(생성 시 0600 요청, Windows에서는 사용자 프로필 ACL에 의존)에만 저장하고 다음 실행에서 재사용한다. 부트스트랩 출력은 버리고 비밀번호·토큰은 출력하지 않는다. 대상 URL은 `127.0.0.1`/`localhost`/`::1` 원점만 허용하고(경로·쿼리·사용자 정보 거부), 프록시·리다이렉트를 따르지 않아 VPS 오용을 막는다. 대안(기록만): 서비스 포트 직접 호출 + `X-User-Id`(2026-10-22 방식) — Gateway 인증·라우팅을 검증하지 못해 보조로만 쓴다.

제약: `AdminBootstrapService`는 사용자 행이 하나라도 있으면 `ADMIN_ALREADY_EXISTS`로 거부한다. 가족 관리자 계정이 이미 있는 로컬 스택에서는 부트스트랩이 실패하고, 대화형 터미널이면 기존 로컬 관리자 값을 입력받아 저장하지 않고 사용한다(`--no-bootstrap`으로 바로 입력). 계정 추가·초기화를 위해 DB를 조작하지 않는다.

### 2026-10-28 실제 Ollama AI 채팅 — 로컬 풀 Docker 실측

스택 `develop` `ed398c4`, 네이티브 Ollama(`qwen2.5:7b` 등 4개 모델), chat-service 직접 호출(`127.0.0.1:8087`, `X-User-Id`)로 합성 `[QA]` 데이터만 질문했다. Gateway 경유 로그인 실측은 위 스크립트 결과로 따로 기록한다.

| 질문 | 이벤트 | 첫 조각/전체 | 판정 |
|---|---|---|---|
| 현장 목록 알려줘 | thinking→tool→answering, token 205 | 2.1초 / 6.8초 | PASS — 실제 15개 현장명과 일치, 조각이 도착 즉시 전달됨 |
| 전체 현장 손익 요약해줘 | thinking→tool→thinking→answering, token 14 | 44.2초 / 44.2초 | PASS(숫자) — 총 마진 7,200원·90%·저마진 현장 목록이 site-service AI 요약 원본과 일치. 요약 도구 안에서 site-service가 LLM을 한 번 더 호출해 느리고, 도구 뒤 답변은 비스트리밍 라운드라 한 번에 도착 |
| 안녕하세요 | thinking→answering, token 3 | 1.0초 / 1.1초 | PASS — 도구 없이 답변 |

관찰: 7b 토큰 잡음(`마argin율`)이 다시 재현됐다(기존 기록과 같음). 같은 실측 중 깨진 UTF-8·잘린 JSON 본문에 auth-service(로그인)와 chat-service만 400 대신 500을 반환하고 스택 전체를 로그에 남기는 결함을 찾아, 다른 5개 서비스와 같은 `HttpMessageNotReadableException` → 400 처리기와 회귀 테스트를 추가했다. 공개 VPS는 모델 미기동이라 실제 답변은 여전히 PENDING이다.

후속(같은 날, 로컬 재실측): 요약 도구를 결정적 손익 통계(`/dashboard/stats`)로 바꾸자(PR #211) 요약 질문이 44.2초→6~13초가 됐다. 대신 7b 모델이 원 단위 숫자 `8000.00`을 "8,000만 원"·"8,000,000원"으로 부풀렸고 프롬프트 규칙만으로는 고쳐지지 않았다. 또 "[QA] 1022-144647 현장"처럼 기호·숫자가 섞인 이름은 siteId를 추측(404)하거나 목록에서 못 찾았다. 수리: 도구 결과의 `*Amount`·`margin`·`totalMargin`·`unitPrice`를 "8,000원", `*Rate`를 "84.00%" 문자열로 미리 만들고, 질문에 이름이 든 현장의 siteId를 규칙 매칭으로 시스템 메시지에 넣고, 404는 listSites로 다시 찾으라는 안내로 돌려준다. 재실측 5문항(전체 요약·현장 2곳·인사) 금액·비율이 원본과 모두 일치(PASS), 요약 6.2초·현장 질문 2~4초. 남은 관찰: 다국어 토큰 잡음(`ước정`, `마argin율`)은 숫자에 영향 없음 — 모델 교체 검토 후보.

### 2026-10-31 복원 DB 관리자 로그인 — 합성 Docker 로컬 실측 PASS

`scripts/db/test-v2-snapshot-docker.ps1`가 합성 원본 MySQL에 빈 `admin_accounts`만 만든 뒤, 실행 중 무작위로 만든 로그인 ID(`synth-` + 16 hex)·비밀번호(48 hex)를 일회성 비웹 auth-service(`AdminBootstrapRunner`, 실제 `BCryptPasswordEncoder`)에 표준입력 JSON 1줄로 넘겨 관리자 1행을 만든다(저장값이 BCrypt 형식 1행인지 SQL로만 확인). 이어 기존 `backup.ps1` → `restore-test.ps1 -TestIncompleteV2 -SyntheticAdminFromEnvironment` 흐름으로 격리 임시 volume에 복원하고, 복원 DB를 가리키는 격리 auth-service(+Redis, `ddl-auto=validate`)에서 같은 계정에 다른 무작위 비밀번호 → HTTP 401, 원래 자격 증명 → `accessToken` 발급 + 로그아웃 성공을 단언한 뒤 manifest·digest 불변을 재확인한다. 자격 증명은 프로세스 환경변수와 표준입력으로만 다루고 출력·커밋하지 않으며 종료 시 이전 값으로 복원한다(부트스트랩 실패 시 진단 출력도 비밀값을 `[REDACTED]`로 치환). Windows PowerShell 5.1은 stdin 첫머리에 UTF-8 BOM을 붙여 부트스트랩 JSON 파싱이 실패했으므로 시작 직전에만 BOM 없는 입력 인코딩을 쓴다. 로컬 실측(Windows PowerShell 5.1, 로컬 빌드 이미지 `BUILDFLOW_TEST_AUTH_IMAGE`로 기존 `build-flow-auth-service` 태그 미변경): `[OK] Restored administrator rejected a wrong password with HTTP 401.` / `[OK] Restored administrator login and logout passed.` / 최종 `[OK] Synthetic Docker source snapshot and isolated DB+file restore with backed-up administrator login passed; real source and external recovery remain UNVERIFIED.` 임시 project·volume·network 모두 제거, 기존 `build-flow_*` volume 4개 그대로. PR #253 CI `DB+PDF 복구 (합성 Docker)` job에서도 PASS(관리자 생성·틀린 비밀번호 401·로그인/로그아웃 같은 줄). 한계: 합성 계정이며 실제 운영 백업 속 기존 관리자 로그인은 미검증.

### 2026-10-31 공개 VPS BuildFlow 컨테이너 0개 — 자가 점검 발견(장애 아님)

`self_audit.py --vps` 결과 HEAD `4c4ca8c`는 기록과 같았지만 `buildflow-release status`에 실행 중 컨테이너가 하나도 없고 공개 `/`·무인증 API가 000이었다(로컬에서 직접 접속해도 000). 호스트 가동 14일·Docker `active`라 재부팅은 아니다. 2026-10-29 배포 직후에는 200/401이었으므로 그 뒤에 멈췄다. 기존 점검은 컨테이너 행이 없으면 "모두 Up"으로 PASS 처리하던 결함이 있어, 0개면 FAIL로 바꾸고 회귀 테스트를 추가했다. 정정(같은 날): 사용자가 VPS를 다른 프로젝트에 쓰고 있어 BuildFlow 컨테이너를 내린 상태였다. 장애가 아니며, 사용자 지시로 에이전트는 VPS에 접속·조회·배포하지 않는다. 공개 VPS 실측·배포 게이트는 사용자가 VPS를 다시 BuildFlow용으로 돌릴 때까지 보류한다.

### 2026-10-29 문서 서비스 Feign 재시도 비활성화 — 로컬 실측

`spring-retry`가 런타임 경로에 있어 Spring Cloud LoadBalancer 기본값이면 Feign GET이 실패 시 한 번 더 시도된다. 현장 확인은 쓰기 트랜잭션 안(행 잠금 보유 중)에서 호출되므로 에이전트 결정(위임)으로 estimate·purchase·tax·notification만 `spring.cloud.loadbalancer.retry.enabled=false`로 끄고 실패는 503으로 바로 돌려준다(chat은 트랜잭션 밖 조회라 유지). site-service를 일시정지한 상태에서 매입 생성 503 응답 시간: 기존 6.0초(read-timeout 3초×2) → 변경 후 3.1초(1회).

### 2026-10-29 채팅 모델 비교 — qwen2.5:7b ↔ qwen3:8b (로컬 실측)

develop `38c400d` chat-service, 같은 합성 `[QA]` 데이터·5문항(전체 요약·현장 2곳·목록·인사), 모델만 임시 compose override(`OLLAMA_MODEL`)로 바꿔 측정했다. 측정 후 기본값(qwen2.5:7b)으로 되돌렸다.

| 모델 | 금액·현장 정확도 | 외국어 토큰 잡음 | 응답 시간 |
|---|---|---|---|
| qwen2.5:7b | 5/5 일치(#212 전처리 후) | 반복 재현(`마argin율`, `ước정`) | 1~13초(현장 질문 2~8초) |
| qwen3:8b | 5/5 일치 | 없음 | 4~30초(현장 질문 12~15초, 전체 요약 30초) |

판단: 숫자 정확도는 같고 qwen3:8b는 문장 품질이 낫지만 2~3배 느리다. 기본 모델 변경은 속도·품질 교환이라 보류하고, VPS 모델 기동 시점에 하드웨어 속도와 함께 다시 고른다. 관찰: qwen3는 0% 마진 현장을 "매입과 견적이 일치"로 잘못 풀이한 문장이 1회 있었다(숫자는 정확).

### 2026-10-29 주간 기능 Gateway 경유 실측 결과 — 49/49 PASS

develop `e649ad2` 이미지, 로컬 풀 Docker. 기존 스택에 가족 관리자가 있어 부트스트랩이 막히므로(계정 추가·초기화 목적의 DB 조작 금지), 기존 스택을 볼륨 보존 상태로 내리고(`down`, `-v` 없음) **별도 Compose 프로젝트 `bfqa`(빈 볼륨 `bfqa_*`)**를 같은 이미지로 띄워 `AdminBootstrapRunner`로 무작위 QA 관리자를 만든 뒤 `python -I -X utf8 scripts/qa/local_e2e.py`를 실행했다. 실측 후 `bfqa` 컨테이너·볼륨 4개만 이름 지정 삭제하고 기존 스택을 복구했다(현장 17건·관리자 1명 그대로, QA 자격 파일 삭제).

| 시나리오 | 결과 |
|---|---|
| S0 Gateway 인증(무인증 401 2건·인증 200) | 3/3 PASS |
| S1 분할 입금(#193) | 12/12 PASS |
| S2 수주 전 현장(#192) | 6/6 PASS |
| S3 매입 증빙(#196) | 10/10 PASS |
| S4 견적 수정본(#201) | 11/11 PASS |
| S5 손익 재계산(#203/#205) | 7/7 PASS |

이는 로컬 Gateway·JWT 경유 실측이며 공개 VPS 동선 실측이나 실데이터 승인이 아니다. 재실행은 같은 방식(별도 프로젝트·빈 볼륨)으로 사용자 입력 없이 가능하다.

### 2026-11-01 보증 합성 PDF 업로드→OCR Gateway 경유 실측 — 로컬 27/27 PASS

develop `a3b35ee`를 기준으로 한 브랜치 `claude/warranty-ocr-local`에서 `python -I -X utf8 scripts/qa/warranty_ocr_e2e.py`를 실행했다. 스크립트가 별도 Compose 프로젝트 `bfqaocr`를 만든다. 이 프로젝트는 빈 볼륨 `bfqaocr_*` 4개를 쓰고, 고정 container_name과 공용 네트워크를 쓰지 않으며, Gateway만 `127.0.0.1:18080`에 공개한다. 띄우는 서비스는 mysql·redis·kafka·eureka·gateway·auth·site·notification이고, 이미지는 이 작업 트리에서 `bfqaocr-*:local`로 순차 빌드했다. notification은 VPS와 같게 896m·`-Xmx320m`으로 제한했다. QA 관리자는 `AdminBootstrapRunner`로 부트스트랩하고, 비밀번호·DB 비밀번호·JWT 비밀은 실행할 때마다 메모리에서만 무작위로 만든다(출력·저장 없음). 기존 `build-flow` 프로젝트는 꺼진 상태 그대로 두었고 볼륨 4개도 건드리지 않았다.

합성 PDF는 실행 임시 디렉터리에서만 만들었다(커밋·외부 다운로드 없음). 생성기 `scripts/qa/WarrantyOcrPdfGen.java`는 notification 이미지에 들어 있는 PDFBox 3 jar, 로컬 `eclipse-temurin:17-jdk-jammy`(`--network none`), 호스트 맑은 고딕 `.ttf`로 돌린다. 생성한 PDF 두 종류는 아래와 같다. 텍스트 층 글자 수는 앱과 같은 PDFBox 추출로 다시 세어 단언했다.

| 합성 PDF | 텍스트 층 | ocrStatus | 추출 필드 | 서버 OCR 기록(UPLOADED→OCR_APPLIED) / 폴링 관찰 |
|---|---|---|---|---|
| (a) 한글 폰트 내장 텍스트 1쪽, 12,122B | 117자 → PDFBox 경로 | SUCCESS | 서울보증보험·`QA-520951-01`·2025-11-16~2026-11-16 모두 정확 | 0.0초 / 0.8초 |
| (b) 300 DPI 흑백 래스터만 있는 1쪽, 36,729B | 0자 → Tesseract CLI 경로 | SUCCESS | 삼성화재·`2026-143441`·2025-11-21~2026-11-21 모두 정확(증권번호도 정확, 판정은 정규화 일치·미추출 허용) | 1.0초 / 1.1초 |

- 두 PDF에서 확인한 동선: 업로드 202 `PENDING`(원본 보유) → `SUCCESS`(`expiryConfirmed=false`) → MANUAL 확인 전 만료 목록(30일)에서 제외 → PUT 확인 저장 200 `MANUAL`·만료 확정 → 만료 목록 포함·남은 일수 일치 → 무인증 원본 다운로드 401 → 인증 다운로드 200, SHA-256·바이트 수가 업로드와 같음 → 감사 이력 `UPLOADED→OCR_APPLIED→MANUAL_UPDATED`. 무인증 보증 목록 401도 확인했다.
- 메모리(notification, 896 MiB 제한): 대기 391 MiB, OCR 중 `docker stats` 표본 최대 433 MiB, cgroup `memory.peak` 504 MiB(기동 포함), `memory.max` 접촉 0회, `oom_kill` 0. 이미지 빌드는 캐시 상태로 7초(첫 빌드 123초), 기동부터 라우팅 준비까지 78초.
- 실측 후 `bfqaocr` 컨테이너·네트워크는 `down`(`-v` 없음)으로 내리고, 볼륨 4개는 이름을 지정해 삭제했다(잔여 0). 기존 `build-flow_*` 볼륨 4개가 그대로 있는 것을 확인했다. `bfqaocr-*:local` 이미지 5개는 재실행(`--skip-build`)용으로 남겼다.
- 시험 도구 수정 2건(제품 결함 아님): Gateway는 config import 검사 때문에 config-server 없이 기동하지 못해 격리 정의에 `SPRING_CLOUD_CONFIG_ENABLED=false`를 넣었다. 준비 대기 중 종료된 컨테이너를 바로 보고하도록 했다. 운영 코드 변경은 없다.
- 한계: 깨끗한 합성 1쪽만 시험했다. 기울어짐·저해상도·실제 증권 서식의 OCR 정확도, 공개 VPS 업로드/OCR(현재 VPS 접속 금지), 실데이터는 이 결과로 통과 처리하지 않는다. 10쪽 최악 경로의 시간·메모리는 2026-10-17 기록을 따른다.

### 2026-11-01 화면 QA용 로컬 격리 전체 스택 — `scripts/qa/ui_stack.py`

사람(또는 에이전트 내장 브라우저)이 실제 화면을 눌러 보는 용도다. `python -I -X utf8 scripts/qa/ui_stack.py up [--skip-build] [--no-chat]`은 별도 Compose 프로젝트 `bfqaui`(빈 볼륨 `bfqaui_*` 4개, 고정 container_name·공용 네트워크 없음)로 mysql·redis·kafka·eureka·gateway·auth·estimate·site·purchase·tax·notification·chat과 frontend(Nginx 프로덕션 이미지)를 띄운다. config-server는 띄우지 않고 서비스마다 `SPRING_CLOUD_CONFIG_ENABLED=false`를 준다. 공개 포트는 루프백만이다: 화면 `http://127.0.0.1:13080`(Nginx가 `/api/`를 `gateway-server:8080`으로 프록시), Gateway `127.0.0.1:18081`(준비 확인·API 직접 호출). 이미지는 작업 트리에서 `bfqaui-*:local`로 순차 빌드한다. 사용자 `build-flow-*` 태그는 건드리지 않는다. DB 비밀번호·JWT 비밀은 실행 메모리에서만 무작위로 만든다. `AdminBootstrapRunner`로 만든 QA 관리자 자격 증명은 출력하지 않고, 저장소 무시 경로 `.local/qa-ui-admin.json`에만 저장한다(`output/`은 `.gitignore` 대상이 아니라서 쓰지 않는다). `up`은 frontend 200, frontend 경유 로그인 200, 서비스별 목록 API 200을 확인한 뒤 URL만 출력하고 스택을 켜 둔다. 합성 시드는 넣지 않는다. `up`을 다시 실행하면 이전 `bfqaui` 스택을 먼저 지우고 빈 상태로 새로 띄운다. `status`는 컨테이너 상태와 화면 응답을 보여 준다. `down`은 `bfqaui` 컨테이너·네트워크를 `down`(`-v` 없음)으로 내리고, 볼륨은 이름을 지정해 지운 뒤 자격 파일도 지운다. 기존 `build-flow` 프로젝트와 볼륨 4개는 조회만 한다. chat은 호스트 Ollama가 없으면 기동은 되지만 채팅을 쓸 수 없다고 표시된다. estimate의 AI 파싱도 Ollama가 있어야 동작한다(Claude API 키는 넘기지 않음).

### 2026-11-01 가족 일상 화면 동선 실측과 화면 수리 — 로컬 격리 스택 브라우저 실측, 수리는 프론트 회귀만

로컬 격리 전체 스택(브라우저 → Gateway·JWT)에서 현장 → 견적 → 매입 → 세금계산서 화면 동선을 합성 데이터로 실측했다. 저장·금액·손익은 모두 정확했다(견적 600,000 − 매입 273,000 = 마진 327,000, 마진율 54.5%, 미수금 660,000). 대신 가족이 매일 쓰기에 불편한 화면 결함이 나왔고, 브랜치 `claude/ui-family-fixes`에서 프론트엔드만 고쳤다(백엔드·API 계약·MSW 변경 없음).

| 실측 결함 | 수리 |
|---|---|
| 세금계산서 등록·수정에서 세액을 매번 손으로 계산 | 공급가액을 입력하면 세액이 비었거나 직접 고치지 않은 경우 공급가액의 10%를 **원 미만 절사**로 채운다(서버는 세액을 계산하지 않고 입력값을 소수 2자리로 저장하므로 화면 기본값 규칙). 직접 고친 세액은 덮어쓰지 않고, 세액을 지우면 다시 자동 채움. 수정 폼은 저장된 세액이 10%와 다르면 사용자가 정한 값으로 보고 유지. 등록 폼에서 세액을 지우면 0원이 되던 입력 변환도 고쳐 필수 검증이 0원으로 통과하지 않게 했다 |
| 매입·세금계산서 목록에 현장이 안 보임, 세금계산서 발행일 없음 | 두 목록에 '현장' 열(이미 쓰는 현장 목록 조회로 siteId→현장명, 보관 현장은 흐리게 + `보관` 표식), 세금계산서 목록에 '발행일' 열 추가 |
| 매입 목록 '거래처'와 폼 '공급업체' 용어 불일치 | 화면 문구를 '거래처'로 통일(DTO 필드 `supplier`는 그대로) |
| 매입일을 비워 두면 목록에 '-'로 남아 기간 집계 곤란 | 매입 등록 매입일 기본값 = 오늘(KST, `Asia/Seoul`). 서버 DTO가 선택값이라 지울 수는 있다 |
| 현장 0개 대시보드에 총 마진 '흑자'·평균 마진율 '주의' | 현장·금액이 없으면 평가 배지를 숨기고, 견적 합계가 0이면 평균 마진율을 '-'로 표시 |
| 현장 상세 탭에서 바로 등록할 수 없음 | 견적서·매입·세금계산서·보증보험 탭에 '추가' 버튼. 매입·세금계산서·보증은 등록 모달을 그 현장이 선택된 상태로 바로 연다(목록 화면과 같은 모달 컴포넌트 재사용). 견적서는 작성 모달이 공내역서 파싱·수정본 흐름과 묶여 있어 견적서 화면으로 이동해 그 현장이 선택된 작성 모달을 연다. 보관 현장은 버튼을 막고 "복원 후 등록" 안내. 보증 업로드의 현장 입력도 숫자 ID 대신 현장 검색 선택으로 바꿨다 |
| 사이드바 메뉴 접근성 | 메뉴 버튼에 `aria-label`·`aria-current="page"`, 메뉴 영역에 `aria-label` |

- 검증: 프론트 `bun run lint`(경고 0)·`bun run test`·`bun run build` 로컬 PASS. 세액 계산·덮어쓰기 규칙, KST 오늘, 현장명 매핑, 대시보드 빈 상태, 현장 상세 추가 버튼(보관 현장 비활성 포함)을 Vitest로 고정했다.
- 브라우저 재실측(같은 날, `bfqaui` 격리 스택의 프론트만 수리본 이미지로 교체, 데이터 유지): 현장 상세 탭별 추가 버튼 노출, 세금계산서 추가 시 현장 미리 선택·공급가액 123,457 → 세액 12,345 자동 채움, 매입 목록 현장 열·매입 등록 매입일 기본 2026-11-01, 세금계산서 목록 현장·발행일 열 PASS. 빈 대시보드 배지는 데이터가 있어 화면 미확인(컴포넌트 테스트로 고정). `main`·공개 VPS 미반영, 실데이터 미사용.

### 2026-11-01 30분 뒤 로그인 화면으로 튕김 — access 재발급 미구현 수리

- **현상**: 로그인 후 약 1시간 다른 일을 하다 `/taxes`로 이동하자 로그인 화면으로 튕겼다(브라우저 실측). auth-service 설정은 access 30분·refresh 7일이다.
- **원인(프론트)**: 백엔드 `/api/v1/auth/refresh`(본문 `{refreshToken}` → 회전 발급, Gateway 인증 필터 밖, auth `permitAll`, Nginx 로그인 rate limit은 `= /api/v1/auth/login`만 적용)는 정상인데 프론트가 쓰지 않았다. `LoginPage.tsx`가 로그인 응답의 `accessToken`만 저장하고 `refreshToken`을 버렸고(`authStore`에 필드 없음), `axiosInstance.ts` 응답 인터셉터는 보호 API 401이면 곧바로 `logout()` + `/login` 이동만 했다. 라우트 가드(`App.tsx` `PrivateRoute`)는 저장된 `isAuthenticated`만 보므로 재진입 자체는 통과하고, 만료된 access로 나간 첫 API 401에서 튕긴 것이다. `docs/STUDY_GUIDE.md`의 설계(refresh는 Redis + 클라이언트 보관, 401 시 자동 갱신, 실패 시 로그인)가 프론트에 구현되지 않은 상태였다.
- **수리(프론트만, 백엔드·DTO 변경 없음)**: 로그인 응답의 refresh 토큰을 access와 같은 저장소(`buildflow-auth`)에 보관. 보호 API 401 → refresh 1회 → 새 access로 원 요청 1회 재시도. 동시 401은 refresh 호출 하나를 공유(서버가 refresh를 회전하므로 병렬 호출은 두 번째가 거부돼 세션이 끊김). refresh가 400/401/403/404로 거부되면 로그인으로, 네트워크·5xx는 일시 장애로 보고 세션 유지. 재시도도 401이면 반복 없이 로그인으로. 다른 탭이 먼저 회전한 경우 저장소 새 토큰을 사용하고, `storage` 이벤트로 탭 간 토큰·로그아웃을 동기화. 만료 access로 로그아웃하면 재발급 후 서버 폐기를 재시도(기존 계약대로 폐기 미확인 시 토큰 보존·실패 안내). 채팅 스트림(fetch)도 같은 재발급 경로. MSW에 refresh 핸들러 추가.
- **함께**: 세금계산서 입금 관리 모달의 입금액 기본값이 `660000.00`으로 보이던 것을 `₩ 660,000`(₩·천 단위 쉼표·정수, 센트가 있으면 그대로)으로. `precision={2}` 대신 반올림 없는 표시/파서를 쓰고 소수 자릿수는 기존 `validatePaymentAmount`가 검증한다.
- **검증**: 프론트 `bun run lint`(경고 0)·`bun run test`·`bun run build` PASS. Vitest로 401→refresh→재시도, 동시 401 단일 refresh, 재진입(만료 access + 유효 refresh) 유지, refresh 거부 시 로그아웃, 일시 장애 시 유지, 재시도 401 루프 방지, 다른 탭 회전, 만료 access 로그아웃 재시도, 채팅 스트림 재시도, MSW refresh 계약, 입금액 형식을 고정했다.
- **실측(로컬 임시 격리 스택 `bfqasess`, 루프백 13180/18181, auth `JWT_ACCESS_TOKEN_EXPIRATION=60000`, 백엔드는 기존 `bfqaui-*:local` 이미지 재사용·frontend만 수리본 빌드, notification·tax 등 제외, 끝난 뒤 컨테이너·볼륨·자격 파일 삭제)**: Gateway 직접 — 로그인 `expiresIn` 60000, 65초 뒤 만료 access 401, refresh 200·회전, 새 access 200, 이전 refresh 재사용 401, 로그아웃 200 후 그 access 401·refresh 401. 브라우저 — 로그인 후 access 만료 시각을 지나 `/sites`를 새로 고침(앱 재진입)해도 로그인 화면으로 가지 않고, 네트워크에 `401 → POST /auth/refresh 200 → 재시도`가 남고 저장 access 만료 시각이 갱신, 현장 목록 200. 로그아웃 버튼 후 저장소 토큰 null, 직전 refresh로 재발급 시도 401. 입금 관리 모달은 tax-service를 띄우지 않아 컴포넌트 테스트로만 확인.
- **남은 위험**: refresh 토큰(7일)이 access와 같이 `localStorage`에 있어 XSS 시 탈취 창이 30분 → 최대 7일로 늘어난다(실데이터 승격 게이트의 "세션 저장소/XSS 방어"에 해당). HttpOnly 쿠키 전환은 별도 설계 결정. auth-service는 refresh를 사용자당 키 1개(`auth:refresh:{userId}`)로 두므로 두 기기 이상 동시 사용 시 한 기기의 로그인·재발급이 다른 기기의 refresh를 무효화해 그 기기는 access 만료 때 로그인으로 간다(기기별 키는 Redis 키·로그아웃 범위 설계 결정). 서버 refresh 대조·교체가 원자적이지 않다(동시 같은 토큰 2회 성공 가능).

### 2026-11-01 세션 보강 — refresh 절대 상한·재사용 탐지·원자 회전, CSP Report-Only·HSTS (PR #263 보안 검토 후속)

- **서버(auth-service)**: refresh 토큰에 세션 id(`sid`)·최초 로그인 시각(`sst`)·`jti` claim을 넣고, 회전해도 `sid`·`sst`는 그대로 옮긴다. 절대 상한 `jwt.session-absolute-expiration`(기본 7일 = refresh TTL, env `JWT_SESSION_ABSOLUTE_EXPIRATION`)을 넘으면 회전 불가(401), refresh 토큰 만료와 Redis TTL은 `min(refresh TTL, sst + 상한 − 지금)`. Redis 키는 기존 `auth:refresh:{userId}` 그대로이며 값만 `v3|sid|현재 토큰 SHA-256|직전 토큰 SHA-256|회전 시각`(원문 토큰 미저장)으로 바꿨다. 회전은 Lua 한 번(GET→sid·해시 비교→SET PX)으로 원자 처리하고, 로그아웃은 같은 키 DEL이라 Redis 단일 스레드 순서상 로그아웃 뒤 새 refresh가 살아나지 않는다. 이미 회전된 refresh가 다시 오면 키를 폐기(이후 refresh 401 "이미 사용된…")하되, 직전 토큰이 유예(`jwt.refresh-reuse-grace` 기본 30초) 안이면 여러 탭 동시 재발급으로 보고 폐기 없이 **409**를 준다. 다른 세션 sid·이전 형식 값은 변경 없이 401.
- **배포 호환**: `sid`/`sst` 없는 기존 refresh 토큰은 401 → 기존 로그인 세션은 access 만료 시 **재로그인 1회**로 충분하다(키 형식 동일, 이전 값은 다음 로그인이 덮어씀). access 토큰·Gateway·notification 블랙리스트는 변경 없음.
- **프론트**: (M1) 로그인마다 클라이언트 세션 세대값 `sessionId`를 저장하고 refresh 회전은 유지·로그아웃은 지운다. 재발급 응답 반영 직전 메모리·저장소의 세대값/로그인 상태를 다시 대조해 다른 탭의 로그아웃(또는 새 로그인) 뒤 늦은 응답이 세션을 되살리거나 덮어쓰지 않게 하고, 이 탭이 로그아웃한 세션 토큰이 storage 이벤트로 다시 오면 따르지 않고 로그아웃 상태를 다시 쓴다. (M2) 로그아웃 401 → refresh가 400/401/403/404로 거부(또는 refresh 없음)되면 서버에 살아 있는 토큰이 없으므로 로컬 토큰을 지우고 로그인으로; 5xx·네트워크·409 미해결은 기존대로 보존·재시도 안내. (L1) 실패 요청의 Authorization이 현재 저장 access와 다르면 refresh 없이 새 access로 1회 재시도. refresh 409면 최대 2초 동안 다른 탭이 저장할 새 토큰을 기다려 사용하고, 없으면 일시 장애로 세션 유지.
- **Nginx/Caddy**: `frontend/nginx.conf`(LAN·공개 공통 경로)에 `Content-Security-Policy-Report-Only`(`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; img-src 'self' data: blob:; font-src 'self' data: https://fonts.gstatic.com; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'`), `always`로 오류 응답 포함. `'unsafe-inline'`은 Ant Design CSS-in-JS `<style>` 주입(실측 24개)·React `style` 속성 때문에 필요, Google Fonts는 `index.css`의 Inter `@import` 때문. `= /api/v1/auth/refresh` 전용 `limit_req` 30r/m·burst 10(429는 프론트가 일시 장애로 취급). Caddy에 `Strict-Transport-Security "max-age=31536000"`(PUBLIC_HOST apex/하위 여부 확인 전이라 includeSubDomains·preload 없음), CSP는 Caddy에서 중복 설정하지 않는다.
- **검증**: `./gradlew :auth-service:test` PASS(AuthService 11·RefreshSessionStore 6·JwtTokenProvider 6 등, Lua는 스크립트 문자열·분기 순서·인자·반환 코드 매핑 단위 테스트). 프론트 `bun run lint`(경고 0)·`bun run test`(전체 PASS)·`bun run build` PASS — 세대값 유지/신규, 늦은 재발급 응답 무시, 종료 세션 storage 되살림 거부, 새 세션 storage 수용, 409 대기·미해결 유지, L1 재시도, LogoutButton 400/401/403/404 정리·503/네트워크 보존. CI와 같은 방식의 `caddy adapt --validate`·`nginx -t` 로컬 PASS(HSTS 헤더 adapt 결과 확인).
- **실측(로컬 임시 격리 스택 `bfqahard`, 루프백 13280/18281, mysql·redis·eureka·gateway(기존 `bfqaui-*:local` 이미지)·auth·frontend(수리본 빌드), auth `JWT_ACCESS_TOKEN_EXPIRATION=60000`·`JWT_SESSION_ABSOLUTE_EXPIRATION=90000`·`JWT_REFRESH_REUSE_GRACE=5000`, 끝난 뒤 컨테이너·볼륨·이미지·자격 파일 삭제, `build-flow` 볼륨 4개·`bfqaui` 스택 무변경)**: Gateway 직접 — 로그인 값 `v3|…`(원문 토큰 없음)·PTTL 89536≤90000, 회전 200, 직전 refresh 유예 내 재사용 409·키 유지, 현재 refresh 회전 200, 유예 후 이전 refresh 재사용 401("이미 사용된…")·키 삭제·최신 refresh도 401, 같은 refresh 병렬 2회 = [200, 409]이고 승자 토큰 계속 유효, 로그아웃 200 후 refresh 401, 로그아웃↔회전 병렬 20회 되살아남 0, 상한 60% 시점 회전 200·회전 후 PTTL이 남은 상한 이하(늘지 않음), 상한 경과 후 refresh 401. frontend 경유 — HTML·API 응답 모두 CSP-Report-Only 헤더, refresh 11회 연속 401 뒤 429. 브라우저 — 로그인→대시보드·현장·세금계산서·견적서·AI 채팅 열기·로그아웃 동안 CSP 위반 콘솔 0건(합성 외부 이미지 프로브로 위반 기록이 콘솔에 남는 것 확인), Inter 웹폰트 로드, 로그아웃 후 저장소 토큰·sessionId null. 일부 화면은 업무 서비스를 띄우지 않아 503(CSP 무관).
- **남은 결정**: refresh HttpOnly 쿠키 전환(XSS 시 localStorage refresh 탈취 창 — 사용자 설계 결정 대기), CSP enforce 전환(Report-Only 위반 0 확인 후 별도 결정; 웹폰트 자체 호스팅 시 외부 출처 제거 가능, `'unsafe-inline'` 스타일은 Ant Design nonce 설정 전까지 유지), 기기별 세션(현재 사용자당 키 1개 — 한 기기 로그인이 다른 기기 refresh를 무효화), HSTS includeSubDomains/preload(도메인 구조 확인 후). 로그아웃과 경합해 로그아웃 **직전**에 회전으로 발급된 access는 블랙리스트 대상이 아니라 만료(30분)까지 유효하다(클라이언트는 M1로 되살리지 않음). LAN 직결에서는 Nginx 레이트 리밋 키가 클라이언트 `X-Forwarded-For`라 우회 가능(기존 로그인 리밋과 동일, 공개 경로는 Caddy가 덮어씀).

### 2026-11-01 휴대폰·태블릿 화면 깨짐 — 브라우저 실측 발견, 수리는 프론트 회귀만
- **현상(로컬 격리 스택 브라우저 실측)**: 375×812 모바일에서 `MainLayout`의 사이드바가 240px로 고정돼 본문이 135px만 남았다. 대시보드 통계 카드가 글자 단위로 세로 줄바꿈되고 `₩` 금액이 잘렸다. 768×1024 태블릿에서도 사이드바 240px가 남아 본문이 528px이고, 현장 상세 제목 "[QA] 화면 실측 현장"이 단어마다 세로로 꺾이며 요약 카드 4개가 한 줄에 몰렸다. 가족이 현장에서 휴대폰으로 쓸 가능성이 높아 V1 핵심 결함으로 본다.
- **수리(프론트만, API 계약 변경 없음)**: 분기 기준을 모바일 `< 768px`, 컴팩트(모바일+태블릿) `< 1024px`로 두고 `useViewport` 훅(`matchMedia` 구독, 없으면 데스크톱)과 `index.css` 미디어 쿼리가 같은 값을 쓴다.
  - 내비게이션: 1024px 미만은 고정 사이드바 대신 헤더의 `메뉴 열기` 버튼 + 왼쪽 Ant Design `Drawer`(같은 메뉴·로그아웃). 메뉴를 고르면 이동 후 Drawer가 닫히고, 활성 메뉴 `aria-current="page"`와 메뉴 버튼 `aria-label`을 유지. 데스크톱은 기존 사이드바 그대로.
  - 대시보드 통계 카드: 데스크톱 3+2 → 태블릿 2열(마지막 1장은 전체 폭) → 모바일 1열. 금액은 말줄임·줄바꿈 없이 폭에 맞춰 글자 크기만 줄인다(`clamp`). 현장별 손익 표와 AI 요약은 1024px 미만에서 위아래로 쌓인다.
  - 목록 표(견적·매입·세금계산서·보증, 대시보드 현장별 손익): 한 방식으로 통일 — 표 컨테이너 가로 스크롤 + 1024px 미만에서 셀 줄바꿈 금지 + 마지막 `처리` 열을 오른쪽에 고정(sticky)해 처리 버튼이 화면 밖으로 사라지지 않게 했다. 현장 목록(탐색 패널+선택 현장)은 1024px 미만에서 세로로 쌓이고, 알림 목록은 원래 카드형이라 헤더 버튼 줄바꿈만 손봤다.
  - 등록/수정 모달: 모바일에서 폭 `100vw - 16px`, 높이를 화면 안으로 제한하고 본문만 스크롤해 하단 버튼이 항상 보이게, 버튼은 넘치면 줄바꿈. 견적 품목 입력 행은 품목명 한 줄 + 단위·수량·단가·삭제 한 줄로.
  - 현장 상세: 요약 카드 4열 → 태블릿 2열 → 모바일 1열. 헤더는 제목 영역이 320px보다 좁아지면 상태·수정 박스를 아래 줄로 내리고 제목 글자 크기를 폭에 맞춘다(단어 단위 세로 꺾임 방지). 탭 안 3열 정보 칸은 모바일 2열.
  - 필터 바: 1024px 미만에서 입력이 폭을 넘지 않게, 모바일에서는 검색·상태·기간·금액 입력을 한 줄에 하나씩 전체 폭으로(금액 범위는 두 칸이 반씩).
  - 채팅 런처: 1024px 미만 본문 하단 여백을 88px로 늘려 마지막 버튼이 런처에 가리지 않게 했다(모달·Drawer는 런처보다 위 레이어). 알림 벨 드롭다운도 화면 폭을 넘지 않게.
- **검증**: 프론트 `bun run lint`(경고 0)·`bun run test`(60파일 257테스트)·`bun run build` PASS. Vitest로 `useViewport` 분기(375/767/768/1023/1024/1440px, 크기 변경 재계산, 구독 해제, `matchMedia` 부재)와 `MainLayout`(데스크톱 사이드바·메뉴 버튼 없음, 375/768px 메뉴 버튼만, Drawer 메뉴 선택 시 이동·닫힘·`aria-current` 갱신)을 고정했다.
- **브라우저 재확인: PASS(2026-11-01, `bfqaui` 격리 스택 프론트만 수리본으로 교체)** — 375×812: 사이드바 없음·본문 375px·가로 넘침 0, 메뉴 열기 버튼으로 Drawer 열림·메뉴 선택 시 이동 후 닫힘, 대시보드 카드 1열(금액 온전), 세금계산서 목록 가로 스크롤·처리 열 고정(입금 버튼 화면 안). 768×1024: 본문 768px, 현장 상세 제목 한 줄·요약 카드 2열. 등록 모달·견적·매입·보증 목록의 모바일 화면은 미확인.

### 2026-11-01 알림 문구에 현장·거래처 이름 — 백엔드만 수리

- **현상(화면 실측)**: 알림 목록이 "매출 세금계산서(ID: 1)가 등록되었습니다.", "매입(ID: 1)이 등록되었습니다.", "견적서(ID: 1)가 확정되었습니다."처럼 내부 ID만 보여 가족이 어느 현장·거래처 자료인지 알 수 없었다. 화면은 서버 `message`를 그대로 보여 주므로 백엔드만 고쳤다(프론트·API 응답 DTO 변경 없음).
- **수리**: notification-service가 문구를 `NotificationMessageFormatter`(순수 함수)로 만든다. 예: `[강남 A현장] 견적서(외벽 도장 공사)가 확정되었습니다.`, `[강남 A현장] 매입(시멘트, 대한건자재)이 등록되었습니다.`, `[강남 A현장] 매출 세금계산서(대한건설, 1,100,000원)가 등록되었습니다.`, `[강남 A현장] 세금계산서(대한건설, 1,100,000원) 입금이 확인되었습니다.`, `[강남 A현장] 하자보증보험(ID: 5, 보험사) 만료까지 18일 남았습니다.` 이름이 하나도 없으면 기존 `(ID: n)` 문구, 현장명이 없으면 `[...]` 접두어 없이 저장한다.
- **방식**: 견적 제목·매입 품목/거래처는 발행 서비스 payload에 선택 필드(`title`, `itemName`, `supplier`)를 추가했다(하위 호환 — `docs/ARCHITECTURE.md` 2.2). 세금계산서는 기존 `counterparty`·`totalAmount`를 쓴다. 현장명은 세 발행 서비스가 모두 갖고 있지 않아 payload 대신 notification-service가 기존 Feign으로 소비 시점에 조회한다(트랜잭션·보증 행 잠금 밖, 실패 시 폴백, 장애 뒤 30초 조회 생략으로 밀린 이벤트 소비가 타임아웃 누적으로 리밸런스되지 않게 함). eventId 멱등 처리는 그대로이고, 금액은 JSON 실수를 `BigDecimal`로 읽어 16자리 금액도 반올림되지 않는다. 사용자 입력 이름은 제어·서식 문자 제거 + 공백 접기 + 50자(코드포인트) 말줄임.
- **검증**: `./gradlew :notification-service:test :purchase-service:test :estimate-service:test` PASS(문구 생성·필드 누락 폴백·말줄임·제어문자·금액 포맷·현장명 조회 실패 폴백·구 이벤트·발행 payload 이름 필드). 격리 스택 실측은 하지 않았다(단위 테스트만). 이미 저장된 과거 알림 문구는 바뀌지 않는다.

### 2026-11-01 refresh 회전 Lua 실제 Redis 통합 테스트·유예 축소·LAN 보안 헤더 (PR #265 보안 재검토 후속)

- **M-1 (PENDING — CI 결과 대기)**: `auth-service` `RefreshSessionStoreRedisIntegrationTest`가 회전 Lua를 실제 Redis에서 실행한다. 단위 테스트(Mockito)는 스크립트 문자열·인자만 고정하므로 Lua 패턴·분기·원자성은 이 테스트가 증명한다. 고정 시나리오: 회전 성공(값 `v3|sid|새 해시|직전 해시|회전 시각`, PTTL ≤ 넘긴 TTL), 유예 내 직전 토큰 `CONCURRENT`(서비스 409)·값 불변, 유예 밖 직전 토큰 `REUSED`(401)·키 DEL·이후 최신 토큰 `NOT_FOUND`(401), 모르는 토큰도 재사용 처리, 다른 sid `OTHER_SESSION`·값 불변, 이전 형식(원문 JWT)·`v2`·필드 부족/초과·숫자 아닌 회전 시각·빈 sid·빈 문자열 → 거부·값·TTL 불변, 키 없음 → `NOT_FOUND`·키 생성 없음, 같은 토큰 병렬 2회 20번 반복 모두 `[ROTATED, CONCURRENT]`·승자 토큰 유효, 로그아웃 DEL↔회전 병렬 200회 되살아남 0, `AuthService`+실제 Redis로 refresh TTL(2h)이 절대 상한(1h)보다 길어도 로그인 PTTL ≤ 상한·상한 60% 시점 회전 후 PTTL ≤ 남은 상한, 409/401 오류 코드 매핑. 전용 DB 인덱스 15에서 이 테스트가 만든 키만 지우며, `AUTH_REDIS_INTEGRATION=true`와 `REDIS_HOST=127.0.0.1`일 때만 실행된다(일반 `:auth-service:test`에서는 건너뜀). CI 잡 `auth-redis`(인증 refresh 회전, `redis:7.2-alpine` 서비스 컨테이너)가 이 클래스만 돌리고 결과 XML의 tests ≥ 16·skipped 0을 단언하며, 릴리스 후보 증거 잡의 `needs`·`REQUIRED_RESULTS`에 추가했다. Testcontainers는 쓰지 않았다(기존 Flyway 잡과 같은 서비스 컨테이너+환경 변수 게이트 방식).
- **로컬 실행**: 임시 컨테이너 `bfrit-redis`(`redis:7.2-alpine`, `127.0.0.1:16399`) — 16건 PASS·skipped 0, 종료 시 DB 15 키 0개, 컨테이너 삭제. `build-flow` 볼륨 4개·`bfqaui` 스택 무변경.
- **L-2**: `jwt.refresh-reuse-grace` 기본값 30초 → **10초**(env `JWT_REFRESH_REUSE_GRACE`). `0 ≤ grace < jwt.refresh-token-expiration`을 생성자에서 검사해 위반이면 기동 실패(단위 테스트: 0·TTL−1 허용, TTL·TTL 초과·음수 거부). 유예가 짧을수록 탈취 토큰이 409로 숨을 수 있는 창이 줄고, 여러 탭 동시 재발급은 프론트가 409 후 최대 2초 동안 다른 탭의 새 토큰을 기다리므로 10초로 충분하다.
- **L-5**: `frontend/nginx.conf`에 `X-Frame-Options: DENY`·`X-Content-Type-Options: nosniff`·`Referrer-Policy: no-referrer`(Caddy와 같은 값, `always`)를 서버 블록에 추가해 LAN 직결 경로에도 붙인다. 공개 경로에서는 Caddy `reverse_proxy`의 `header_down -…`로 Nginx 사본을 지워 Caddy 값이 한 번만 나간다. 로컬 확인: CI와 같은 `nginx -t`·`caddy adapt --validate` PASS, 임시 Nginx(실제 `nginx.conf`)+Caddy(실제 Caddyfile, 호스트 줄만 평문 HTTP로 치환) 컨테이너에서 Nginx 직접 응답에 세 헤더 각 1회, Caddy 경유 응답에 세 헤더+HSTS 각 1회(중복 없음), 끝난 뒤 컨테이너·네트워크 삭제.
- **L-1 (미구현 — 설계 메모)**: 재사용 탐지 시 이미 발급된 access(최대 30분)를 즉시 끊으려면 auth가 `REUSED`에서 `auth:revoked-before:{userId}` = 탐지 시각(TTL = access TTL)을 쓰고, Gateway `AuthorizationHeaderFilter`(그리고 access를 직접 검증하는 notification SSE 경로)가 블랙리스트 조회와 함께 이 키를 읽어 `iat < 표식`이면 401을 준다. 고려점: JWT `iat`는 초 단위라 표식도 초로 내림해 저장하고 `iat < 표식초`만 거부하는 규칙으로 고정해야 함(같은 초에 발급된 access는 통과 — 최대 1초 창을 허용하는 대신 탐지 직후 재로그인한 정상 access를 막지 않음), 요청마다 Redis 조회 1회 추가(현재 블랙리스트와 묶어 MGET 가능), auth·gateway·notification 공용 키 계약 문서화와 세 서비스 동시 배포. 같은 표식으로 로그아웃 직전 회전 access 잔존 문제도 닫을 수 있다. 변경 범위(3개 서비스·공용 키 규칙·시간 단위)가 커서 이번 후속에서는 하지 않았다.
- **남은 위험**: L-1 미구현으로 재사용 탐지 뒤에도 공격자가 이미 받은 access는 만료까지 유효. 통합 테스트는 단일 Redis 인스턴스 기준이며 Redis Cluster·복제 지연은 대상 밖(운영도 단일 인스턴스).

### 2026-11-01 없는 주소·없는 현장 빈 화면 — 브라우저 실측 발견, 수리는 프론트 회귀만
- **현상**: `App.tsx`에 catch-all 라우트가 없어 `/clients` 같은 없는 주소는 로그인 상태에서도 레이아웃 없이 빈 화면이었다. 없는 현장 상세 `/sites/999999`는 서버 404(`SITE_NOT_FOUND`)를 일반 조회 실패("데이터를 불러오는 중 오류", 다시 시도)로 보여 줬고 404도 1회 재시도했다. `/sites/0`·음수는 조회가 꺼진 채 로딩 화면에 멈췄다.
- **수리(프론트만, API 계약 변경 없음)**: 레이아웃 라우트 안에 `path="*"` → Ant Design `Result` 404 "페이지를 찾을 수 없습니다" + `대시보드로` 버튼(사이드바/모바일 Drawer 유지). 비로그인은 기존 `PrivateRoute`가 먼저 `/login`으로 보낸다. 현장 상세는 API 404와 잘못된 ID(문자·0·음수·소수)를 "현장을 찾을 수 없습니다" + `목록으로`(`/sites`)로 안내하고, `useSite`는 404를 재시도하지 않는다. 404 외 오류는 기존 안내·다시 시도 유지.
- **검증**: 프론트 `bun run lint`(경고 0)·`bun run test`(63파일 280테스트)·`bun run build` PASS. Vitest로 없는 경로 → 404 화면·주 메뉴·대시보드 버튼 이동, 비로그인 → `/login`, 현장 404·500·잘못된 ID 분기, 404 판별·재시도 규칙을 고정했다. 브라우저 재확인은 미실시.

### 2026-11-01 현장에 연결되지 않은 거래처를 관리할 화면 없음 — 거래처 메뉴 추가, 프론트 회귀만
- **현상**: 거래처는 현장 등록 창의 "새 거래처 등록"으로만 만들고, 수정·보관·복원은 거래처가 연결된 현장 상세의 "거래처 수정"에서만 가능했다. 어느 현장에도 연결되지 않은 거래처는 화면에서 관리할 수 없었다.
- **수리(프론트만, API 계약 변경 없음)**: 사이드바·모바일 Drawer 공용 메뉴의 `현장 관리` 아래에 `거래처`(`/clients`) 추가. 목록은 업체명·대표자·사업자번호·연락처 검색, 보관 제외(기본)/보관된 거래처만/보관 포함 전체 필터(현장 목록과 같은 URL 필터), 연결 현장 수(현장 목록 응답에서 계산, 보관 현장 수 병기), 행별 `수정`(기존 `ClientEditModal`)·`보관`/`복원`(확인 후 기존 archive/restore API)과 `거래처 등록`(기존 `ClientCreateModal`). 보관 거래처는 내용을 흐리게 하고 `보관` 표시. 좁은 화면은 기존 표 가로 스크롤·처리 열 고정 규칙을 따르고 휴대폰에서는 대표자·사업자번호 열을 접는다. 현장 목록에 거래처 ID 필터가 없어 거래처 이름 → 현장 목록 필터 이동은 넣지 않았다.
- **검증**: 프론트 `bun run lint`(경고 0)·`bun run test`(64파일 289테스트)·`bun run build` PASS. Vitest로 목록·연결 현장 수, 보관 필터 3종, 검색, 수정·등록 모달 열기, 보관/복원 요청, 메뉴 항목 순서·이동(데스크톱·모바일)을 고정했다. 브라우저 재확인(같은 날 `bfqaui` 격리 스택 프론트만 교체): 사이드바 '거래처' 메뉴(현장 관리 다음)·`/clients` 화면, 현장 미연결 거래처 등록 → 목록 표시(연결 현장 0곳) → 보관 확인 후 기본 필터에서 숨김 PASS.

### 2026-11-01 "이 거래처의 현장 보기" 불가 — 현장 목록 거래처 필터, 프론트 회귀만
- **현상**: `/clients`에서 연결 현장 수는 보이지만 그 현장들로 갈 방법이 없었고, 현장 목록에도 거래처로 거르는 필터가 없었다.
- **수리(프론트만, API 계약 변경 없음)**: 현장 목록에 URL 쿼리 `clientId` 거래처 필터(검색 가능한 선택, 보관 거래처는 `보관된 거래처` 묶음·`(보관)` 표시, 목록에 없는 ID는 `거래처 #ID (목록에 없음)`). `useFilterParams`에 양의 정수만 받는 `id` 타입을 추가해 `0`·음수·소수·지수·앞자리 0·초과 정수는 URL에서 지운다. 현장 목록 필터에 적용 개수와 `필터 N개 초기화`를 더했다. 기존 `GET /sites`는 `status`·`archived`만 받고 이미 보관 포함 전체 현장과 거래처를 함께 내려 주므로 받은 목록에서 거른다. 거래처 목록의 업체명·연결 현장 수는 `/sites?clientId=<id>`로 이동하며, 보관 현장이 섞인 거래처는 연결 현장 수와 결과가 같도록 `&archive=ALL`을 붙인다. 휴대폰에서는 현장 탐색 필터가 기존 `bf-filter-fields` 규칙으로 폭을 채운다.
- **검증**: 프론트 `bun run lint`(경고 0)·`bun run test`(65파일 301테스트)·`bun run build` PASS. Vitest로 `id` 파싱·정리, 선택지 구성·필터 술어·이동 경로, 현장 목록의 쿼리 필터·보관 조합·잘못된 값 정리·초기화·검색 선택, 거래처 목록 링크 이동을 고정했다. 브라우저 재확인(데스크톱·태블릿·휴대폰 화면, 거래처 → 현장 이동)은 PASS(2026-11-01 `bfqaui` 격리 스택: 거래처 목록 업체명 링크 → `/sites?clientId=1`, 필터 칸에 '(보관)' 거래처 선택 표시, 필터 결과 0·'필터 1개 초기화' 노출).

### 2026-11-01 AI 채팅 질문 후 앱 전체가 하얗게 사라짐 — effect 반환값 수리, 프론트 회귀만
- **현상(로컬 격리 스택 브라우저 실측, 실제 Ollama)**: 채팅에 "안녕하세요"를 보내면 `/api/v1/chat/stream`이 200으로 시작한 뒤 화면 전체가 비었다(본문 텍스트 0). 콘솔 `TypeError: c is not a function`, 압축 안 한 빌드에서 React `commitHookEffectListUnmount`의 `destroy()` 호출 지점.
- **원인**: `ChatPanel` 자동 스크롤 effect가 식 본문 화살표라 `scrollIntoView` 반환값을 그대로 돌려줬다. 최신 Chromium의 `scrollIntoView`는 Promise를 반환하므로 React가 그 값을 정리 함수로 호출하다 실패했고, 에러 바운더리가 없어 루트 전체가 언마운트됐다.
- **수리(프론트만, API 계약 변경 없음)**: effect를 블록 본문으로 바꿔 아무것도 반환하지 않는다. 같은 패턴은 저장소에 이 1곳뿐이었다. 재발 방지로 ESLint `no-restricted-syntax` 규칙을 추가해 `useEffect`·`useLayoutEffect`·`useInsertionEffect`(및 `React.` 접두) 콜백의 식 본문을 오류로 막는다(정리 함수를 바로 반환하는 `() => () => ...`는 허용, 새 의존성 없음). 레이아웃의 페이지 영역(라우트 출력, 경로가 바뀌면 자동 해제)과 채팅 패널을 각각 `ErrorBoundary`로 감싸 오류 시 antd `Result` 안내 "문제가 생겼습니다. 다시 시도해 주세요."와 `다시 시도` 버튼을 보인다. 채팅 오류는 페이지를 지우지 않는다. 오류 원문·스택(토큰이 섞일 수 있음)은 화면과 콘솔 기록에 남기지 않고 영역 이름·오류 종류만 기록한다.
- **검증**: 프론트 `bun run lint`(경고 0)·`bun run test`(66파일 306테스트)·`bun run build` PASS. Vitest로 `scrollIntoView`가 Promise를 반환하는 목에서 스트리밍 렌더·완료·언마운트가 예외 없이 동작함(수리 전 코드에서는 `destroy is not a function`으로 실패 재현), 에러 바운더리의 안내 표시·토큰 비노출·다시 시도 리셋·경로 변경 리셋·형제 영역 보존을 고정했다. 브라우저 재확인 PASS(2026-11-01, `bfqaui` 격리 스택·실제 Ollama qwen2.5:7b: 질문 후 앱 유지, 손익 답변 견적 600,000·매입 260,000·마진 340,000·56.67% 정확).

### 2026-11-01 합성 공내역서 → 실제 Ollama AI 파싱 → 견적 초안·확정 — 로컬 44/44 PASS
- **실행**: develop `d20e100` 기준 `claude/estimate-parse-e2e`에서 `python -I -X utf8 scripts/qa/estimate_parse_e2e.py --runs 5`. 별도 Compose 프로젝트 `bfqaparse`(빈 볼륨 3개, Gateway만 `127.0.0.1:18083`)로 mysql·redis·kafka·eureka·gateway·auth·site·estimate를 띄우고, estimate-service는 `host.docker.internal:11434`로 호스트 native Ollama `qwen2.5:7b`(Q4_K_M, RTX 4060 8GB)를 부른다. DB 비밀번호·JWT 비밀·QA 관리자 비밀번호는 실행마다 메모리에서만 무작위 생성(출력·저장 없음), 사용자 API 키는 넘기지 않았다. 기존 `build-flow_*` 볼륨 4개와 떠 있던 `bfqaui` 스택은 건드리지 않았고, 종료 후 `bfqaparse` 컨테이너 0·볼륨 0을 확인했다.
- **코드 사실**: `POST /api/v1/estimates/parse`(multipart `file`)는 `.xlsx` 첫 시트만 받는다(파일명 확장자 검사, 10MB, 비어 있지 않은 행 최대 300행). 셀을 탭 구분 텍스트로 만들어 Ollama `/api/chat`(시스템 프롬프트 + `stream:false`, timeout 120초)에 보내고, 응답에서 첫 번째로 역직렬화되는 JSON 배열을 `itemName·unit·quantity·unitPrice·amount`로 돌려준다. 모델 출력의 값 검증·규격 필드는 없다. 결과는 **미리보기만**이고 DB에 저장하지 않는다. 화면이 "견적서로 만들기"에서 결과를 작성 폼에 채우고 사용자가 확인·수정한 뒤 일반 견적 생성 API로 DRAFT를 만든다. **원본 보존 없음**: 업로드한 엑셀은 저장되지 않고, 견적에 원본 파일·체크섬·파서/모델 버전·AI 추출값과 사용자 수정값의 구분이 남지 않는다(`docs/PRODUCT_VISION.md` "원본과 해석 결과 분리" 미충족, 스키마·저장소가 필요한 설계 변경이라 이번에 고치지 않음).
- **합성 입력**: 스크립트가 실행 중 메모리에서 공내역서를 만든다(커밋 없음). 제목·공사명·빈 행·머리글(번호/품명/규격/단위/수량/단가/금액/비고)·공종 행·소계·합계 행을 넣고 단가·금액은 비우며, 5~8개 품목 중 규격이 빈 `식` 항목을 반드시 하나 넣는다(빈 셀은 엑셀처럼 파일에 쓰지 않음).
- **판정 기준(서버 검증 규칙이 없어 스크립트가 엄격 기준을 둠)**: 품목 수 일치·머리글/소계/합계 행 미포함, 품명 공백 무시 일치(규격 덧붙음은 따로 셈), 단위 일치, 수량 수치 동일. 이어서 AI 결과에 단가만 채워 견적 초안 201 DRAFT·합계 일치 → 확정 CONFIRMED → site 손익 견적 합계(Kafka) 반영까지 단언했다.
- **결과**: 5회(5·5·6·8·6개 품목, 합 30개) 모두 품목 수·품명·단위·수량 30/30 정확, 규격 덧붙음 0, 소계·합계 행 혼입 0, 단가·금액은 0으로 와서 null 0건. 소요(Gateway 경유 요청~응답) 4.2·3.5·4.3·5.6·4.3초. 앞선 수리 전 기준선 3회(8·8·5개)도 21/21 정확, 10.6초(모델 첫 적재 포함)·6.5·3.6초. 경계: 무인증 401, `.csv` 400 "xlsx 형식의 파일만 업로드 가능합니다.", 깨진 `.xlsx` 422 "엑셀 파일 파싱에 실패했습니다.", Ollama 주소를 닫힌 포트로 바꿔 estimate만 재생성하면 0.4초 만에 503 "AI 파싱 서비스에 연결할 수 없습니다. Ollama가 실행 중인지 확인해주세요."(화면은 이 `error.message`를 그대로 띄운다).
- **수리(백엔드만, API 계약 변경 없음)**: `ExcelParserService`가 `for (Cell cell : row)`로 존재하는 셀만 돌아 빈 셀 뒤 열이 앞으로 당겨졌다(규격이 빈 행의 단위가 규격 열 위치로 감). 이번 실측에서는 모델이 맞게 복원해 오답은 없었지만, 중간 칸(예: 수량 빈칸 + 단가 기재)이 비면 숫자가 다른 열로 읽힐 수 있어 0열부터 마지막 셀까지 빈 칸을 채워 열 위치를 보존하게 했다(최대 50열). 단위 테스트 `ExcelColumnAlignmentTest`로 고정, `./gradlew :estimate-service:test` PASS.
- **한계**: 깨끗한 합성 1시트·8품목 이하만 시험했다. 병합 셀·여러 시트·수식·수백 행 실제 공내역서, 단가가 채워진 내역서의 금액 계산 정확도, 300행 상한을 넘는 문서, CPU 전용 환경의 시간, 공개 VPS(접속 금지), 실데이터는 이 결과로 통과 처리하지 않는다. 모델 출력 검증(품목 수·수량 범위·열 대조)과 원본 보존이 없어 실제 문서에서는 사용자의 미리보기 확인이 유일한 안전장치다.

### 2026-11-01 데이터 없는 현장을 적자로 말하는 AI 요약 — 사실 판정을 서버로 이동, 백엔드만
- **현상(로컬 실측)**: 견적·매입이 하나도 없는 현장 1개만 있을 때 `GET /api/v1/dashboard/summary`가 "모든 현장에서 적자 또는 손해가 발생", "[QA] AI 요약 현장의 경우 마진율이 10% 미만임으로 경보"라고 답했다. 원인은 site-service가 모든 현장을 `견적 0원, 마진 0원 (마진율 0%)`로 넘기고 "마진율 10% 미만 또는 적자 현장 경고"를 모델에게 맡긴 것이다.
- **수리(site-service만, API 계약 변경 없음)**: 새 `DashboardSummaryFacts`가 현장을 결정적으로 분류한다. 견적 합계 0 → "견적 미등록(손실 아님)", 수주 전 → 평가 제외, 나머지에서 마진 < 0 → 적자, 마진율 < 10% → 저마진(10.00%는 주의 아님). 다음 할 일도 서버가 적자("매입 내역과 추가공사 견적 누락 여부 확인") → 저마진("남은 매입 계획과 마진 확인") → 견적 미등록("견적 등록") 순으로 정한다. 금액은 `Locale.ROOT` 천 단위 쉼표(`10,000,000원`), 비율은 소수 2자리로 미리 문자열화한다. 시스템 프롬프트는 "제공된 사실 외의 판단·추정·새 계산 금지", 경고는 주의 목록에만, 할 일은 목록만 옮기기, 한글만 쓰기로 바꿨다. 견적·매입이 전혀 없으면(현장 0개 포함) 모델을 부르지 않고 "아직 견적·매입이 등록된 현장이 없습니다. 등록된 현장 1개는 손실이 아니라 아직 데이터가 없는 상태입니다. …"를 돌려준다.
- **검증**: `./gradlew :site-service:test` PASS. `DashboardSummaryFactsTest`(빈 데이터·견적 미등록·매입만 있는 현장·적자·저마진·수주 전·정상 혼합의 사실 목록과 할 일, 10% 경계, 독일 로케일에서도 점 소수점), `DashboardServiceTest`(빈 데이터 2종 모델 미호출 `verifyNoInteractions`, 데이터가 있으면 분류된 사실을 넘김).
- **실제 모델 확인(호스트 Ollama `qwen2.5:7b` 직접 호출, 풀 Docker 아님)**: 빌드된 클래스로 만든 사실 프롬프트 + 새 시스템 프롬프트를 `/api/chat`에 보냈다. 빈 현장 1개는 모델 호출 없이 위 안내 문구. 정상 2개 + 빈 현장 1개 2회: 주의 현장 "없음", 할 일 "[QA] 빈 현장: 견적 등록"만, 합계 12,000,000/7,600,000/4,400,000원·36.67% 그대로. 정상 + 적자 + 수주 전 2회: 주의 현장에 적자 현장만, 할 일 "매입 내역과 추가공사 견적 누락 여부 확인". 프롬프트 강화 전에는 적자 현장에 "견적 등록 필요"·"모니터링하고 개선사항을 조사" 같은 지어낸 할 일이 나왔다.
- **남은 위험**: 7B 모델이 판정은 지켰지만 1회 "마argin율" 같은 글자 섞임과, 할 일 뒤에 "지금 따로 조치할 일은 없습니다"를 덧붙이는 모순이 있었다(사실 판정은 틀리지 않음). 모델 출력 사후 검증(라틴 문자·목록 밖 현장명 탐지 시 서버 결정 문구로 대체)은 하지 않았다. 현장명은 사용자 입력이 그대로 프롬프트에 들어간다. 풀 Docker Gateway 경유 화면 확인과 공개 VPS는 PENDING. (사후 검증은 아래 항목에서 추가)

### 2026-11-01 AI 요약 모델 출력 사후 검증 — 걸리면 서버 결정 문구로 대체, 백엔드만
- **현상**: 위 항목의 7B 모델이 판정은 지켰지만 "마argin율"·"中有" 같은 외국 문자, "8,000만원"처럼 단위를 바꾼 금액, 할 일 목록 뒤 "지금 따로 조치할 일은 없습니다" 모순을 섞었다.
- **수리(site-service만, API 계약 변경 없음)**: 새 `SummaryOutputGuard`가 모델 출력을 `DashboardSummaryFacts`와 대조한다. (1) 외국 문자: 라틴·한자·키릴·가나·태국 문자 덩어리 중 사실 목록(현장명·상태 원문 등)에 원래 있던 덩어리와 `AI`가 아닌 것. (2) 모르는 현장명: 알려진 현장명을 지운 뒤 "OO현장"처럼 붙은 이름(전체·각·나머지 같은 일반 낱말 제외)이나 "OO (시공 중)"처럼 상태 라벨이 붙은 이름이 남음. (3) 새 숫자: 원·% 단위 숫자가 사실 목록 값과 다름(쉼표·뒤쪽 0·부호는 무시해 `30%`=`30.00%`, "마진 200,000원 손실"=`-200,000원`), 만·억 단위가 끼면 항상 위반. (4) 할 일이 있는데 "조치할 일은 없"이 나옴. 빈 출력도 위반. 하나라도 걸리면 모델 출력 대신 `deterministicSummary()`(1. 전체 현황 2. 손익 3. 주의 현장 4. 다음 할 일을 사실 목록 그대로 옮긴 템플릿 마크다운)를 돌려주고, WARN 로그에는 위반 종류와 출력 길이만 남긴다(출력 원문·토큰은 사용자 입력이 섞일 수 있어 남기지 않음). 모델 호출 실패(503)는 바꾸지 않았다. 화면이 "AI 요약을 불러오지 못했습니다" + 재시도를 보여 주고(2026-10-19 회귀), 손익 숫자는 통계 카드가 이미 결정적으로 보여 주므로 장애를 정상 요약처럼 숨기지 않는 쪽을 택했다.
- **검증**: `./gradlew :site-service:test` PASS(94건). `SummaryOutputGuardTest` 10건(정상 통과·영문 현장명 "Tower A동"과 `AI` 허용·라틴/한자/베트남어 혼입·모르는 현장명 2형태·일반 낱말+현장 허용·새 금액/비율/만 단위·소수 자리와 부호 차이 허용·할 일 모순·빈 출력·결정 문구 자기 통과), `DashboardSummaryFactsTest` 결정 문구 2건, `DashboardServiceTest` 대체/통과 2건.
- **실제 모델 실측(호스트 Ollama `qwen2.5:7b` 직접 호출, 빌드된 클래스로 사실 프롬프트+시스템 프롬프트 생성·검증, 풀 Docker 아님)**: 정상 데이터(정상 2·빈 현장 1) 10회 중 2회 대체(외국 문자 2·"8,000만원" 1, 겹침 포함), 혼합 데이터(영문 현장명 정상·적자·저마진·매입만·입찰) 10회 중 9회 대체(외국 문자 6·할 일 모순 4, 겹침 포함). 전체 20회 중 11회(55%) 대체, 대체된 11건을 눈으로 확인해 모두 실제 결함이었다(오탐 0). 통과 9건에 "Tower A동"·`[QA]`·`37.50%` 같은 사실 표기는 오탐 없이 통과했다. 응답 3.0~15.0초.
- **남은 위험**: 혼합 데이터에서 대체율이 90%라 실사용 화면은 대부분 템플릿 문구가 된다(사실은 정확하지만 문장이 딱딱함). 할 일 모순만 있을 때 마지막 문장만 잘라내는 완화, 또는 모델 교체(qwen3:8b 비교 기록 있음)가 후속 후보다. 검사하지 않는 것: "개" 단위 개수(실측 1회 "평가 대상 2개"로 틀림 — 통과됨), 한글끼리의 글자 잡음("겹견적"), 띄어쓴 가짜 한글 현장명("가짜 현장"), 사실의 의미 뒤바뀜(적자 현장을 정상이라 말하기). 풀 Docker Gateway 경유 화면 확인과 공개 VPS는 PENDING.

### 2026-11-01 견적 카드 날짜가 실제 견적일이 아닌 등록 시각 — 실데이터 적재로 발견, 프론트 회귀만
- **현상**: 과거 견적을 로컬 실데이터로 적재한 뒤 현장 상세·현장 목록의 견적 카드가 "작성일"로 DB 생성 시각(`createdAt`)을 보여 줘, 오래된 견적이 모두 적재한 날짜로 보였다. 정렬도 생성 시각 기준이라 과거 견적이 맨 위에 섞였다.
- **수리(프론트만, API 계약·MSW 변경 없음)**: 두 카드의 업무 날짜를 "견적일"(`estimateDate`)로 바꾸고 생성 시각은 "등록일"로 따로 표시한다. 정렬은 견적일 최신순 → 같은 날이면 등록 시각 → id 역순(`compareEstimatesByDateDesc`). 날짜가 없으면 다른 날짜로 대체하지 않고 `-`. 견적서 목록·수정 체인 모달은 이미 견적일, 매입(매입일)·세금계산서(발행일)·보증(기간)의 카드/목록과 정렬은 이미 업무 날짜 기준이라 변경 없음. 알림의 상대 시각은 사건 발생 시각이 맞으므로 유지.
- **검증**: 프론트 lint(경고 0)·Vitest·build 로컬 PASS(카드 라벨·값·정렬, 날짜 없음 `-`). 풀 Docker 화면 재확인과 공개 VPS는 PENDING.

## 업무 흐름 적합성 점검

| 단계 | 현재 구현·확인 | 다음 확인/결정 |
|---|---|---|
| 수주 전 견적 | 견적 작성 시 현장 ID 필수. `docs/PLANNING.md`의 `공내역서→견적→계약→현장` 순서와 다름. 2026-10-23 **에이전트 결정(사용자 위임)**: 현장 상태에 `BIDDING`(수주 전·견적 중)을 추가했다. 견적이 먼저인 일은 BIDDING 현장을 만들어 견적을 붙이고, 수주하면 현장 화면에서 `IN_PROGRESS`로 바꾼다. 수주 실패는 기존 보관으로 정리한다. 생성 기본값은 IN_PROGRESS 유지(폼에서 수주 전 선택). 전환은 BIDDING→IN_PROGRESS만 허용하고 수주 이후 상태→BIDDING은 400(수주 후 매입·세금계산서가 붙은 현장을 견적 단계로 되돌리는 의미가 모호). 확정 견적이 없는 BIDDING 현장은 손익에 영향이 없고 대시보드 "진행 중인 현장" 카드에 수주 전 건수만 덧붙인다. 로컬 단위·격리 MySQL 실측만(`develop`·`main`·VPS 미반영) | 대안(기록만): 견적 `siteId` nullable 후 나중 연결 — estimate/purchase/tax/notification 현장 참조 검증과 손익 집계를 모두 흔들어 범위가 크다. 실사용에서 잘못 수주 전환한 경우의 복구 경로(관리자 되돌리기) 필요성 확인 2026-10-23 리뷰 후속(에이전트 결정): BIDDING 현장 금액은 대시보드 전체 합계·평균 마진율에서 제외하고 현장별 목록에만 표시. 수주 전 현장에도 매입·세금계산서·보증 등록은 막지 않는다(현장 실사·선발주 비용 기록 가능, 대안: site 참조 응답에 status를 넣어 차단 — 실사용에서 필요해지면 적용) |
| 원본 공내역서 | 파일 업로드는 AI 추출용이며 원본 보존·출처·버전 관계 관리가 아님. 현재 VPS AI 모델 미기동 | 원본+DB 동시 백업/격리 복원과 Stage 1 문서 코어 선행. 실제 USB 원본 업로드 금지 |
| 견적 확정·추가공사 | 초안은 수정/삭제 가능, 확정본은 금액에 반영되고 변경 불가. 2026-10-23 **에이전트 결정(사용자 위임)**: 문서 코어 Stage 1의 "작업별 회계 유효 버전 0..1"을 견적 도메인 최소판으로 먼저 구현했다. 견적에 `kind`(ORIGINAL/REVISION/ADDITIONAL)와 `revisionOfId`(같은 현장의 확정 또는 대체된 원 견적)를 둔다. 수정본은 원 견적의 수정 체인(`chainRootId`)에 들어가고, 수정본을 확정하면 그 체인의 기존 확정본은 `SUPERSEDED`(대체됨, `supersededById` 기록)로 남아 손익에서 빠진다(삭제·덮어쓰기 없음). 한 체인에서 손익에 반영되는 확정본은 최대 1개. 추가공사는 새 체인으로 원 견적과 별도 합산되고, 추가공사의 수정본은 그 추가공사만 대체한다. 손익은 기존 이벤트 조합 — 대체되는 확정본 금액의 `estimate.deleted`(payload `reason=SUPERSEDED`, `supersededById`) + 새 확정본 `estimate.parsed` — 를 같은 트랜잭션 outbox로 발행해 site 소비자 변경 없이 맞춘다(두 토픽 순서는 보장되지 않아 잠깐 합계가 흔들릴 수 있으나 가감이 교환 가능하고 eventId 멱등이라 최종값은 최신 확정본). 같은 체인의 동시 확정은 체인 루트 행 비관적 잠금으로 직렬화. 다른 현장·초안·없는 원 견적 연결 400, 대체됨 견적 수정·확정·삭제 409, 보관 현장 확정 409. 화면: 확정/대체된 견적 행의 `수정본`(항목 복사)·`추가공사`(빈 항목) 작성, 목록 관계 열·대체됨 취소선, `이력` 모달(체인 버전·추가공사), 확정 시 대체될 확정본 경고. 로컬 단위·H2·MSW 회귀만 PASS(`develop`·`main`·VPS 미반영) | 대안(기록만): 문서 코어 Stage 1 전면 구현(원본 Blob·가져오기 배치·관계 후보 검토함·append-only 결정) — 선행조건(실데이터 DB 마이그레이션·파일+DB 동시 백업/복구) 미충족이라 보류, 이 최소판의 체인·유효 버전 규칙을 이후 Stage 1 관계 확정으로 승격 검토. 새 토픽(`estimate.superseded`) — 소비자 2곳 신규 리스너·오프셋 기준선이 필요해 보류. 이미 이중 확정된 기존 데이터는 자동 관계 추정 없이 그대로 둔다(사람이 수정본 관계를 판단해야 함) |
| 원가·세금계산서 | 손익 원가는 `purchase-service` 매입 항목, 미수금은 매출 세금계산서의 공급가+세액. 매입 세금계산서는 원가에 자동 합산되지 않음. 2026-10-23 **에이전트 결정(사용자 위임)**: 원가의 단일 진실원은 매입 항목으로 유지한다(이중 계산 방지). 매입 세금계산서는 증빙으로서 같은 현장 매입 항목 0..N건과 연결하고 매입 1건은 계산서 최대 1건(`purchases.evidence_tax_invoice_id` nullable)에 연결한다. 연결 검증(존재·매입 유형·같은 현장)은 purchase-service가 tax-service Feign 조회로 하고 장애 503 fail-closed, 보관 현장 409. 연결된 매입 계산서의 삭제·매출 전환은 tax-service가 409(연결 해제 후 가능). 화면에 현장별 "증빙 미연결 매입 금액"과 "매입 계산서 합계 − 연결 매입 합계"(공급가·합계 기준)를 보여 누락·중복을 사람이 찾게 하며 자동 원가 합산은 하지 않는다. 로컬 단위·MSW만(`main`·VPS 미반영) | 대안(기록만): 매입 계산서를 원가로 쓰고 매입 항목을 상세로 — 기존 손익 이벤트 흐름 전체 변경이라 보류. 매입 항목 금액이 부가세 포함인지 실사용 입력 관행 확인 필요(차이를 두 기준으로 표시) |
| 수금 | 2026-10-23 **에이전트 결정(사용자 위임)**: 매출 계산서마다 입금 내역 여러 건을 기록한다. 미수금 = 공급가+세액 − 유효 입금 합계, 합계가 총액에 도달하면 `입금 완료`(중간은 `부분 입금`). 오입금은 삭제하지 않고 입금 건 취소(취소 시각·사유 기록, 합계 제외)로 정정, 초과 입금 400. 보관 현장은 입금 기록 허용·취소 409(복원 후 정정). 기존 `입금 확인`은 잔액 전액 1건 기록으로 하위 호환, 알림은 완료 시 1회. 로컬 단위·H2·MSW 회귀만 PASS, 풀 Docker·공개 VPS 실측 PENDING | 대안(기록만): 2상태 유지 + 메모 — 기성금·잔금 등 실제 공사 대금 분할 수금과 잔액 추적에 부적합. 실사용에서 입금 취소 사유 필수화·초과 입금(선수금) 허용 필요성 재검토 |
| 보증·종결 | 보증정보 수동 입력/수정 가능. 현장 상태 변경은 수동 | PDF/OCR, 만료 알림, 종결 조건 실측 |
| 현장 보관 후 기존 문서 | 보관 현장은 현장 수정과 새 견적·매입·세금계산서·보증 등록이 막힌다. 2026-10-17 **에이전트 결정(추천안, 사용자 위임)**: 기존 문서의 수정·삭제·견적 확정·보증 수정은 409로 막고 현장 복원 후 가능, 보관 뒤 늦게 들어온 **매출 입금 확인만 허용** | 대안: 입금 확인도 막기(복원 필요, 수금 기록 지연 위험) 또는 전부 허용(보관 의미 약화). 실사용에서 불편하면 재검토 |

이 표는 QA에 사용한 새 합성 ID에 한정한 관찰이다. 삭제·OCR·실제 AI 응답과 실데이터 보존은 통과로 추정하지 않는다.

2026-10-13 추가 OCR 회귀: 선택 가능한 긴 PDF 텍스트에서 보증 필드가 0개일 때 이미지 OCR 경로로 재시도하는 테스트와 전체 백엔드 테스트는 로컬 PASS다. 공개 사이트에 업로드할 원본과 무관한 단일 페이지 합성 스캔 PDF를 만들었지만, 브라우저 파일 선택 연결이 완료되지 않아 서버 업로드·실제 OCR 결과는 **PENDING**이다. 사용자 로그인/파일 선택은 직접 진행하며 비밀번호나 USB 자료를 수집하지 않는다. 혼합형 PDF의 부분 필드 병합과 native OCR의 시간·메모리 한도는 별도 미완료 항목이다.

2026-10-14 로컬 후속 회귀: OCR이 보험사·시작일·종료일 중 일부만 추출하거나 날짜가 역전되면 추출값은 남기고 `FAILED`로 표시한다. 증권번호만 선택 사항이다. 필드가 완전한 `SUCCESS`도 사용자 확인 전에는 미확정이며, 만료 임박 목록·자동 알림에서 제외한다. 사용자가 원본과 대조해 수정/확인 저장한 `MANUAL` 행만 알림 후보로 삼고 잠금 후 재검증한다. 화면에도 미확정 OCR의 만료 상태 대신 확인 필요를 표시한다. 전체 Gradle 테스트, 프론트 lint·40파일 134테스트·build 로컬 PASS(기존 대형 번들/JSdom 경고). **공개 VPS 미배포**이므로 실제 사이트의 해당 동작은 PENDING이다.

2026-10-14 추가 확인 출처 회귀(로컬, 미배포): 구버전에서 만들어진 `MANUAL` 행은 확인 시각이 없으면 `확인 필요`로 표시하고 자동 만료 알림에서 제외한다. 합성 구 행을 원본과 대조해 편집·저장하면 확인 시각을 기록하고 새 알림 후보가 된다. 구 outbox가 저장 전/후에 ACK되거나 뒤늦게 소비되어도 최초 새 알림을 7일 막거나 구 알림을 되살리지 않고, 만료 당일 구 이벤트가 PENDING이어도 새 확인 알림을 만들 수 있는 JPA 회귀를 통과했다. 실제 공개 테스트 데이터의 기존 수기 보증도 재저장 전에는 미확정으로 보일 수 있다. 배포 전 MySQL 컬럼 추가·기존 행 NULL 유지·롤백을 별도 검증하며, 사용자에게는 구 행을 무조건 일괄 확인하지 않고 원본 대조 후 한 건씩 저장하도록 안내한다. 누가 어떤 원본을 보고 확인했는지의 감사 이력은 아직 없어 실데이터 승격 불가다.

## 발견 사항 처리

사용자가 재현 정보를 주면 `.claude/BACKLOG.md` P0에 해당 화면·작업·기대/실제·영향을 등록한다. 수정은 단위 테스트 → CI → 검증된 `main` SHA → 테스트 파일럿 배포 → 동일 UI 시나리오 재현 순서로 확인한다. 특정 기능의 `PENDING`은 전체 CRUD 완료를 의미하지 않는다.
