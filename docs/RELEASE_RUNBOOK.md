# VPS 반영 런북 (develop → main → 공개 테스트 VPS)

공개 테스트 VPS를 CI 검증된 `main`으로 올리는 절차다. **테스트 데이터 전용 운영**이며 실데이터 승격(복구·마이그레이션·최소 권한 게이트)은 이 런북의 범위가 아니다. `main` 병합과 VPS 적용은 사용자 승인 후에만 진행한다.

## 이번 반영의 변경 요약 (VPS `4e56596` → 새 `main`)

- 스키마(Hibernate `ddl-auto=update`가 자동 반영, 2026-10-22 로컬 리허설 확인):
  - `buildflow_site`: `clients.archived_at`, `sites.archived_at` nullable 추가
  - `buildflow_notification`: `defect_warranties.expiry_confirmed_at`, `outbox_events.suppressed_at` nullable 추가, `outbox_events.status` enum에 `SUPPRESSED` 추가, 새 테이블 `warranty_audit_events`·`warranty_file_retentions`
  - 다른 5개 스키마는 엔티티 변경 없음. Flyway는 기본 비활성이라 실행되지 않는다
- 기존 수기 보증은 `expiry_confirmed_at`이 비어 **"확인 필요"**로 보이고 자동 만료 알림에서 빠진다. 원본과 대조해 한 번 저장하면 확인된다(의도된 동작)
- 컨테이너: 앱 이미지 `TZ=Asia/Seoul`(업무 시각 KST 통일), notification `mem_limit` 640m→896m, notification에 `JWT_SECRET`·`REDIS_HOST` 환경변수 추가, 설정 서버 주소 변수 `CONFIG_SERVER_URL`로 변경
- 시간대 전환 주의: 기존 행의 시각 값은 보정하지 않는다. 적용 순간 처리 중인 outbox·OCR `PENDING`이 있으면 재시도·`FAILED` 전환될 수 있으므로 아래 사전 점검에서 0건을 확인한다

## 다음 반영 추가 항목 — 현장 상태 `BIDDING` (2026-10-23, `develop` 미병합 시점 기록)

- `buildflow_site.sites.status` enum에 `BIDDING`(수주 전) 추가. 2026-10-23 격리 MySQL 8(무작위 이름·tmpfs)에서 V1 스키마(`enum('COMPLETED','IN_PROGRESS','SETTLING','WARRANTY')`)를 만든 뒤 새 코드를 `ddl-auto=update`로 기동하자 Hibernate가 컬럼을 `enum('BIDDING','COMPLETED','IN_PROGRESS','SETTLING','WARRANTY')`로 넓혔고 BIDDING 저장·재조회가 PASS였다(`SiteStatusLegacySchemaIntegrationTest`). 운영 기본 경로는 **수동 ALTER 불필요**.
- 반영 후 읽기 전용 확인(값 출력 없음): `SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='buildflow_site' AND TABLE_NAME='sites' AND COLUMN_NAME='status';` 결과에 `'BIDDING'`이 없으면 site-service 로그의 스키마 갱신 오류를 확인하고, 사용자 승인 후에만 V2와 같은 정의로 `ALTER TABLE sites MODIFY COLUMN status enum('BIDDING','COMPLETED','IN_PROGRESS','SETTLING','WARRANTY') COLLATE utf8mb4_unicode_ci NOT NULL;`를 적용한다(백업 후, 기존 값만 있는 행에는 무해).
- Flyway 경로(opt-in `site-flyway`)는 `V2__site_status_bidding.sql`이 같은 정의를 적용한다. 기존 DB를 baseline(1)하면 V2가 이어서 실행되며, 이미 BIDDING이 있는 컬럼에도 같은 정의라 무해하다(격리 실측).
- **되돌리기 주의**: 이전 코드는 기동은 되지만(넓어진 enum을 줄이지 않음) BIDDING 행을 읽으면 `No enum constant ...SiteStatus.BIDDING`으로 현장 목록·대시보드가 실패한다(2026-10-23 격리 실측). 이전 이미지로 되돌리기 전에 `SELECT id FROM buildflow_site.sites WHERE status='BIDDING';`로 대상 ID를 기록하고, 사용자 승인 후 `UPDATE ... SET status='IN_PROGRESS' WHERE status='BIDDING'`로 낮춘다. 재반영 시 기록한 ID를 BIDDING으로 되돌린다.

