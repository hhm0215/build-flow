# CI 릴리스 후보 증거 (배포 아님)

`main` push의 CI 5개 검사가 모두 성공하면 `release-candidate-<SHA>` artifact에 `release-candidate.json`을 14일 동안 보관한다. PR·`develop`·실패하거나 누락된 검사에서는 생성하지 않는다. 이 파일은 **후보의 출처와 테스트 결과에 관한 증거**이지 Docker 이미지, 배포 승인, VPS 배포 성공 또는 실데이터 운영 승인이 아니다.

## 포함 정보와 신뢰 경계

- 정확한 40자리 `main` source SHA, 저장소, CI run ID/attempt, 5개 검사 결과 `success`.
- 추적된 배포 관련 파일 7개의 SHA-256. `.env`, 키, DB 덤프, 업로드 파일, 실제 호스트 주소는 포함하지 않는다.
- `deploy_authorized: false`, `real_data_ready: false` 고정. 이 값을 바꾼 artifact나 형식이 다른 artifact를 배포 입력으로 사용하지 않는다.
- `actions/upload-artifact`가 표시하는 digest는 업로드된 artifact의 무결성 정보다. 소스 SHA나 실제 서버에서 실행 중인 Docker image ID/digest를 대신하지 않는다.

생성 job은 GitHub-hosted runner에서 `contents: read`만 사용하고 운영 SSH 키·웹훅·배포용 토큰을 받지 않는다. `workflow_run`, `pull_request_target`, 일반 VPS self-hosted runner, 서버의 체크아웃 스크립트 실행은 사용하지 않는다. 정확한 SHA checkout과 clean tracked tree를 검사한다. 이 artifact는 다운로드 후에도 신뢰된 `main` CI run과 SHA를 다시 대조해야 한다.

## 다음 승격 게이트

CD에서 실제 이미지를 반영하려면 별도 고정 image digest, 변경 서비스 allowlist, VPS의 실제 실행 이미지·이전 릴리스 매핑, 배포 잠금, 순차 health, 외부 HTTPS·401, 실패 중단·롤백과 알림 수신이 필요하다. 7개 DB+업로드 파일의 외부 일관 백업·격리 복원, 전 서비스 버전 마이그레이션, 최소 권한 DB 계정, 전체 CRUD/PDF와 재부팅 회귀가 끝나기 전 운영 DB 대상 자동 배포를 켜지 않는다. 이 문서와 후보 artifact는 `docs/SECURITY_OPERATIONS.md`의 실데이터 게이트를 대체하지 않는다.
