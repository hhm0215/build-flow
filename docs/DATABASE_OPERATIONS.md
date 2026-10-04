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

스크립트는 컨테이너 내부 `MYSQL_ROOT_PASSWORD`를 자식 프로세스 환경으로만 전달하고 값을 명령행, manifest, 로그에 기록하지 않는다. `mysqldump`는 InnoDB 일관성 확보를 위한 `--single-transaction`, 큰 테이블 스트리밍을 위한 `--quick`, stored object 보존을 위한 `--routines --triggers --events`, 불필요한 tablespace 권한을 피하는 `--no-tablespaces`를 사용한다.

백업 후 또는 다른 저장 장치로 복사한 뒤에는 Docker 없이 다음 검증을 실행한다.

```powershell
.\scripts\db\verify-backup.ps1 -BackupDirectory .\backups\db\<UTC timestamp>
```

성공 조건은 dump가 비어 있지 않고, manifest의 SHA-256과 일치하며, 7개 스키마·테이블 정의·단일 관리자 행·mysqldump 완료 footer가 manifest와 일치하는 것이다. 이 검증은 파일 손상 여부를 확인하지만 실제 복원 성공을 대신하지 않으며, dump와 manifest를 함께 바꾸는 공격을 막는 전자서명도 아니다.

차기 통합 패키지를 위한 `verify-warranty-files.ps1`은 파일만 검사하는 준비 도구다. 고정 경로 `uploads/warranties` 아래 일반 파일의 바이트 수·SHA-256, manifest 내부의 참조 ID, SQL과 같은 Compose project의 별도 volume 신원을 확인한다. 파일 누락·추가·변조, 중복/위험 상대경로, 링크·하위 디렉터리, manifest에 적힌 파일이 없는 참조는 실패한다. **이 검사는 SQL의 실제 `defect_warranties.file_path`와 대조하지 못한다.** 그러므로 `verify-backup.ps1`과 `restore-test.ps1`은 `formatVersion: 2`를 명시적으로 거부한다. v2 백업 생성·DB/파일 통합 격리 복원·실제 DB 참조 검증을 구현하기 전에는 합성 파일 검사 통과를 운영 백업 성공으로 기록하지 않는다.

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

## 참고

- [Flyway baselines](https://documentation.red-gate.com/fd/baselines-273973441.html)
- [Flyway MySQL 지원 모듈](https://documentation.red-gate.com/fd/mysql-277579322.html)
- [MySQL 8.0 mysqldump](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html)