## 다음 반영 추가 항목 — 세금계산서 분할 입금 `tax_payments` (2026-10-23)

- `buildflow_tax`에 새 테이블 `tax_payments`가 `ddl-auto=update`로 생기고, tax-service 기동 시 `LegacyPaymentLedgerBackfill`이 기존 `payment_confirmed=1`·총액>0 매출 계산서마다 총액 1건(메모 "기존 입금 확인 이관")을 이관한다. 수동 DDL·SQL 불필요. 2026-10-23 업그레이드 리허설(`scripts/db/upgrade-rehearsal-tax-site.ps1`)에서 이관 1건·재기동 중복 0건을 확인했다.
- 반영 후 읽기 전용 확인(값 출력 없음, 새 입금 기록 전): `SELECT COUNT(*) FROM buildflow_tax.tax_invoices WHERE payment_confirmed=1 AND total_amount>0;`와 `SELECT COUNT(DISTINCT tax_invoice_id) FROM buildflow_tax.tax_payments;`가 같은지.
- **되돌리기 주의**: 이전 코드는 `tax_payments`를 무시하므로 부분 입금이 미수금에 반영되지 않는다. 되돌린 상태에서 부분 입금된 계산서에 "입금 확인"을 누른 뒤 재반영하면 그 계산서는 `PAID`로 보이지만 입금액은 부분 합계만 잡힌다(이관은 입금 행이 이미 있는 계산서를 건너뜀, 리허설 WARN). 되돌림 기간에는 부분 입금 계산서의 입금 확인을 피하고, 발생했다면 재반영 후 잔액을 입금 기록으로 추가하면 정상화된다(실측). **2026-10-23 수정**: 재반영 시 기동 이관이 "입금 확인인데 유효 입금 합계 < 총액"인 계산서의 잔액을 `기존 입금 확인 이관` 1건으로 채우고, 이관 전에도 입금 확인 건은 총액 입금으로 표시한다(단위 회귀 추가). 되돌린 동안의 입금 확인도 재반영 후 정합하다.

## 다음 반영 추가 항목 — 견적 수정본·추가공사 (PR #201, 2026-10-23)

- `buildflow_estimate.estimates`에 nullable `kind`·`revision_of_id`·`chain_root_id`(+`idx_estimates_chain_root`)·`superseded_by_id`가 생기고 `status` enum에 `SUPERSEDED`가 추가된다. 2026-10-23 업그레이드 리허설(`scripts/db/upgrade-rehearsal-estimate.ps1`, 이전 `0fe8c9a`·새 `c2416ef`, 격리 Kafka 포함)에서 이전 코드가 만든 `enum('CONFIRMED','DRAFT')`를 새 코드 `ddl-auto=update`가 `SHOW CREATE TABLE` 기준 `enum('CONFIRMED','DRAFT','SUPERSEDED') ... NOT NULL`로 넓혔고 수정본 확정 200(1265 Data truncated 없음)을 확인했다. 운영 기본 경로는 **수동 ALTER 불필요**.
- 반영 후 읽기 전용 확인(값 출력 없음): `SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='buildflow_estimate' AND TABLE_NAME='estimates' AND COLUMN_NAME='status';`에 `'SUPERSEDED'`가 없으면 수정본 확정이 실패하므로 estimate-service 로그를 확인하고, 사용자 승인·백업 후 V2와 같은 정의 `ALTER TABLE estimates MODIFY COLUMN status enum('CONFIRMED','DRAFT','SUPERSEDED') COLLATE utf8mb4_unicode_ci NOT NULL;`를 적용한다.
- **되돌리기 주의**: 이전 코드는 넓어진 스키마에서 기동하지만(enum을 줄이지 않음) `SUPERSEDED` 행이 하나라도 있으면 견적 전체 목록·현장별 목록·그 견적 조회가 500이다(확정된 `REVISION`·`ADDITIONAL` 행은 이전 코드가 일반 확정 견적으로 읽음, 리허설 실측). 또 초안 `REVISION`을 이전 코드로 확정하면 기존 확정본을 대체하지 않고 손익에 이중 합산된다. 되돌리기 전에 앱 정지 상태에서 사용자 승인 후 아래로 두 종류 행을 보관 테이블로 옮긴다(손익 `site_profits`는 건드리지 않음 — 대체된 행은 이미 손익에서 빠져 있음):

