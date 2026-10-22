# BuildFlow 데이터베이스 운영 가이드

## 목적

BuildFlow의 7개 MySQL 스키마와 단일 관리자 계정을 실데이터 손실 없이 보존하고, 향후 Flyway 기반 버전 마이그레이션으로 안전하게 전환하기 위한 기준이다.

## 현재 스키마 정책

| 서비스 | 스키마 | 현재 생성 정책 |
|---|---|---|
| auth-service | `buildflow_auth` | `schema.sql` + Hibernate `validate` |
| estimate-service | `buildflow_estimate` | Hibernate `update` |
| site-service | `buildflow_site` | Hibernate `update` |
| purchase-service | `buildflow_purchase` | Hibernate `update` |
| tax-service | `buildflow_tax` | Hibernate `update` |
| notification-service | `buildflow_notification` | Hibernate `update` |
| chat-service | `buildflow_chat` | Hibernate `update` |

`docker/mysql/init/01_create_databases.sql`은 빈 MySQL volume의 최초 초기화에서 스키마만 만든다. 기존 volume의 변경 이력을 관리하지 않으므로 마이그레이션 도구를 대신하지 않는다.

## 백업

Docker Engine과 `buildflow-mysql`이 정상일 때 저장소 루트에서 실행한다. 일관된 시점을 확보하려면 먼저 전체 Compose를 정상 종료하고 MySQL만 다시 시작한다. 로컬 `bootRun` 등 DB에 쓰는 별도 프로세스도 종료해야 한다. 백업 스크립트는 Compose의 DB 쓰기 서비스가 실행 중이면 중단한다.

```powershell
.\scripts\buildflow.ps1 down
docker compose up -d mysql
.\scripts\db\backup.ps1
```

기본 산출물은 Git에서 제외된 `backups/db/<UTC timestamp>/` 아래에 생성된다. SQL dump는 실제 업무 데이터와 관리자 BCrypt hash를 포함하는 **민감한 평문 백업**이다. Git, 공유 폴더, 일반 클라우드 동기화 폴더에 올리지 말고 BitLocker가 적용된 PC 또는 암호화된 외장 저장소에서 현재 Windows 사용자만 읽을 수 있도록 보관한다. 보존 기한이 지난 복사본은 복구 불가능하게 폐기한다.

- `buildflow.sql`: 7개 스키마의 논리 덤프
- `manifest.json`: 덤프 SHA-256, 원본 컨테이너·Compose project·named volume 신원, MySQL 버전, 스키마별 테이블/정확한 행 수, Flyway history 존재 여부, 관리자 계정 수와 비노출 digest

현재 `backup.ps1`의 `formatVersion: 1`은 **SQL 전용**이다. 보증보험 PDF가 저장되는 `warranty_uploads` volume, Kafka broker 상태는 포함하지 않는다. 따라서 이 도구의 성공이나 아래 SQL 격리 복원 성공만으로 VPS 실데이터 운영/완전 복구 게이트를 통과했다고 판정하지 않는다. 업로드 volume은 앱의 `/app/uploads/warranties`에 연결되고 DB `defect_warranties.file_path`는 그 절대경로를 저장한다.

`-IncludeWarrantyUploads -OutputRoot <이미 존재하는 비공개 디렉터리>`는 SQL과 보증 파일을 함께 읽는 **진단용 v2 snapshot**이다. Linux 실행 경로는 현재 사용자 소유의 `0700` 출력 디렉터리, 시작 시점의 `umask 0077`(새 PowerShell 프로세스를 띄우기 전에 설정), 생성 파일 `0600`, 링크·교체 가능한 공유 쓰기 조상이 없는 경로를 요구한다. Docker 복사 helper도 호스트 실행 UID/GID로 동작하므로 원본 volume을 그 계정으로 읽을 수 없으면 중단한다. 출력은 언제나 `INCOMPLETE`이며 일반 검증·운영 복원으로 승격되지 않는다. 실제 Ubuntu 호스트의 UID/ACL·Docker volume 접근, 외부 writer 차단, 격리 복원과 관리자 로그인은 별도 실측 전까지 `PENDING/UNVERIFIED`다. 이 경로를 사용해도 USB 원본이나 운영 volume을 정리하지 않는다.

