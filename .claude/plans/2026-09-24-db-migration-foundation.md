# 실데이터용 DB 마이그레이션 기반

## 목표

실데이터를 보존한 채 Hibernate 자동 스키마 변경에서 버전 관리형 마이그레이션으로 전환할 수 있도록, 먼저 재현 가능한 백업·검증 경로와 전환 가드레일을 만든다.

## 확인된 현재 상태

- MySQL 스키마는 `buildflow_auth`, `buildflow_estimate`, `buildflow_site`, `buildflow_purchase`, `buildflow_tax`, `buildflow_notification`, `buildflow_chat` 7개다.
- `auth-service`는 `schema.sql` 초기화와 `ddl-auto: validate`를 사용한다.
- 나머지 6개 DB 서비스는 `ddl-auto: update`를 사용한다.
- MySQL 데이터는 Compose named volume `mysql_data`에 보존된다.
- 2026-09-29 Docker Desktop 런타임 소켓을 데이터 volume과 분리해 복구했고 실제 DDL 덤프와 오프라인 검증을 완료했다.

## 단계

### Phase A — 백업·검증 기반 (현재 사이클)

- [x] 7개 스키마 고정 allowlist 기반 논리 백업 스크립트
- [x] SHA-256, 스키마/테이블별 행 수, 관리자 계정 비노출 digest manifest
- [x] Docker 없이 실행 가능한 오프라인 백업 검증 스크립트
- [x] 백업 산출물 Git 제외
- [x] 운영 런북과 금지 규칙 문서화
- [x] PowerShell 구문 분석 및 독립 역할 리뷰
- [x] Docker 복구 후 실제 덤프 생성·오프라인 검증

### Phase B — 격리 복원 검증

- [x] 기존 `buildflow-mysql` 컨테이너와 `mysql_data` 볼륨을 절대 대상으로 삼지 않는 별도 Compose project/임시 volume에 복원한다.
- [x] 원본 manifest와 스키마/테이블별 행 수, 관리자 digest를 비교하고 `CHECK TABLE`을 수행한다.
- [x] auth-service를 SQL 초기화 금지·`ddl-auto: validate`로 기동하고 전후 DB 불변을 확인한다.
- [ ] 관리자 비밀번호를 저장하지 않는 대화형 옵션으로 복원 환경의 실제 로그인·로그아웃을 확인한다.

### Phase C — auth-service Flyway 파일럿

- 실제 MySQL `SHOW CREATE TABLE`과 검증된 덤프를 기준으로 V1 baseline을 작성한다.
- `flyway-core`와 MySQL 전용 모듈을 추가한다.
- 기존 비어 있지 않은 DB는 명시적인 일회성 baseline만 수행한다.
- 상시 `baseline-on-migrate=true`를 사용하지 않고 `clean`은 비활성화한다.
- `schema.sql` 자동 실행을 끄고 JPA는 `validate`를 유지한다.

### Phase D — 나머지 서비스 순차 전환

- chat/estimate → purchase/tax → site/notification 순으로 전환한다.
- 한 시점에 Flyway와 Hibernate `update`가 동시에 스키마를 쓰지 않게 한다.
- 마지막 별도 작업에서 runtime DB 사용자의 root 권한을 제거한다.

## 멈춤 조건

- 백업 파일 또는 manifest 검증 실패
- 실제 스키마가 7개 allowlist와 불일치
- 관리자 계정 수가 1이 아니거나 복원 digest 불일치
- Docker 임시 복원 환경이 기존 컨테이너/볼륨과 격리되지 않음
- 실제 DDL과 V1 baseline 불일치

## 검증 결과

- PowerShell 5.1 호환 parser로 4개 스크립트의 구문을 확인했다.
- 오프라인 fixture에서 manifest/SHA-256/7개 스키마/테이블/관리자 행 검증 성공과 dump 변조 거부를 확인했다.
- native process 인자 인코딩은 공백 경로, 따옴표, 끝 역슬래시, 빈 문자열 회귀를 통과했다.
- 독립 운영·보안 리뷰에서 발견한 stderr 혼입, 원본 volume 신원, 민감 평문, dump↔manifest 교차검증, 불완전 산출물 구분 문제를 반영했다.
- 수정 후 독립 재검토에서 CRITICAL/HIGH 0건을 확인했다.
- 실제 기존 DB 백업 `20260929T123815Z`의 SHA-256·7개 스키마·테이블·관리자 digest를 검증했다.
- 업무 데이터를 비운 새 환경에 기존 관리자 1명만 정확히 복원하고, 관리자 1행·업무 데이터 0행 기준 백업 `20260929T125754Z`를 다시 생성·오프라인 검증했다.
- 전용 `restore-test.compose.yml`과 무작위 project/nonce를 사용해 기준 백업을 임시 volume에 실제 복원했다. 7개 스키마·19개 테이블·정확한 행 수·관리자 digest·모든 `CHECK TABLE`이 일치했고, auth-service의 `ddl-auto=validate` 기동 후 DB가 변하지 않았다. 임시 컨테이너·volume은 label 검증 후 제거됐으며 운영 DB는 관리자 1행·업무 데이터 0행 그대로다.
- Phase B는 대화형 관리자 로그인·로그아웃 1건만 남았다.