```sql
USE buildflow_estimate;
START TRANSACTION;
CREATE TABLE rollback_hold_estimates LIKE estimates;
CREATE TABLE rollback_hold_estimate_items LIKE estimate_items;
INSERT INTO rollback_hold_estimates SELECT * FROM estimates WHERE status='SUPERSEDED' OR (kind='REVISION' AND status='DRAFT');
INSERT INTO rollback_hold_estimate_items SELECT i.* FROM estimate_items i JOIN rollback_hold_estimates h ON h.id = i.estimate_id;
DELETE i FROM estimate_items i JOIN rollback_hold_estimates h ON h.id = i.estimate_id;
DELETE e FROM estimates e JOIN rollback_hold_estimates h ON h.id = e.id;
COMMIT;
```

  재반영 시 새 코드 기동 **전에** 되돌린다: `INSERT INTO estimates SELECT * FROM rollback_hold_estimates; INSERT INTO estimate_items SELECT * FROM rollback_hold_estimate_items;` 후 두 보관 테이블을 `DROP`. AUTO_INCREMENT는 줄지 않으므로 되돌림 기간에 생긴 견적과 id가 겹치지 않는다(리허설: 보관 2건 → 이전 코드 목록 200·손익 150만 유지, 재반영 후 6건 조회·보관했던 초안 수정본 확정으로 160만 수렴).

## SSH 배포 경로 (일회성 root 설정 후)

PC의 `buildflow-deploy` SSH 키로 접속한 에이전트가 배포할 수 있도록, root 소유 래퍼 하나만 sudo로 허용한다. 래퍼는 CI 검증된 `origin/main`과 정확히 같은 40자리 SHA만 배포하고, 아래 0~5단계(처리 중 작업 0건, 이미지 보존, 순차 빌드, 백업·격리 복원 확인, 기동)를 그대로 실행한다. 임의 명령은 받지 않는다. 일회성 설정은 Web Console root에서 한다.

```bash
cd /opt/buildflow && git fetch origin main:refs/remotes/origin/main
chown -R root:root /opt/buildflow && chmod -R go-w /opt/buildflow   # 래퍼가 소유권·쓰기 권한을 검사한다
id buildflow-deploy   # docker 그룹이 없어야 한다(있으면 sudo 제한이 무의미)
git show origin/main:scripts/vps/root/buildflow-release > /usr/local/sbin/buildflow-release   # CI 검증된 main 기준
chown root:root /usr/local/sbin/buildflow-release && chmod 755 /usr/local/sbin/buildflow-release
install -d -m 700 /etc/buildflow && printf '%s
' "실제-공개-호스트명" > /etc/buildflow/public_host && chmod 600 /etc/buildflow/public_host
printf '%s
' 'buildflow-deploy ALL=(root) NOPASSWD: /usr/local/sbin/buildflow-release' > /etc/sudoers.d/buildflow-release
chmod 440 /etc/sudoers.d/buildflow-release && visudo -cf /etc/sudoers.d/buildflow-release
```

래퍼를 갱신할 때도 같은 명령으로 `origin/main`에서 다시 설치한다(2026-10-23 보안 검토: 설치 원본을 develop에서 main으로, 저장소 root 소유·git 훅 비활성 검사 추가). 2026-10-23 비root 실측: `/opt/buildflow`·`.git`·`.git/config`·`.git/hooks` 모두 `root:root`, deploy 계정 쓰기 가능 파일 0, deploy 계정 그룹에 docker 없음.

이후 PC에서: `ssh buildflow-deploy@<서버> sudo buildflow-release status | schema | deploy <main SHA> | rollback <이전 SHA>`. 접속 시 호스트 키는 2026-10-22 확인한 ED25519 `SHA256:7bqzBJZH/AYX6h8HYLuhDR/0+vUjcFqqo8fUWLJ2zCs`와 일치해야 한다(PC `known_hosts`의 호스트명 항목은 재설치 전 키라 IP 항목으로 접속한다).

