# VPS 비공개 파일럿 준비 (2026-10-01)

## 범위와 판단

- 사용자는 기존 VPS의 OpenClaw·Ollama 및 다른 파일·서비스를 보존할 필요가 없다고 확인했다.
- Hostinger hPanel에 2026-09-27, 2026-09-20 주간 백업이 존재한다. OS 변경 후에도 정기 백업은 남지만 순환 교체되며, 복원은 당시 Docker·Traefik 템플릿과 SSH 설정 전체로 돌아간다. 이를 장기 보관이나 새 BuildFlow 데이터 백업으로 간주하지 않는다.
- 2 vCPU / 약 8 GiB VPS에서는 전체 MSA와 7B Ollama를 동시에 안정적으로 실행할 수 있다고 가정하지 않는다. 첫 단계는 AI 모델·공개 도메인 없이 SSH 터널로만 접속하는 빈 데이터 파일럿이다.
- 로컬 BuildFlow 데이터와 VPS 데이터는 별개다. 이 작업은 로컬 DB 삭제·이관을 포함하지 않는다.

## 단계

1. 현재 Compose를 유지하며 VPS 전용 override에 자동 재시작, 제한된 로그, 보수적 메모리/JVM 한도, Linux 내부 Ollama 주소를 추가한다. PDF 업로드 프록시 한도를 백엔드와 맞춘다.
2. `docker compose config` 정적 검증 및 코드 리뷰. Docker Desktop이 실행될 때 로컬 런타임 회귀를 수행한다.
3. Hostinger에서 **Plain OS Ubuntu 24.04**로 변경한다. `Reinstall`은 기존 템플릿을 되살릴 수 있으므로 사용하지 않는다. 새 SSH host key fingerprint를 Web Console에서 다시 확인한다.
4. 공식 Docker Engine/Compose 설치, 전용 배포 계정·키 및 최소 방화벽을 설정한다. Docker 소켓/그룹 권한은 사실상 root 권한이므로 별도 검토한다.
5. 새 비밀값으로 서버 전용 `.env`를 만들고 앱을 순차 빌드·기동한다. 프론트는 계속 `127.0.0.1:3000`에만 바인딩한다. SSH 터널로 로그인·CRUD·PDF 업로드를 검증한다.
6. 빈 데이터 상태에서 서버 재부팅 후 복구, DB와 업로드 파일의 일관된 백업·격리 복원을 검증한다. 실제 업무 데이터 입력 전 Flyway/DB 권한 작업과 운영 백업 체계를 완료한다.
7. 도메인 공개는 별도 단계로 분리한다. HTTPS, 로그인 시도 제한, 노출 포트 정책, 외부 백업, 자원 여유가 검증된 뒤 진행한다.

## 중단·되돌림 조건

- hPanel 백업 항목이 없어졌거나 예상과 다르면 OS 변경 중단.
- 새 OS의 SSH host key를 독립 경로(Web Console)에서 확인하지 못하면 SSH 접속 중단.
- 서비스 OOM·반복 재시작·핵심 API 오류 시 데이터 입력 중단, `docker compose down`으로 앱을 멈춘 뒤 원인을 수정한다. 영구 볼륨은 삭제하지 않는다.
- 부득이하게 2026-09-27 백업을 복원하면 기존 OpenClaw/Docker·Traefik 상태 및 오래된 SSH 설정이 되돌아온다. 이는 임시 복구이며 새 BuildFlow 데이터의 복원책이 아니다.

## 현재 결과

- VPS 전용 override·비공개 배포 런북·서버 전용 비밀값/대화형 관리자 초기화 도구를 준비했다. 15개 서비스 Compose 병합 검증에서 외부 공개 포트 0, 메모리 한도·재시작 누락 0, 터널 CORS·Linux Ollama 내부 주소를 확인했다.
- 독립 보안 리뷰의 SSH 터널 CORS 차단 지적을 수정했다. 프론트 lint·108개 테스트·프로덕션 빌드와 백엔드 전체 Gradle 41 tasks를 통과했다. 기존 프론트 대형 chunk/React Router 경고는 별도 백로그의 기존 이슈다.
- 로컬 Docker Engine은 현재 꺼져 있어 컨테이너 런타임 검증은 미완료. Python VPS 도구는 기존 `.env` 보존과 비대화형 관리자 실행 거부를 확인했지만, 새 Linux 환경의 실제 초기화는 아직 검증하지 못했다.
- VPS OS/서비스는 아직 변경하지 않았다.
- [PR #64](https://github.com/hhm0215/build-flow/pull/64)는 GitHub CI 6개 성공, 로컬·원격·PR 3개 커밋 SHA 일치 후 merge commit `ced4fca`로 병합했다. 이 병합은 준비 코드만 포함하며 실제 서버 배포 완료를 뜻하지 않는다.