스크립트는 컨테이너 내부 `MYSQL_ROOT_PASSWORD`를 자식 프로세스 환경으로만 전달하고 값을 명령행, manifest, 로그에 기록하지 않는다. `mysqldump`는 InnoDB 일관성 확보를 위한 `--single-transaction`, 큰 테이블 스트리밍을 위한 `--quick`, stored object 보존을 위한 `--routines --triggers --events`, 불필요한 tablespace 권한을 피하는 `--no-tablespaces`를 사용한다.

백업 후 또는 다른 저장 장치로 복사한 뒤에는 Docker 없이 다음 검증을 실행한다.

```powershell
.\scripts\db\verify-backup.ps1 -BackupDirectory .\backups\db\<UTC timestamp>
```

성공 조건은 dump가 비어 있지 않고, manifest의 SHA-256과 일치하며, 7개 스키마·테이블 정의·단일 관리자 행·mysqldump 완료 footer가 manifest와 일치하는 것이다. 이 검증은 파일 손상 여부를 확인하지만 실제 복원 성공을 대신하지 않으며, dump와 manifest를 함께 바꾸는 공격을 막는 전자서명도 아니다.

차기 통합 패키지를 위한 `verify-warranty-files.ps1`은 파일만 검사하는 준비 도구다. 고정 경로 `uploads/warranties` 아래 일반 파일의 바이트 수·SHA-256, manifest 내부의 참조 ID, SQL과 같은 Compose project의 별도 volume 신원을 확인한다. 파일 누락·추가·변조, 중복/위험 상대경로, 링크·하위 디렉터리, manifest에 적힌 파일이 없는 참조는 실패한다. **이 검사만으로는 SQL의 실제 `defect_warranties.file_path`와 대조하지 못한다.**

`verify-warranty-references.ps1 -BackupDirectory <v2 패키지> -RestoredDbRowsPath <격리 복원 DB 행 JSON>`은 두 번째 오프라인 준비 도구다. JSON 배열의 각 행은 `warrantyId`와 `filePath`(파일 없는 수동 행은 `null`)를 포함해야 한다. 파일 경로가 있는 DB 행과 manifest의 ID·상대경로를 양방향 대조하고 전체 행 수를 `defect_warranties` inventory와 비교하며, 패키지 파일의 SHA-256 검사도 먼저 수행한다. 합성 정상·누락·불일치·중복·미완료 패키지 거부 회귀를 통과했다. **JSON을 실제 격리 복원한 DB에서 추출했는지 이 도구 자체는 증명하지 못한다.** 원본 DB의 데이터가 아니라 격리 복원 DB에서 읽기 전용으로 추출한 행 수·출처·시각을 작업 기록에 남겨야 한다. 행 JSON에는 실제 파일 경로가 있으므로 백업과 동일한 접근 통제를 적용하고 Git에 넣지 않는다. 이 검사 성공도 복구 완료 판정이 아니다.

`verify-backup.ps1`과 `restore-test.ps1`은 `formatVersion: 2`를 계속 명시적으로 거부한다. 일관된 v2 백업 생성·격리 복원·실제 DB 추출을 연결하고 관리자 로그인과 이벤트/OCR 재조정까지 검증하기 전에는 운영 백업 성공으로 기록하지 않는다.

SQL 전용 v1 백업의 격리 복원에서는 `-WarrantyRowsExportPath`에 **절대 경로**를 지정하면 복원된 `defect_warranties`의 ID·파일 경로를 JSON 배열로 추출할 수 있다. 경로의 부모 디렉터리는 미리 만들어 두고 현재 사용자만 읽을 수 있도록 제한해야 한다. 원본 백업 디렉터리나 Git 추적 폴더에는 쓰지 않으며 기존 파일은 덮어쓰지 않는다. `<경로>.evidence.json`에는 덤프·manifest·행 JSON SHA-256과 격리 복원 컨테이너/volume/nonce를 기록한다. 실제 경로 값은 콘솔에 출력하지 않는다. 이 메타데이터는 전자서명이 아니며 v1의 업로드 파일이 복원되지 않았으므로 **부분 진단 증거**일 뿐이다. v2 통합 복원 거부와 실데이터 NO-GO는 그대로 유지된다.

