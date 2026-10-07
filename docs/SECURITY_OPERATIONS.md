# BuildFlow 보안·인프라 점검 기준

> 2026-10-07 기준. 이 문서는 점검 항목과 판정 기준의 단일 진실원이다. `AGENTS.md`의 보안 게이트가 이 문서를 호출한다. 공개 HTTPS는 **테스트 데이터 파일럿**이며 실데이터 운영 승격과 다르다. 실제 호스트명·IP·키·비밀값·덤프는 Git 추적 문서와 점검 결과에 적지 않는다.

## 판정과 기록

- 각 항목을 `PASS`(실측 근거), `FAIL`(기준 위반), `PENDING`(아직 미실행), `UNVERIFIED`(권한·접속 부족), `N/A`(이유 명시)로 기록한다. Compose 정적 검증만으로 외부 차단을 `PASS`로 표시하지 않는다.
- 매 실행에 UTC 시각, private/public 단계, 기대·실제 Git SHA, 점검 명령 또는 CI 링크, 관측 결과, 담당 역할을 기록한다. 민감한 출력은 요약만 남긴다.
- `FAIL`이 인증·TLS·비밀값·공개 포트·백업 무결성에 걸리면 배포/데이터 입력을 중단한다. 점검자는 위험을 수정 제안할 수 있지만 포트 개방, 방화벽·SSH 변경, 재부팅, 볼륨 삭제, 복원, 운영 배포를 정기 작업으로 자동 실행하지 않는다.
- 공개 파일럿에서 인증서·로그인·포트 검증에 실패하면 `docs/VPS_PUBLIC_PILOT.md`의 **볼륨 보존** 롤백을 사용한다. 실데이터 운영은 별도 게이트를 모두 통과할 때까지 금지한다.

## 공개 HTTPS 파일럿 사전 게이트

| ID | 점검 | 통과 근거 | 현재 상태 |
|---|---|---|---|
| S01 | CI 검증된 `main` SHA와 VPS checkout/빌드 대상 일치, 작업 트리 청결 | PR CI·SHA 대조 및 서버 `git status` | PASS(public) — PR #84 및 병합 `main` CI 통과, merge SHA `9b7ea88` 서버 clean checkout; frontend 이미지만 적용 |
| S02 | `.env`와 키 보호 | 서버 `.env` 소유/모드 `root:root 0600`, 값 비출력; 개인키 미복사 | PASS — 서버 메타데이터 확인 |
| S03 | 내부 서비스 포트 비공개 | Compose 검증 + 호스트 IPv4/IPv6 리스닝 + 외부망 접속 확인. public은 22/80/443만, private은 22만 | PASS(public) — 비loopback 22/80/443만, 내부 포트 외부 접근 불가 |
| S04 | 관리자 인증 | 공개 회원가입 없음; 무인증 업무 API 401; 로그인 후 권한 경계 | PASS(public 기본 동선) — 사용자 직접 로그인·대시보드 재조회, 무인증 업무 API 401·가입 403 |
| S05 | 로그아웃 토큰 폐기 | UI 로그아웃이 서버 API를 호출하고 **로그아웃 전 발급된 동일 토큰** 재사용이 401 | PASS(private) / PENDING(public 재확인) — 2026-10-03 터널 UI 로그아웃·보호 화면 재진입 차단, 대화형 API 스모크에서 동일 토큰 401 실측 |
| S06 | TLS·호스트 | 공인 신뢰 인증서, 호스트명 일치, HTTP→HTTPS, 만료/갱신 경로; 잘못된 Host/SNI 거부 | PASS(새 접속·Host) / PENDING(실제 자동 갱신) — 사용자 소유 도메인에서 TLS 신뢰·정상 308/200, 잘못된 HTTP·HTTPS Host 421 |
| S07 | 웹 남용·CORS | 실제 공개 경로의 로그인 429, 변형 경로/위조 XFF 우회 실패, 미허용 Origin 거부 | PASS(검사 표본) — 기존 공개 경로에서 위조 XFF를 바꾼 빈 로그인 요청 429·변형 경로 403; 새 도메인에서 허용 Origin 200/정확한 ACAO, 미허용 Origin 403, 비지원 GET 로그인 405. 새 주소의 429 재검사는 PENDING |
| S08 | SSH 복구·하드닝 | 일반 계정 키·Web Console 복구 확인 후 root/password/keyboard-interactive 정책과 `sshd -t`·별도 재접속 검증. 외부 22 차단 시 독립 배포·점검·알림·원격 재개방 실측 | PASS(현재 정책) — password/kbd-interactive no, 키·Web Console 복구; root 키·22는 유지. 장래 22 차단은 PENDING |
| S09 | OS·의존성 패치 | 보안 업데이트/커널 상태와 영향 평가, 재부팅 시 서비스 복귀 검증 | PASS(커널·재부팅) / PENDING(잔여 업데이트 영향 평가·적용) — 6.8.0-146 재부팅 후 health 복귀; 2026-10-07 `libfreetype6` 보안 업데이트와 Compose plugin 업데이트 2건 대기 |
| S10 | 리소스·로그 | 실제 서비스 health, frontend 200, 보호 API 401, OOM/restart, 디스크·메모리, 비밀값 로그 없음 | PASS(public 기본 health) / PENDING(전 서비스 비밀값 로그 검토) — 2026-10-07 Gateway·8081~8087 health 200, frontend 200, 변경된 frontend·chat 컨테이너 restart 0/OOM false, 디스크·RAM 여유. 나머지 컨테이너의 최신 restart/OOM은 UNVERIFIED |
| S11 | 실패 복귀 | SSH 터널 200/401 보존, 공개 실패 시 Caddy 제거 후 private 복귀, named volume 유지 | PASS — Host 게이트 실패 시 볼륨 보존 `down`→private `up`, 200/401·22만 공개 확인 후 수정 재공개 |