### 운영 DDL 받기 (`schema`, 읽기 전용 — Flyway baseline 게이트용)

`schema` 모드는 `docker exec buildflow-mysql`로 하드코딩한 7개 업무 스키마(`buildflow_auth/chat/estimate/purchase/tax/site/notification`)의 테이블 목록을 `information_schema.TABLES`에서 얻어 `SHOW CREATE TABLE`만 stdout으로 출력한다. `AUTO_INCREMENT=N`은 지우고, 데이터 행·사용자 계정·비밀값은 조회하지 않으며 쓰기·재기동도 없다. 출력은 앞에 `-- schema:`/`-- table:` 표식이 붙은 텍스트다.

```bash
# PC(Git Bash 또는 cmd)에서 — 출력은 테이블 구조뿐이지만 저장소 밖 개인 폴더에 둔다
ssh buildflow-deploy@<서버 IP> sudo buildflow-release schema > prod-ddl.sql
# 대조 (Docker 불필요: 직전 리허설 출력 폴더의 <service>-v1.sql 사용)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/db/flyway-baseline-rehearsal.ps1 -ProdDdlPath prod-ddl.sql -V1DdlDirectory <리허설 출력 폴더>
# 또는 V1 스키마를 새로 만들며 같이 대조: -ProdDdlPath prod-ddl.sql 만 추가
```

Windows PowerShell 5.1의 `>`는 UTF-16으로 저장하지만 비교 스크립트는 BOM을 인식하므로 그대로 써도 된다. **`schema` 모드는 2026-10-23 추가분이라, 서버의 `/usr/local/sbin/buildflow-release`가 이 변경이 `main`에 병합된 뒤 root가 위 설치 명령으로 다시 설치하기 전에는 없다**(래퍼는 root 소유라 배포 계정·에이전트가 갱신할 수 없다). 결과 해석과 차이 처리 원칙은 `docs/DATABASE_OPERATIONS.md` "기존 DB baseline 리허설"을 따른다.

## 0. 사전 조건

