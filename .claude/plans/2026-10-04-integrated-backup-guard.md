# 통합 백업/복원 준비 — SQL-only 오인 방지와 업로드 파일 검사

## 배경·범위

현재 `backup.ps1`은 7개 MySQL 스키마만 백업하며 `warranty_uploads`와 Kafka를 보존하지 않는다. 사용자가 실데이터 입력을 원하므로, 이 부분 백업을 운영 복구 가능 상태로 오인하지 않게 막고 통합 패키지의 파일 검사 단위를 먼저 분리한다. 이번 단계에서 USB 원본·VPS 데이터·Docker volume은 변경하지 않는다.

## 구현

1. v1 SQL 검증과 격리 복원의 출력에 파일/Kafka 미포함을 명시한다.
2. v2 통합 패키지는 실제 복원 DB의 `defect_warranties.file_path` 대조가 구현되기 전까지 성공 판정을 거부한다.
3. 업로드 파일 전용 진단은 고정 mount/상대경로, volume 신원, 파일 수·크기·SHA-256, manifest 내부 참조, 링크/하위 디렉터리를 검사한다. SQL 참조 검증이 아님을 출력한다.
4. 합성 패키지에서 정상, 변조, path traversal, 잘못된 volume project, 참조 누락, 미기재 파일, v2 전체 검증 거부를 회귀 테스트한다.
5. `DATABASE_OPERATIONS.md`와 백로그에 남은 통합 복원·OCR·Kafka·외부 암호화 복사 게이트를 기록한다.

## 검증·결과

- PowerShell 5.1·7의 `test-backup-tools.ps1`와 `git diff --check`를 실행한다.
- 독립 `infrastructure-reviewer`가 실제 volume/경로 및 백업 중단 조건을 검토했다. 독립 `security-reviewer`는 v2 파일 검사만으로 SQL 참조를 입증할 수 없는 High 결함을 발견했고, 전체 v2 검증/복원을 fail-closed로 바꾸었다.
- Docker Engine이 현재 로컬에서 연결되지 않아 실제 파일 volume snapshot과 통합 격리 복원은 실행하지 않는다. 이는 본 단계에서 `PENDING`이다.