**공개 결정:** 사용자가 승인한 테스트 데이터 전용 80/443 파일럿을 운영 중이다. 첫 공개에서 S06 Host 실패를 발견해 볼륨 보존 롤백했고, PR #75의 5개 CI 통과·독립 리뷰·재배포 뒤 정상 호스트와 잘못된 Host의 외부 응답을 재검증했다. S05 공개 경로의 동일 토큰 로그아웃 401, 실제 인증서 갱신, 전 서비스 로그 검토는 미완료다. 이 상태를 실데이터 운영 승인으로 해석하지 않는다. 외부 백업·격리 복원과 DB 마이그레이션/최소 권한 전에는 업무 원본을 입력하지 않는다.

**SSH와 CI/CD:** CI만으로 VPS 배포 경로가 생기지 않는다. 현재 Web Console 접속 기록에는 내부 root 공개키 경로가 있어 `PermitRootLogin no`를 복구 검증 없이 적용하지 않는다. `PasswordAuthentication`뿐 아니라 `KbdInteractiveAuthentication`의 유효 설정도 확인한다. 외부 22 차단은 터널과 일일 SSH 점검을 끊는다. 운영 VPS에 일반 self-hosted Actions runner나 Docker/root 권한을 배포 우회책으로 추가하지 않는다(현재 GitHub 저장소는 PUBLIC). 온디맨드 22는 ADR-020의 대체 경로를 실측한 뒤 별도 승인·롤백 절차로 검토한다.

### 최근 게이트 증거 — 2026-10-07 04:52 UTC, public 테스트 전용

