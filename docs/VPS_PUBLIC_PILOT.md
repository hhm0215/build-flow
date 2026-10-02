# VPS 공개 HTTPS 테스트 파일럿

> ADR-019에 따른 **테스트 데이터 전용** 접속이다. URL이 열리고 로그인이 되더라도 실데이터 운영 승격은 아니다. 업무 자료는 DB 마이그레이션, DB+업로드 파일의 외부 백업·격리 복원, 최소 권한 DB 계정 게이트 이후에만 입력한다.

공개 전·후 판정 기준은 `docs/SECURITY_OPERATIONS.md`를 따른다. `FAIL` 항목을 CI 정적 성공으로 대체하지 않는다. 특히 로그아웃 전 발급된 같은 토큰이 로그아웃 후 401인지 확인하기 전에는 인증 검증 완료로 표시하지 않는다.

## 접속 주소와 전제

- 첫 후보는 VPS 기본 호스트명이다(실제 값은 Git 제외 로컬 운영 기록 참조). DNS가 VPS 공인 IP를 가리키는지는 확인했지만, 이 이름으로 공인 인증서가 실제 발급되는지는 배포 후 검증해야 한다. 발급 실패 시 브라우저 경고를 무시하지 말고 사용자 소유 도메인을 준비한다.
- 기존 SSH 터널 `http://127.0.0.1:13000`은 긴급 접근 경로로 유지한다. 공개하는 포트는 Caddy의 TCP 80/443뿐이다. MySQL·Redis·Kafka·Gateway·frontend의 published port는 `127.0.0.1`에만 남는다.
- CI/CD 자동 배포는 별도 백로그다. 공개 파일럿에 코드를 수동 반영할 때도 `main`의 검증된 커밋만 사용한다.

## 적용 전 점검

서버 Web Console의 root 세션에서 `/opt/buildflow`로 이동한다. 아래 명령의 `your-vps-hostname.example`은 Git 제외 운영 기록에 있는 **실제 VPS 호스트명으로 치환**한다. `.env` 비밀값을 화면·로그·문서에 출력하지 않는다. `PUBLIC_HOST`는 그 세션에서만 지정해도 Caddy 컨테이너 환경에 저장되며 재부팅 후에도 유지된다. 후일 Compose를 다시 실행할 때는 같은 값을 다시 지정해야 한다.

```bash
cd /opt/buildflow
git status --short --branch
git fetch origin main:refs/remotes/origin/main
git log -1 --format='%H %s' origin/main
git switch --detach origin/main
git rev-parse HEAD
test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)"
export PUBLIC_HOST="your-vps-hostname.example"
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml -f docker-compose.public.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml -f docker-compose.public.yml config --format json | python3 scripts/vps/verify_public_compose.py
ss -lntp
```

서버 clone은 `develop` 단일 브랜치 refspec이라 일반 `git fetch origin`만으로 `origin/main`이 생기지 않았다. 위와 같이 `main`을 명시적으로 가져온다. 작업 트리가 깨끗하고, 병합된 `main` 커밋의 SHA와 CI 결과를 확인하기 전에는 `git switch`/배포를 하지 않는다. `git switch --detach origin/main`은 서버를 검증된 릴리스에 고정하며 기존 `.env`는 Git 제외 상태로 유지한다. 현재 앱 상태와 기존 loopback 포트·`docker compose ... ps` 결과를 기록한다. 서버 방화벽/Hostinger 방화벽에서 80/443의 실제 허용 여부를 확인하되 SSH 22를 닫지 않는다. 기본 호스트명 DNS와 인증서 검증이 끝나기 전에는 업무 데이터를 입력하지 않는다.

## 제한된 적용

기존 운영 볼륨을 삭제하거나 초기화하지 않는다. 프론트 이미지 하나만 빌드한다. 기존 3파일 private stack을 확장하는 4번째 파일을 항상 마지막에 둔다.

```bash
cd /opt/buildflow
export PUBLIC_HOST="your-vps-hostname.example"
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml -f docker-compose.public.yml build frontend
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml -f docker-compose.public.yml up -d --no-build
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml -f docker-compose.public.yml ps
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml -f docker-compose.public.yml logs --tail=80 caddy gateway-server frontend
```

`caddy_data`와 `caddy_config` 볼륨은 인증서/설정의 재시작 지속성을 위해 보존한다. `down -v` 또는 volume 제거를 사용하지 않는다.

## 외부 검증

1. 외부망에서 `http://<VPS_HOST>`가 HTTPS로 전환되고, `https://<VPS_HOST>/login`의 인증서가 신뢰되는지 확인한다. 만료·이름 불일치·자체 서명 경고는 우회하지 않는다.
2. 로그인 전 `/api/v1/sites`는 401인지, 관리자 로그인 후 대시보드와 테스트 현장 API가 동작하는지 확인한다. 비밀번호는 채팅·로그에 입력하지 않고 사용자가 직접 웹 폼에 넣는다. UI 로그아웃은 서버 `/api/v1/auth/logout` 성공을 확인해야 하며 **로그아웃 전 발급된 동일 Bearer 토큰**으로 보호 API를 다시 호출했을 때 401인지 확인한다. 무토큰 401은 토큰 폐기 증거가 아니다. 토큰 값은 화면·로그·문서에 출력하지 않는다.
3. 로그인 요청 제한(429), 허용하지 않은 Origin의 CORS 거부, 호스트의 비loopback 리스닝 포트가 SSH 22와 웹 80/443뿐인지 검사한다. 내부 서비스의 포트가 외부로 열렸다면 즉시 롤백한다.
4. Caddy/프론트/Gateway 재시작 후 HTTPS·터널 접속을 재확인한다. 실제 리부팅 회귀와 DB+파일 복원 검증은 별도 선행 게이트로 남긴다.

## 실패 시 롤백

TLS, 로그인, 내부 포트, 리소스 중 하나라도 실패하면 공개 파일럿을 중단한다. 아래 `down`은 **컨테이너와 네트워크만** 내리고 named volume은 보존한다. 다시 3파일 private stack을 띄워 터널의 200/401을 확인한다.

```bash
cd /opt/buildflow
export PUBLIC_HOST="your-vps-hostname.example"
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml -f docker-compose.public.yml down
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml up -d --no-build
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml ps
```

기본 호스트명이 인증서 대상이 아니면 강제로 HTTP만 공개하거나 TLS 검증을 끄지 않는다. 사용자 소유 도메인과 DNS를 준비한 뒤 다시 시도한다.
