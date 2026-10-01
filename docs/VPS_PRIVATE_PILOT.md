# VPS 비공개 파일럿 운영 절차

> 목표: 기존 OpenClaw 템플릿을 깨끗한 Ubuntu 24.04로 교체한 뒤, BuildFlow를 **SSH 터널에서만** 검증한다. 도메인·공개 HTTPS 운영 절차가 아니다.

## 실제 VPS 작업 기록 (2026-10-01)

| 항목 | 확인 결과 |
|------|-----------|
| 대상 | Hostinger VPS (실제 식별자·IP는 Git 제외 로컬 기록에만 보관) |
| 변경 전 | Ubuntu 24.04 with Docker and Traefik 템플릿. OpenClaw·Ollama는 사용하지 않으며 보존할 VPS 데이터가 없다고 사용자 확인 |
| 복구 안전망 | hPanel에서 주간 백업 두 건을 변경 직전 확인. 자동 순환 백업이므로 영구 보존 아님 |
| 변경 | 사용자가 최종 삭제 경고를 확인하고 새 root 비밀번호 입력 및 `Change OS` 제출. `Reinstall`은 사용하지 않음 |
| 변경 후 | hPanel 현재 OS `Ubuntu 24.04 LTS`, Web Console 로그인 배너 `Ubuntu 24.04.5 LTS` 확인 |
| 새 SSH host key | Web Console에서 `/etc/ssh/ssh_host_ed25519_key.pub`의 ED25519 지문을 확인. 이전 지문은 폐기. 로컬 `known_hosts` 갱신 전 Git 제외 로컬 기록의 새 지문과 대조할 것 |
| 초기 자원 | RAM 7.8 GiB (swap 0), `/` 96 GB 중 사용 798 MB. `docker` 실행 파일 없음. 리스닝 포트는 SSH 22와 로컬 DNS 53만 확인 |
| 접속 | `buildflow-deploy` 일반 계정 생성, 소유권·권한 `~ 750`/`.ssh 700`/`authorized_keys 600`, PC 공개키 지문 일치 및 별도 SSH 키 로그인 성공. sudo·Docker 그룹 권한 없음 |
| 현재 단계 | OS 변경과 비root SSH 접속 검증 완료. Docker/Compose, BuildFlow 배포와 런타임 검증은 아직 미완료 |

실제 서버 식별자·IP·지문·시각·운영 명령은 Git 제외 파일 `docs/VPS_LOCAL_OPERATIONS.md`에 기록한다. 이 파일에도 비밀번호·개인키·`.env` 값을 넣지 않는다. 현 단계에서 BuildFlow 서비스나 업무 데이터가 VPS에 올라간 것으로 간주하지 않는다.

## 사전 확인

- Hostinger hPanel의 `Backups & Monitoring → Snapshots & Backups`에서 정기 백업의 **현재 존재와 날짜를 OS 변경 직전에 다시 확인**한다. 2026-10-01 화면에는 9월 27일·20일 백업이 있었지만, 오래된 백업은 자동 교체된다.
- 사용자는 현 VPS에서 보존할 OpenClaw·Ollama 외 파일이나 서비스가 없다고 확인했다. 이 확인은 로컬 BuildFlow 데이터의 삭제·이관 승인이 아니다.
- OS 변경 시 현재 VPS 파일과 수동 스냅샷은 삭제된다. 기존 정기 백업은 유지되지만 복원하면 과거 Docker·Traefik 템플릿, 당시 SSH 설정까지 통째로 돌아간다. 새 BuildFlow 데이터의 백업으로 사용하지 않는다.
- 로컬 `docker compose config` 검증은 통과했지만 로컬 Docker Engine이 꺼져 있어 런타임 시험은 아직 통과하지 않았다.

## 1. 새 OS와 SSH