통합 백업 구현 전 추가 중단 조건도 있다. 메모리 기반 비동기 OCR의 `PENDING` 행은 중지 후 자동 복구된다고 보장할 수 없다. Kafka outbox의 `SENT`는 broker 수락일 뿐 소비 완료가 아니므로 SQL+PDF만 복원하면 미소비 이벤트가 유실될 수 있다. OCR 대기 0건과 이벤트 drain/재조정 근거가 마련되기 전 전체 업무 복구 판정은 `PENDING`이다. 외부 물리 저장장치에 암호화된 복사본을 보관하고, 별도 빈 환경에서 DB와 파일을 같은 컨테이너 경로에 복원해 참조/해시/로그인을 확인해야 한다.

## 격리 복원 검증

검증된 백업을 전용 Compose project, 임시 MySQL volume, 자동 생성 네트워크에 복원한다. 기존 Compose 파일은 `buildflow-mysql`과 `buildflow-net` 이름이 고정돼 있으므로 복원 시험에 재사용하지 않는다.

```powershell
.\scripts\db\restore-test.ps1 -BackupDirectory .\backups\db\<UTC timestamp>
```

이 명령은 다음을 자동 확인한 뒤 임시 환경을 제거한다.

- 복원 컨테이너·volume의 project/nonce label과 원본 컨테이너·volume 비일치
- manifest와 정확히 일치하는 7개 스키마·테이블·행 수·Flyway history 유무
- 모든 테이블의 `CHECK TABLE ... status OK`
- 관리자 계정 수와 비노출 digest 일치
- 복원된 인증 스키마에서 SQL 초기화를 끄고 Hibernate `ddl-auto=validate`로 auth-service 기동 및 health `UP`

관리자 자격 증명까지 확인하는 최종 로그인·로그아웃 검증은 값을 파일·명령행에 남기지 않는 대화형 옵션으로 수행한다.

```powershell
.\scripts\db\restore-test.ps1 -BackupDirectory .\backups\db\<UTC timestamp> -ValidateLogin
```

필요할 때만 보호된 로컬 경로로 복원 DB 참조를 추출한다. 기존 행 JSON·증거 파일은 보존하고 새 경로를 사용한다.

```powershell
.\scripts\db\restore-test.ps1 -BackupDirectory .\backups\db\<UTC timestamp> -ValidateLogin -WarrantyRowsExportPath C:\protected\restore-evidence\warranty-rows.json
```

### 복원 검증 원칙

복원 시험은 위 스크립트로 자동화하며 아래 규칙을 예외 없이 적용한다.

1. 실행 중인 `buildflow-mysql` 컨테이너와 현재 `mysql_data` named volume에 복원하지 않는다.
2. 별도 Compose project 이름과 새 임시 named volume을 사용한다.
3. 복원 전에 대상 컨테이너 ID와 mount 이름이 현재 환경과 다른지 확인한다.
4. 복원 후 `CHECK TABLE`, 스키마/테이블별 정확한 행 수, 관리자 계정 수와 digest를 원본 manifest와 비교한다.
5. 관리자 비밀번호 hash 자체는 화면이나 파일에 출력하지 않는다.
6. 인증 로그인 및 각 서비스의 `ddl-auto: validate` 기동 확인 후에만 백업을 복구 가능으로 판정한다.
7. 임시 환경 제거는 대상 project와 volume의 절대 이름을 재확인한 뒤 수행한다. 현재 BuildFlow volume은 삭제하지 않는다.

## Flyway 전환 원칙