- 담당: 주 에이전트 배포·외부 실측, `security-reviewer`·`infrastructure-reviewer` 독립 사전 검토. [PR #84](https://github.com/hhm0215/build-flow/pull/84)의 10개 체크와 병합 `main` CI run `37573098778` 성공. 기대/실제 SHA `9b7ea8828ea722ab2ffcee02304d89020af024f9`, VPS 작업 트리 청결. 사용자 소유 로컬 `EstimateService.java` 변경은 PR·배포에서 제외했다.
- S02/S03: `.env` 권한 메타데이터 `root:root 0600` 확인, 값 비출력. 4파일 Compose `config --quiet` 및 JSON 기반 공개 포트·CORS·Caddy 저장소 검증 PASS. 고정 host key·비root BatchMode SSH 사전 점검에서 비loopback 리스닝은 IPv4/IPv6 22/80/443만 관측했다.
- 배포 직전 실제 실행 frontend image ID를 `buildflow-frontend:pre-pr84`로 태그하고 동일 ID임을 확인했다. 서버를 정확한 병합 SHA에 detached checkout한 뒤 frontend만 단독 빌드·`--no-deps --no-build` 재생성했다. 새 frontend image ID는 보존 이미지와 다르고 restart 0/OOM false, chat image는 이전 ID 그대로 restart 0/OOM false였다. DB·업로드·다른 서비스·Caddy·SSH·방화벽은 변경하지 않았다.
- S04/S06/S10: 외부 HTTPS `/login` 200 및 TLS 검증 성공, `/api/v1/clients` 무인증 401. Gateway·8081~8087 health 200, loopback frontend 200, 루트 디스크 15%, 가용 RAM 약 2.5 GiB. 인증 세션이 만료되어 새 거래처 수정 UI의 실제 저장·재조회는 **PENDING**이며 테스트 데이터 수정은 하지 않았다. 이미지 되돌리기 실측, 새 도메인 동일 토큰 로그아웃 401, 인증서 자동 갱신, 전체 CRUD/PDF·외부 백업/격리 복원·DB 마이그레이션은 계속 PENDING이다.
- 후속 문서 전용 병합으로 `main` SHA가 앞서면 서버의 배포 코드와 차이를 다음 릴리스 게이트에서 대조한다. 문서만 갱신된 SHA를 새 애플리케이션 배포로 표시하지 않는다.

### 최근 게이트 증거 — 2026-10-06 12:12 UTC, public 테스트 전용

- 담당: 주 에이전트 배포·외부/UI 실측, `security-reviewer`·`infrastructure-reviewer` 독립 사전 검토. PR #82의 10개 체크와 병합 `main` CI run `37460143479` 성공. 기대/실제 `main` SHA `df23c6d6c99fbfd27a8b73a30e319e3d25fec998`, VPS 작업 트리 청결. 사용자 소유 로컬 견적 서비스 미커밋 변경은 PR·배포에서 제외했다.
- S02/S03: `.env` `root:root 0600` 재확인. 4파일 Compose `config --quiet`와 공개 격리 검증 PASS. 비loopback 리스닝은 IPv4/IPv6 22/80/443만. Hostinger Web Console의 ED25519 호스트키 지문을 Git 제외 전용 고정 기록과 대조한 후 비root BatchMode SSH로 확인했다. PC의 구 OS 전역 `known_hosts`는 사용하지도 수정하지도 않았다.
- S04/S06/S10: 외부 HTTPS `/login` 200·TLS 검증 성공, 새 `/api/v1/chat/availability`와 현장 API 무인증 401. 사용자 로그인된 화면에서 새 AI 미준비 안내·질문 전송 비활성화 확인. Gateway·8081~8087 health 및 loopback frontend 200, 새 chat/frontend 컨테이너 restart 0/OOM false. 루트 디스크 15%·inode 2%, 가용 메모리 약 2.6 GiB. 실제 Ollama 모델은 미기동이므로 AI 답변 기능은 **아직 사용할 수 없다**.
- 배포 전 실제 실행 chat/frontend 이미지 ID를 `pre-pr82` 태그로 보존하고, 두 이미지만 순차 단일 빌드·`--no-deps --no-build` 재생성했다. DB·업로드·Caddy·SSH·방화벽·다른 서비스는 변경하지 않았다. 이번 릴리스의 실제 롤백 실행은 하지 않았으므로 되돌리기 실측은 PENDING이다. S05 새 도메인 동일 토큰 폐기, 인증서 자동 갱신, 전체 CRUD/PDF·로그/외부 백업·격리 복원·DB 마이그레이션은 여전히 PENDING이다.
- 이 점검의 서버 SHA는 배포한 **애플리케이션 코드** 기준이다. 후속 문서 전용 병합으로 `main` SHA가 앞서면 서버의 코드 차이가 없는지 다음 배포 게이트에서 재대조한다.

### 최근 게이트 증거 — 2026-10-03 03:51 UTC, private

- 담당: 주 에이전트 UI·API 점검, `security-reviewer` 독립 코드 검토. 서버 코드 SHA `5a372f2`, 작업 트리 청결을 읽기 전용 SSH로 확인. 당시 `main` SHA `0c18b7e`와의 차이는 문서뿐이었다. 이후 `main`이 변경됐으므로 다음 배포 전 정확한 SHA 재대조가 필요하다.
- S05: SSH 터널에서 사용자 직접 로그인 후 UI 로그아웃이 로그인 화면으로 돌아왔고 `/dashboard` 재진입이 로그인 화면으로 리다이렉트됐다. 사용자 직접 대화형 `scripts/verify-admin.ps1 -BaseUrl http://127.0.0.1:13000` 실행 결과, 임시 현장 생성·조회·삭제 후 로그아웃 전의 동일 access token으로 `/api/v1/sites` 재요청이 401이었다. 스크립트는 비밀값과 토큰을 출력하지 않는다.
- 프론트 200, 무인증 현장 API 401, 공개 가입 403, Gateway·8081~8087 health `UP`. 호스트 비loopback 리스닝은 SSH 22만 관측. Docker 컨테이너 재시작/OOM과 실제 외부망 포트는 이 비root 점검에서 `UNVERIFIED`다.

### 최근 게이트 증거 — 2026-10-04 05:47 UTC, public 테스트 전용

- 담당: 주 에이전트 배포·외부 실측, `security-reviewer`와 `infrastructure-reviewer` 독립 읽기 전용 검토. 기대/실제 `main` SHA `e6f3f477928c66ed7088a6fb85446dcd70dc692d`, PR #75의 CI 5개 성공, 서버 작업 트리 청결. Caddy 실제 호스트 설정 `validate` 통과.
- 첫 공개의 잘못된 Host 200/임의 호스트로 308을 S06 `FAIL`로 판정해 볼륨 보존 private 롤백을 실행했다. private 프론트 200·보호 API 401·비loopback SSH 22만 재확인했다. 수정 후 재공개에서 정상 HTTP 308→고정 HTTPS, 공인 인증서 HTTPS 200, 잘못된 HTTP/HTTPS Host 모두 421, 무인증 보호 API 401을 외부망에서 실측했다. 인증서 자동 갱신은 아직 실측 전이다.
- 사용자 직접 공인 HTTPS 로그인 후 대시보드를 확인했고 재배포 뒤 같은 세션의 새로고침으로 시현 현장 1건을 재조회했다. 비밀번호·토큰은 수집하지 않았다. 공개 경로에서 **로그아웃 전 동일 토큰의 로그아웃 후 401**은 여전히 `PENDING`이다.
- 로그인 빈 테스트 요청 7건에서 위조 XFF 값을 바꿔도 6~7번째 429, `/api/v1/auth/login/` 403, 미허용 Origin 403을 확인했다. 비지원 GET 로그인에서 500이 관측됐고 auth-service 코드에서 405로 교정했다. 새 SHA의 VPS 배포 전까지 공개 경로 405는 `PENDING`이다. 비loopback 리스닝은 22/80/443만, 외부 검사한 내부 포트는 차단됐다. 16개 컨테이너 모두 restart 0/OOM false, 디스크 약 14%·inode 약 2% 사용, RAM 가용 약 3.4 GiB.

### 최근 게이트 증거 — 2026-10-05 04:41 UTC, 사용자 소유 도메인 공개 테스트 전용

- 담당: 주 에이전트 배포·외부 실측, `security-reviewer`와 `infrastructure-reviewer` 독립 읽기 전용 재점검. 도메인은 hPanel에서 Active·소유자 이메일 인증 완료로 확인했다. A 레코드가 VPS IPv4를 가리키고 서버와 다른 기본 AAAA 레코드는 제거했다. 도메인 자동 갱신은 사용자 요청에 따라 껐으며 만료일은 2027-10-05이다. VPS 재부팅은 하지 않았다.
- 전환 전 Hostinger 수동 스냅샷을 생성했고 hPanel 성공 표시와 다음 날 만료를 확인했다. 2026-10-04 주간 자동 백업도 존재한다. 이 둘은 DB+업로드 파일의 일관된 외부 백업·격리 복원을 대체하지 않는다.
- 기대/실제 `main` SHA `cd0f80a710a449b2d61f64e695785fd6e83b0414`, PR #80 CI 5개 성공, 서버 작업 트리 청결. 유일한 앱 코드 변경인 auth-service 이미지를 빌드·재기동했고 health `UP`, 공개 경로의 비지원 GET 로그인 405를 확인했다. 새 `PUBLIC_HOST`의 4파일 Compose 구문 및 공개 포트·CORS·Caddy 지속성 정적 검사가 통과한 뒤 gateway-server와 caddy만 재생성했다. 기존 호스트명은 단일 호스트 설정에 따라 더 이상 공개 접속 주소가 아니다.
- 외부 독립 실측: 새 HTTPS `/login` 200(시스템 TLS 신뢰 검증 통과), HTTP 308→정확한 HTTPS 주소, 무인증 현장 API 401, 잘못된 HTTP Host 및 정상 SNI+잘못된 HTTPS Host 421. 허용 Origin의 preflight 200·정확한 ACAO, 미허용 Origin 403. 호스트 IPv4/IPv6 비loopback 리스닝은 22/80/443뿐이며 8080–8087 health `UP`, loopback frontend 200·무인증 업무 API 401. 디스크 약 15%·inode 2%, 가용 RAM 약 2.8 GiB. 권한 있는 별도 확인에서 재기동한 auth/gateway/caddy는 모두 running·restart 0·OOM false였다. 외부 전 포트 스캔, 나머지 컨테이너의 재시작/OOM, 전체 비밀값 로그 점검은 `UNVERIFIED`/`PENDING`으로 유지한다.
- 새 주소에서 관리자 직접 로그인과 로그아웃 전 동일 토큰의 로그아웃 후 401, 로그인 429 재검사, 인증서 자동 갱신 실측은 `PENDING`이다. 따라서 이 기록은 테스트 데이터 전용 공개 접속 확인이며 실데이터 운영 승격이 아니다.

## 실데이터 운영 승격 게이트

- 7개 DB와 업로드 파일을 같은 쓰기 중지 시점으로 묶은 외부 백업, 체크섬/manifest, 빈 환경 격리 복원, 관리자 로그인·참조 무결성 검증.
- 서비스별 버전 마이그레이션(Flyway 등)과 Hibernate `update` 중단, 런타임 DB 계정 최소 권한. 실제 DDL 확인 전 추정 V1 작성 금지.
- 현장 CRUD/PDF 전체 동선과 실제 VPS 재부팅 복구, 릴리스 image ID/SHA와 되돌릴 수 있는 이전 이미지 기록.
- 세션 저장소/XSS 방어(CSP 등), PDF 형식·페이지·자원 제한, 의존성/이미지 취약점 및 보안 패치 평가.
- 운영 알림 수신 채널과 실패 복구 절차 검증. GitHub CI 성공은 VPS 배포 성공의 증거가 아니다.

## 반복 점검 — 보안 역할

- 매 변경/매일: Git diff의 인증·권한·입력·비밀값·로그 노출, 공개 경로·CORS·프록시 헤더, `scripts/vps/verify_public_compose.py`와 CI 결과. 새로운 Critical/High는 같은 변경에서 수정하거나 공개/배포 중단.
- 공개 후 매일: 외부 HTTPS 인증서/리다이렉트·무인증 API 401·의도한 포트만 노출되는지 read-only 확인. 로그인 429는 운영 계정 잠금/서비스 남용을 피하도록 합의한 저빈도 시나리오에서만 실행.
- 매주: OS/라이브러리/이미지 보안 권고를 공식 출처와 실제 버전으로 대조. CVE 이름만으로 적용성을 단정하지 않는다. SSH 로그인 이벤트는 IP를 공개 문서에 복제하지 않고 이상 징후만 보고.

## 반복 점검 — 인프라 역할

- 매일: 고정 host key·비root `BatchMode` SSH, Git SHA/작업 트리, `.env` 권한 메타데이터, host 리스닝, frontend 200, Gateway와 8081–8087 health, 무인증 업무 API 401, 메모리·디스크·inode. private/public 기대 포트·컨테이너 수를 구분.
- 권한 있는 점검에서만 Compose 서비스 수·restart/OOM·image ID와 프록시 오류 로그 요약을 조회. 일반 배포 계정의 Docker 권한 부족은 `UNVERIFIED`로 남기며 sudo/docker 그룹을 자동 부여하지 않는다.
- 매주: 백업 최신성·오프사이트 보존·격리 복원 증거, 패치/재부팅 계획, 롤백 이미지·커밋 매핑을 확인. 모니터가 복원·재부팅을 자동 실행하지 않는다.
- 정상·변화 없음은 조용히 유지하고 장애/복구/노출 변화/SHA 불일치/실패한 게이트만 보고한다. 발견 사항은 `.claude/BACKLOG.md`에 우선순위와 근거를 적고, 수정 PR은 기존 CI/SHA/리뷰 규칙을 따른다.

## 역할과 실행 경계

`security-reviewer`는 독립 보안 검토, `infrastructure-reviewer`는 가용성·배포·복구 검토를 담당한다. 이들은 상주 프로세스가 아니다. 작업 시 주 에이전트가 독립 서브에이전트로 위임하고 결과를 통합한다. 정기 실행은 Codex heartbeat 자동화가 같은 체크리스트를 다시 평가하도록 별도로 등록한다. 이는 앱 스케줄러·이 PC·네트워크 가용성에 의존하는 주기 점검이지 24시간 서버 감시나 장애 대응 SLA가 아니다. 자동화 알림은 Codex 앱 내 보고이며 이메일·Discord 전송은 별도 수신 채널 설정/검증 전까지 완료로 표시하지 않는다.

참고 기준: [OWASP ASVS](https://owasp.org/www-project-application-security-verification-standard/), [OWASP REST Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html), [Docker 포트 공개](https://docs.docker.com/engine/network/port-publishing/), [Caddy Automatic HTTPS](https://caddyserver.com/docs/automatic-https/).