1. hPanel `OS & Panel → Operating System → Plain OS → Ubuntu 24.04`의 **Change OS**를 사용한다. 같은 템플릿의 `Reinstall`을 누르지 않는다.
2. 완료 후 Web Console에서 새 `/etc/ssh/ssh_host_ed25519_key.pub` 지문을 확인한다. 기존 SSH host key를 신뢰하지 않으며, 로컬 `known_hosts` 갱신은 새 지문을 독립 확인한 뒤에만 한다.
3. Web Console에서 배포용 일반 계정과 기존 로컬 공개키를 다시 등록한다. 9월 30일 만든 계정·키는 새 OS에 남지 않는다. 키 로그인 성공을 별도 SSH 세션에서 확인하기 전에는 root/비밀번호 로그인을 끄지 않는다.
4. Docker Engine + Compose plugin은 [Docker 공식 Ubuntu 절차](https://docs.docker.com/engine/install/ubuntu/)로 설치한다. Docker 소켓 접근(`docker` 그룹)은 사실상 root 권한이므로 [Docker의 권한 설명](https://docs.docker.com/engine/install/linux-postinstall/)을 읽고, 배포 계정에 무심코 부여하지 않는다.

## 2. 비공개 배포

- 서버에 이 저장소를 배치한다. `python3 scripts/vps/init_env.py`로 **서버에서** 새 무작위 DB/JWT 비밀값을 만든다. 스크립트는 기존 `.env`를 덮어쓰지 않고 새 파일을 소유자 전용 권한(`0600`)으로 만든다. 로컬 `.env`를 복사하거나 Git에 넣지 않는다. 현재 앱은 DB root 계정을 사용하므로 `DB_ROOT_PASSWORD`와 `DB_PASSWORD`가 같아야 한다.
- 아래 세 파일을 **항상 같은 순서**로 사용한다. 기본 Compose의 모든 published port는 `127.0.0.1`에만 묶인다. VPS 전용 파일은 제한된 로그·메모리·JVM heap과 재시작 정책을 더한다.

```bash
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml config --quiet
COMPOSE_PARALLEL_LIMIT=1 docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml build
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml up -d --wait mysql redis
python3 scripts/vps/create_admin.py
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml up -d --no-build
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml ps
```

- `container-ollama` profile은 첫 배포에서 켜지 않는다. VPS override는 AI 서비스의 URL을 Compose 내부 `ollama:11434`로 맞추지만 모델 컨테이너가 없으므로 파싱·챗봇·대시보드 AI 요약은 이 파일럿에서 사용할 수 없다. 대시보드의 손익 통계는 별도 API로 계속 동작한다. 8 GiB에서 7B 모델의 동시 구동은 별도 자원 시험 후 판단한다.
- 서비스 OOM, `unhealthy`, 반복 재시작이 있으면 입력을 중단하고 `docker compose ... ps/logs`와 호스트 메모리를 확인한다. 메모리 수치는 초기 상한이며 실측 후 조정한다.

Windows 로컬 PC에서 포트 13000을 통해 비공개 접속한다(실제 키 경로와 VPS 호스트명 대입):

```powershell
ssh -i <PRIVATE_KEY_PATH> -N -L 13000:127.0.0.1:3000 buildflow-deploy@<VPS_HOST>
```

다른 창에서 `http://localhost:13000`을 연다. 프론트 Nginx는 원래 Host와 포트를 Gateway에 전달하며 VPS CORS 허용 목록은 이 터널 주소 두 가지로 한정한다. 관리자는 위의 대화형 로컬 명령으로 생성하며 네트워크 가입 API는 없다. 로그인·현장·견적·매입·세금·보증보험 PDF 업로드, 서버 재부팅 후 자동 복귀를 확인한다. 다른 서비스 포트나 3000을 공인 IP에 공개하지 않는다.

## 3. 업무 데이터 입력 전 필수 게이트

1. [DB 운영 가이드](DATABASE_OPERATIONS.md)의 Flyway 전환과 서비스별 최소 권한 DB 계정을 완료한다. 현재 다수 서비스의 Hibernate `ddl-auto: update`와 DB root 공유는 실데이터 운영 기준이 아니다.
2. DB 7개 스키마와 `warranty_uploads` 볼륨을 **같은 쓰기 중지 시점**에 다른 물리 대상에 백업하고, 빈 환경에 격리 복원해 DB 행·파일 체크섬·관리자 로그인을 검증한다. 현재 Windows SQL 백업 도구만으로는 업로드 PDF가 보존되지 않는다. Hostinger 주간 이미지 백업만으로 이 게이트를 대체하지 않는다.
3. 공개 도메인은 별도 단계에서 HTTPS reverse proxy, 로그인 시도 제한, 방화벽/포트, 외부 백업, 용량 실측을 완료한 뒤 연결한다. 초기 파일럿에서 HTTP 서비스를 인터넷에 열지 않는다.

## 문제가 생겼을 때

- 앱 구성 문제: 원격 파일럿만 중지하고 기존 Docker volume은 보존한다. 데이터가 없는 새 VPS라면 수정 후 재배포한다. 로컬 BuildFlow 환경에는 영향을 주지 않는다.
- OS/SSH 문제: Hostinger Web Console로 접속해 수정한다. 새 host key를 확인하지 못하면 일반 SSH로 접속하지 않는다.
- 최후의 VPS 복원: hPanel에 남아 있는 과거 정기 백업을 선택할 수 있다. 복원은 새 VPS 전체를 덮어쓰고 OpenClaw 템플릿·옛 SSH 설정을 다시 가져오므로, 새 BuildFlow 데이터를 넣은 뒤에는 이 방법을 사용하지 않는다.

## 참고

- [Hostinger OS 변경·재설치 차이](https://www.hostinger.com/support/4965922-how-to-change-the-operating-system-of-your-vps-at-hostinger/)
- [Hostinger 정기 백업·복원](https://www.hostinger.com/support/1583232-how-to-back-up-or-restore-a-vps-at-hostinger/)
- [Docker Compose 병렬 빌드 제한](https://docs.docker.com/reference/cli/docker/compose/)