- `develop`→`main` PR(#99) 필수 CI 통과·SHA 대조 후 **사용자 승인으로 병합**된 `main` SHA
- 서버 Web Console root 세션, 작업 디렉터리 `/opt/buildflow`, 작업 트리 깨끗함
- 아래 명령의 `PUBLIC_HOST` 값은 Git 제외 운영 기록의 실제 호스트명으로 치환한다. `.env` 값은 출력하지 않는다

```bash
cd /opt/buildflow
export PUBLIC_HOST="your-vps-hostname.example"
C="docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml -f docker-compose.public.yml"
git status --short --branch                      # 출력이 브랜치 줄뿐이어야 한다
OLD_SHA=$(git rev-parse HEAD); echo "$OLD_SHA"    # 되돌리기 기준. 기록해 둔다
```

## 1. 처리 중 작업 0건 확인 (읽기 전용)

```bash
docker exec buildflow-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "
SELECT \"notification outbox 미완료\", COUNT(*) FROM buildflow_notification.outbox_events WHERE status IN (\"PENDING\",\"CLAIMED\");
SELECT \"OCR 처리 중\", COUNT(*) FROM buildflow_notification.defect_warranties WHERE ocr_status = \"PENDING\";
SELECT \"estimate outbox 미완료\", COUNT(*) FROM buildflow_estimate.outbox_events WHERE status IN (\"PENDING\",\"CLAIMED\");
SELECT \"purchase outbox 미완료\", COUNT(*) FROM buildflow_purchase.outbox_events WHERE status IN (\"PENDING\",\"CLAIMED\");
SELECT \"tax outbox 미완료\", COUNT(*) FROM buildflow_tax.outbox_events WHERE status IN (\"PENDING\",\"CLAIMED\");" 2>/dev/null'
```

모두 0이 아니면 몇 분 기다려 다시 확인한다. 계속 남으면 중단하고 원인을 기록한다.

## 2. 새 코드로 이미지 빌드 (서비스 중단 없음)

```bash
for img in $(docker ps --filter name=buildflow- --format '{{.Image}}' | sort -u); do
  docker image tag "$img" "${img%%:*}:pre-${OLD_SHA:0:7}"      # 되돌리기용 현재 이미지 보존
done
git fetch origin main:refs/remotes/origin/main
git switch --detach origin/main
test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" && git rev-parse HEAD
$C config --quiet
$C config --format json | python3 scripts/vps/verify_public_compose.py
bash scripts/vps/build_images.sh                    # 한 서비스씩 순차 빌드, 기존 컨테이너는 계속 동작
```

빌드 실패 시 `git switch --detach "$OLD_SHA"`로 돌아가고 중단한다(운영 컨테이너는 바뀌지 않았다).

## 3. 앱 정지 → 백업·격리 복원 확인

```bash
$C stop frontend gateway-server auth-service site-service estimate-service purchase-service tax-service notification-service chat-service
install -d -m 700 /root/buildflow-backups
bash scripts/vps/predeploy_backup.sh /root/buildflow-backups
```

마지막 줄이 `완료:`이고 `복원 행 수 일치`가 출력돼야 한다. 실패하면 **4단계로 가지 않고** `git switch --detach "$OLD_SHA"` 후 `$C start ...`(위와 같은 서비스)로 원래 상태를 되살린다. 백업 폴더(`buildflow.sql`·`warranty_uploads.tar`)는 민감 자료이므로 서버 밖 암호화 저장소로 옮긴다.

## 4. 새 컨테이너 기동

```bash
$C up -d --no-build
$C ps
```

## 5. 확인

```bash
$C logs --since=5m site-service notification-service | grep -iE "error|exception|alter table|create table" | head -40
docker exec buildflow-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "
SHOW COLUMNS FROM buildflow_site.sites LIKE \"archived_at\";
SHOW COLUMNS FROM buildflow_notification.outbox_events LIKE \"status\";
SHOW TABLES FROM buildflow_notification LIKE \"warranty_%\";" 2>/dev/null'
docker exec buildflow-site sh -c 'echo TZ=$TZ; date'
curl -s -o /dev/null -w '%{http_code}\n' "https://$PUBLIC_HOST/"                 # 200
curl -s -o /dev/null -w '%{http_code}\n' "https://$PUBLIC_HOST/api/v1/sites"     # 401
```

그다음 사용자가 브라우저로 로그인해 현장 목록·상세, 보증 목록(기존 수기 보증의 "확인 필요" 표시), 알림 시각, 합성 `[QA]` 현장 생성→보관을 확인한다. 결과는 `docs/SECURITY_OPERATIONS.md` S01·S10과 `.claude/PROGRESS.md`에 SHA와 함께 기록한다.

## 되돌리기

스키마 변경은 모두 추가(nullable 컬럼·새 테이블·enum 값 추가)라 이전 코드가 그대로 읽는다. 일반적으로 DB 복원 없이 코드·이미지만 되돌린다. 단, 새 enum 값을 쓴 행이 있으면 이전 코드가 그 행을 읽지 못한다 — 현장 `BIDDING`은 위 "다음 반영 추가 항목"의 되돌리기 주의를 먼저 수행하고, 세금계산서 분할 입금은 같은 절의 되돌리기 주의를 따른다. 견적 `SUPERSEDED`·초안 `REVISION` 행은 "견적 수정본·추가공사" 절의 보관 테이블 절차를 먼저 수행한다.

```bash
git switch --detach "$OLD_SHA"
for img in $(docker images --format '{{.Repository}}:{{.Tag}}' | grep ":pre-${OLD_SHA:0:7}$"); do
  docker image tag "$img" "${img%%:*}:latest"
done
$C up -d --no-build
```

새 코드가 데이터를 손상시킨 경우에만 3단계 백업으로 DB를 복원한다. 운영 volume 복원은 되돌리기 어려운 작업이므로 별도 승인 후 `docs/DATABASE_OPERATIONS.md` 원칙(격리 복원으로 먼저 확인)에 따라 진행한다. `down -v`나 volume 삭제는 사용하지 않는다.