- 실제 V1 baseline은 Entity 추정이 아니라 검증된 실 DB의 `SHOW CREATE TABLE`과 no-data dump를 기준으로 만든다.
- 기존 비어 있지 않은 스키마는 백업·격리 복원 성공 후 명시적인 일회성 `baseline`으로만 등록한다.
- `baseline-on-migrate=true`를 상시 설정하지 않는다. 잘못 지정한 비어 있지 않은 DB를 정상 기준선으로 오인할 안전망 손실 위험이 있다.
- Flyway와 Hibernate `ddl-auto: update`를 같은 서비스에서 동시에 활성화하지 않는다.
- Flyway `clean`은 비활성화한다.
- MySQL 지원 모듈은 Flyway core와 별도 의존성이므로 파일럿에서 함께 추가한다.
- MySQL DDL은 암묵적 commit이 발생할 수 있으므로 실패 복구는 rollback 가정이 아니라 백업 복원 또는 새 forward migration으로 처리한다.

전환 순서는 auth-service 파일럿 → chat/estimate → purchase/tax → site/notification이다. 모든 서비스 전환 후 runtime DB 계정을 root에서 최소 권한 계정으로 분리한다.

### auth-service opt-in Flyway 파일럿

`auth-service`에는 검증된 2026-09-29 MySQL no-data dump의 `admin_accounts` DDL을 기준으로 한 `V1__admin_accounts.sql`이 있다. 이는 **빈 스키마용 생성 마이그레이션**이며 관리자 데이터는 넣지 않는다. 기본 설정은 `spring.flyway.enabled=false`라서 기존 로컬·VPS 실행의 `schema.sql` 경로가 유지된다. 별도 `auth-flyway` 프로파일에서만 SQL 초기화를 끄고 Flyway를 켜며 JPA `validate`를 유지한다. 기존 DB나 VPS에서는 아직 이 프로파일을 사용하지 않는다.

CI의 `인증 DB (Flyway · MySQL 8)` job은 임시 MySQL 8의 빈 `buildflow_auth_flyway_test` 스키마에 V1을 적용하고 history 1건, 실제 UNIQUE/CHECK/collation, 관리자 0건, Hibernate `validate`, 재실행 시 migration 0건을 확인한다. 테스트는 전용 플래그와 `127.0.0.1`의 `buildflow_auth_flyway_test` JDBC URL이 모두 일치할 때만 Spring 컨텍스트를 시작한다. 로컬 일반 `:auth-service:test`에서는 DB 테스트가 건너뛰어지므로 이 CI job의 성공을 별도로 확인한다.

기존 데이터가 있는 스키마는 V1을 그대로 실행하거나 자동 baseline하지 않는다. Phase B의 관리자 로그인 검증과 원본/복원 DB의 실제 `SHOW CREATE TABLE` 대조가 끝난 뒤, **격리 복원 환경**에서 일회성 `baseline(version=1)`·`migrate`·로그인/로그아웃·행/제약 불변을 먼저 검증한다. 그 전에는 운영 프로파일 전환, `schema.sql` 제거 또는 VPS 배포를 하지 않는다. MySQL DDL 실패는 전체 트랜잭션 롤백으로 복구된다고 가정하지 않는다.

### 기존 DB baseline 리허설 (ddl-auto 스키마 ↔ V1 정합)

`scripts/db/flyway-baseline-rehearsal.ps1`은 "운영과 같은 방식으로 만든 스키마"에 `baseline(version=1)`을 걸어도 안전한지 격리 환경에서 확인한다. Windows PowerShell 5.1에서 실행하며 pwsh 전용 문법은 쓰지 않는다.

```powershell
.\gradlew.bat :auth-service:bootJar :chat-service:bootJar :estimate-service:bootJar :purchase-service:bootJar :tax-service:bootJar :site-service:bootJar :notification-service:bootJar -x test
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/db/flyway-baseline-rehearsal.ps1   # -BuildJars, -Services tax 등 선택
```

절차:

1. 무작위 Compose project `bfbase<난수>`에 MySQL 8 하나만 띄운다(`flyway-baseline-rehearsal.compose.yml`). 호스트 포트를 게시하지 않고 데이터는 tmpfs라 named/anonymous volume이 생기지 않으며, 컨테이너·네트워크에 실행별 nonce label을 붙인다. 기존 `buildflow` project·볼륨·이미지는 건드리지 않는다.
2. 스키마 A(`buildflow_<svc>_legacy`): 로컬 bootJar를 `eclipse-temurin:17-jre-jammy` 컨테이너에 읽기 전용으로 마운트해 **기본 프로파일**(ddl-auto `update`, auth는 `schema.sql` + `validate`)로 기동한다. Eureka·Config·Kafka 리스너·토픽 생성은 끄고 DB만 실제로 쓴다.
3. 스키마 B(`buildflow_<svc>_v1`): `<svc>-flyway` 프로파일로 기동해 V1 적용 + Hibernate `validate`.
4. 비교: 정규화한 `SHOW CREATE TABLE`(`AUTO_INCREMENT=n` 제거, 아래 charset 표기 정규화)의 줄 단위 비교와 `information_schema`의 테이블 엔진·collation, 컬럼(타입·NULL·기본값·extra·collation), 인덱스, FK(참조·규칙), CHECK 제약 집합 비교. 컬럼 순서만 다른 테이블은 무해로 따로 표시한다.
5. 차이가 0인 서비스만 A에 일회성 `--spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=1`을 **명령행 인자로 한 번만** 주어 baseline하고, 이어서 일반 `<svc>-flyway` 프로파일(상시 `baseline-on-migrate=false`)로 재기동해 "up to date / No migration necessary"·기동 성공·history가 정확히 `1|1|BASELINE|1`(B는 `1|1|SQL|1`)인지 확인한다.
6. 종료 시 nonce label을 재확인한 앱 컨테이너만 `stop`·`rm`하고, project 이름(`^bfbase\d+$`)과 label이 맞을 때만 `docker compose down`(`-v` 없음)한 뒤 남은 컨테이너·네트워크·볼륨 0개를 확인한다. 로그·정규화 DDL·`report.txt`·`summary.json`은 `%TEMP%\buildflow-flyway-rehearsal-<nonce>`에 남는다.

**2026-10-23 결과 (develop `ade9577`, MySQL 8.0.46) — 7개 서비스 ALL PASSED**

| 서비스 | 테이블 | 의미 차이 | DDL 줄 차이 | 컬럼 순서만 다름 | baseline → migrate 0 + validate |
|---|---|---|---|---|---|
| auth | 1 | 0 | 0 | - | PASS |
| chat | 2 | 0 | 0 | - | PASS |
| estimate | 3 | 0 | 0 | - | PASS |
| purchase | 2 | 0 | 0 | - | PASS |
| tax | 2 | 0 | 0 | - | PASS |
| site | 5 | 0 | 0 | `clients`, `sites` | PASS |
| notification | 6 | 0 | 0 | `defect_warranties`, `outbox_events` | PASS |

무해로 판정한 정규화 차이:

- **charset 표기**: V1처럼 컬럼에 `COLLATE`를 명시해 만든 테이블은 `SHOW CREATE TABLE`이 `CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`로 출력하고, Hibernate가 만든 테이블은 `COLLATE utf8mb4_unicode_ci`만 출력한다(B쪽 74개 컬럼). `information_schema`의 charset·collation은 동일하므로 데이터 사전 표기 차이다.
- **컬럼 순서**: 빈 DB에서 Hibernate는 `id` 뒤 알파벳 순으로 만들지만, V1은 나중에 `ddl-auto update`가 끝에 덧붙인 컬럼(`clients/sites.archived_at`, `defect_warranties.expiry_confirmed_at`, `outbox_events.suppressed_at`)이 마지막에 있는 로컬 DB에서 떠 왔다. 운영 DB도 같은 이력이라면 V1 순서가 더 가깝다. 컬럼 순서는 Flyway baseline·Hibernate validate에 영향이 없다.

한계와 남은 위험:

