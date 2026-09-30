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

스크립트는 컨테이너 내부 `MYSQL_ROOT_PASSWORD`를 자식 프로세스 환경으로만 전달하고 값을 명령행, manifest, 로그에 기록하지 않는다. `mysqldump`는 InnoDB 일관성 확보를 위한 `--single-transaction`, 큰 테이블 스트리밍을 위한 `--quick`, stored object 보존을 위한 `--routines --triggers --events`, 불필요한 tablespace 권한을 피하는 `--no-tablespaces`를 사용한다.

백업 후 또는 다른 저장 장치로 복사한 뒤에는 Docker 없이 다음 검증을 실행한다.

```powershell
.\scripts\db\verify-backup.ps1 -BackupDirectory .\backups\db\<UTC timestamp>
```

성공 조건은 dump가 비어 있지 않고, manifest의 SHA-256과 일치하며, 7개 스키마·테이블 정의·단일 관리자 행·mysqldump 완료 footer가 manifest와 일치하는 것이다. 이 검증은 파일 손상 여부를 확인하지만 실제 복원 성공을 대신하지 않으며, dump와 manifest를 함께 바꾸는 공격을 막는 전자서명도 아니다.

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

## 참고

- [Flyway baselines](https://documentation.red-gate.com/fd/baselines-273973441.html)
- [Flyway MySQL 지원 모듈](https://documentation.red-gate.com/fd/mysql-277579322.html)
- [MySQL 8.0 mysqldump](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html)