- 이 리허설의 A는 **현재 코드로 빈 DB에서 새로 만든** ddl-auto 스키마다. 운영 VPS 스키마는 과거 엔티티 이력 위에 `update`가 누적된 결과이며, `update`는 컬럼 타입 변경·NOT NULL/UNIQUE 추가·컬럼 삭제를 반영하지 않으므로 운영에만 남은 오래된 컬럼·제약이 있을 수 있다. 에이전트는 VPS DB 접근 권한이 없어 **운영 실제 `SHOW CREATE TABLE`과는 대조하지 못했다.** (2026-10-22 배포 전 백업 덤프는 19개 테이블, 현재 코드 기준은 21개 — 배포 후 `update`로 생긴 테이블이 있는지 미확인.)
- 따라서 운영 baseline 전 필수 게이트: 운영 백업(no-data 포함) → 격리 복원 → 복원 스키마와 V1 스키마를 같은 기준(정규화 `SHOW CREATE TABLE` + `information_schema` 집합)으로 비교. 차이가 나오면 V1을 운영에 맞춰 고치지 않고(V1은 이미 신규 DB 기준으로 통합됨), **추천안: baseline 전에 운영 쪽을 V1과 같게 만드는 수동 정렬 SQL을 격리 복원본에서 먼저 검증한 뒤 운영에 적용하고 baseline(version=1)** 한다. V2 정합 마이그레이션은 신규 DB(V1)에도 같은 변경을 강제해 V1·V2 의미가 꼬이므로 운영 전용 잔여물 정리에는 쓰지 않는다. 운영 백업 복원과 이 비교는 사용자 승인된 VPS 작업 경로로만 수행한다.
- CI 미추가: 7개 bootJar 빌드와 JVM 28회 기동이 필요해 무겁고, 서비스별 빈 DB V1+validate는 기존 `*-flyway` CI job이 이미 검증한다. 이 리허설은 엔티티·V1 변경 시와 운영 baseline 직전에 수동으로 다시 돌리는 일회성 게이트로 둔다.
- 2026-10-23 site `V2__site_status_bidding.sql`(`sites.status` enum에 `BIDDING`): 엔티티 변경은 V1을 고치지 않고 forward 마이그레이션으로 둔다. 리허설 스크립트의 기대 history는 마이그레이션 파일에서 계산한다(legacy `1|1|BASELINE|1;2|2|SQL|1`, 신규 `1|1|SQL|1;2|2|SQL|1`). 2026-10-23 격리 MySQL에서 신규 V1+V2+validate(`SiteFlywayIntegrationTest`)와 V1 스키마→`ddl-auto=update` enum 확장·BIDDING 저장(`SiteStatusLegacySchemaIntegrationTest`), BIDDING 존재 컬럼에 V2 재적용 무해를 확인했다. 7개 서비스 전체 리허설 스크립트 재실행은 미실시.

## Phase E1: 서비스별 runtime DB 계정 (opt-in)

**결정 (2026-10-23, 위임된 에이전트 결정)**: 7개 DB 서비스가 공유하던 MySQL root 대신 서비스마다 `buildflow_<svc>`@`'%'` 계정을 두고, 자기 스키마 `buildflow_<svc>.*`에만 `ALL PRIVILEGES`(DDL 포함)를 준다. 다른 스키마·`mysql` 시스템 스키마·전역 권한(`CREATE USER`, `PROCESS` 등)·`GRANT OPTION`은 없다. 한 서비스의 자격 증명이나 SQL 주입이 다른 서비스 데이터와 계정 관리로 번지는 범위를 줄이는 것이 목적이다.

- **대안 — 처음부터 DML 전용**: 운영은 아직 Hibernate `ddl-auto: update`(auth는 `schema.sql`)라서 기동 시 `CREATE/ALTER TABLE` 권한이 필요하다. DML만 주면 Entity 변경 후 기동이 실패하므로 Flyway 운영 전환 전에는 채택하지 않는다.
- **E2(후속)**: 서비스별 Flyway 운영 전환이 끝나면 runtime 계정은 `SELECT/INSERT/UPDATE/DELETE`만 두고, 마이그레이션은 별도 계정이나 배포 단계에서 수행한다.
- **기본값 불변**: `docker-compose.app.yml`은 `<SVC>_DB_USERNAME`/`<SVC>_DB_PASSWORD`가 없으면 기존 `DB_USERNAME`(기본 root)/`DB_PASSWORD`를 그대로 쓴다. 앱 `application.yml`의 `DB_USERNAME`/`DB_PASSWORD` 키는 바꾸지 않았다.

### 계정 생성·갱신

비밀번호는 파일·명령행에 남기지 않는다. 현재 PowerShell 프로세스 환경변수 `<SVC>_DB_PASSWORD`(예: `SITE_DB_PASSWORD`, 16자 이상) 또는 `-EnvFile .env`의 `*_DB_PASSWORD` 줄에서 읽어 `docker exec -i` stdin으로 mysql에 전달한다. root 비밀번호는 대상 컨테이너의 `MYSQL_ROOT_PASSWORD`를 컨테이너 안에서만 사용한다.

```powershell
.\scripts\db\service-accounts.ps1 -EnvFile .\.env          # 7개 전체, 기본 대상 buildflow-mysql
.\scripts\db\service-accounts.ps1 -Service site -EnvFile .\.env
```

스크립트는 하나라도 비밀번호가 없거나 짧으면 아무 계정도 바꾸지 않고 중단한다. 재실행은 안전하다: `CREATE USER IF NOT EXISTS` → 비밀번호를 입력값으로 재설정 → `REVOKE ALL PRIVILEGES, GRANT OPTION` → 자기 스키마 `GRANT ALL`을 다시 적용하므로 손으로 붙인 추가 권한(drift)도 제거된다. `docker/mysql/init`은 새 volume에서만 실행되므로 계정·비밀번호를 넣지 않는다.

적용 순서: 백업 → 스크립트 실행 → `.env`에 `<SVC>_DB_USERNAME=buildflow_<svc>`와 같은 비밀번호를 **쌍으로** 추가 → 해당 서비스만 재생성 → health·로그 확인. USERNAME만 넣고 PASSWORD를 빠뜨리면 root 비밀번호로 접속을 시도해 인증 실패로 기동이 멈춘다(안전 실패). 되돌리기는 두 키를 지우고 서비스를 재생성하면 root 경로로 복귀한다.

### 검증

`scripts/db/test-service-accounts-docker.ps1`은 무작위 Compose project(`bfacct<난수>`, 호스트 DB 포트 없음)의 MySQL 8에서 다음을 확인하고 그 project의 컨테이너와 이름·label을 재확인한 자기 volume만 제거한다. CI `db-service-accounts` job이 같은 스크립트를 실행한다.

- 비밀번호 1개 누락 시 거부·계정 0개, 7계정 생성 시 출력에 비밀번호 미노출
- 각 계정 `SHOW GRANTS`가 정확히 `USAGE ON *.*` + 자기 스키마 `ALL`
- 자기 스키마 `CREATE/ALTER/INDEX/INSERT/UPDATE/SELECT/DELETE/DROP` 허용, `CREATE DATABASE IF NOT EXISTS <자기 스키마>`(chat/notification JDBC `createDatabaseIfNotExist`) 허용
- 다른 스키마 SELECT/INSERT(1142), `mysql.user`(1142), 새 DB 생성(1044), `CREATE USER`(1227) 거부, `SHOW DATABASES`에 자기 BuildFlow 스키마만 노출
- 교차 스키마 SELECT·전역 `PROCESS`를 주입한 뒤 재실행하면 제거되고 계정 수 7 유지
- `-ValidateSiteService`: site-service 이미지를 `buildflow_site`로 기동해 `/actuator/health` UP, Hibernate `update`가 테이블 생성

2026-10-23 로컬 Windows Docker에서 전 항목 PASS(site 테이블 5개). **기존 로컬·VPS DB에는 계정을 만들지 않았고 VPS에 적용하지 않았다.** 운영 적용은 백업·서비스별 전환 확인을 거친 별도 작업이다.

## 참고

- [Flyway baselines](https://documentation.red-gate.com/fd/baselines-273973441.html)
- [Flyway MySQL 지원 모듈](https://documentation.red-gate.com/fd/mysql-277579322.html)
- [MySQL 8.0 mysqldump](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html)
