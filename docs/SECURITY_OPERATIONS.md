# BuildFlow 보안·인프라 점검 기준

> 2026-10-03 기준. 이 문서는 점검 항목과 판정 기준의 단일 진실원이다. `AGENTS.md`의 보안 게이트가 이 문서를 호출한다. 공개 HTTPS는 **테스트 데이터 파일럿**이며 실데이터 운영 승격과 다르다. 실제 호스트명·IP·키·비밀값·덤프는 Git 추적 문서와 점검 결과에 적지 않는다.

## 판정과 기록

- 각 항목을 `PASS`(실측 근거), `FAIL`(기준 위반), `PENDING`(아직 미실행), `UNVERIFIED`(권한·접속 부족), `N/A`(이유 명시)로 기록한다. Compose 정적 검증만으로 외부 차단을 `PASS`로 표시하지 않는다.
- 매 실행에 UTC 시각, private/public 단계, 기대·실제 Git SHA, 점검 명령 또는 CI 링크, 관측 결과, 담당 역할을 기록한다. 민감한 출력은 요약만 남긴다.
- `FAIL`이 인증·TLS·비밀값·공개 포트·백업 무결성에 걸리면 배포/데이터 입력을 중단한다. 점검자는 위험을 수정 제안할 수 있지만 포트 개방, 방화벽·SSH 변경, 재부팅, 볼륨 삭제, 복원, 운영 배포를 정기 작업으로 자동 실행하지 않는다.
- 공개 파일럿에서 인증서·로그인·포트 검증에 실패하면 `docs/VPS_PUBLIC_PILOT.md`의 **볼륨 보존** 롤백을 사용한다. 실데이터 운영은 별도 게이트를 모두 통과할 때까지 금지한다.

## 공개 HTTPS 파일럿 사전 게이트

| ID | 점검 | 통과 근거 | 현재 상태 |
|---|---|---|---|
| S01 | CI 검증된 `main` SHA와 VPS checkout/빌드 대상 일치, 작업 트리 청결 | PR CI·SHA 대조 및 서버 `git status` | PASS(private) — PR #69 merge SHA `5a372f2` 서버 고정, frontend 단일 재빌드 |
| S02 | `.env`와 키 보호 | 서버 `.env` 소유/모드 `root:root 0600`, 값 비출력; 개인키 미복사 | PASS — 서버 메타데이터 확인 |
| S03 | 내부 서비스 포트 비공개 | Compose 검증 + 호스트 IPv4/IPv6 리스닝 + 외부망 접속 확인. public은 22/80/443만, private은 22만 | PENDING — 정적/호스트 private 통과, public 실측 전 |
| S04 | 관리자 인증 | 공개 회원가입 없음; 무인증 업무 API 401; 로그인 후 권한 경계 | PASS(private) / PENDING(public) |
| S05 | 로그아웃 토큰 폐기 | UI 로그아웃이 서버 API를 호출하고 **로그아웃 전 발급된 동일 토큰** 재사용이 401 | PASS(private) / PENDING(public 재확인) — 2026-10-03 터널 UI 로그아웃·보호 화면 재진입 차단, 대화형 API 스모크에서 동일 토큰 401 실측 |
| S06 | TLS·호스트 | 공인 신뢰 인증서, 호스트명 일치, HTTP→HTTPS, 만료/갱신 경로; 잘못된 Host/SNI 거부 | PENDING — 80/443 미기동 |
| S07 | 웹 남용·CORS | 실제 공개 경로의 로그인 429, 변형 경로/위조 XFF 우회 실패, 미허용 Origin 거부 | PENDING — 설정·CI만 통과 |
| S08 | SSH 복구·하드닝 | 일반 계정 키·Web Console 복구 확인 후 root/password/keyboard-interactive 정책과 `sshd -t`·별도 재접속 검증. 외부 22 차단 시 독립 배포·점검·알림·원격 재개방 실측 | PENDING — 현재 root/password 허용; 터널·점검은 SSH 의존; 무검증 변경 금지 |
| S09 | OS·의존성 패치 | 보안 업데이트/커널 상태와 영향 평가, 재부팅 시 서비스 복귀 검증 | PENDING — 커널 업데이트 대기, 리부팅 미검증 |
| S10 | 리소스·로그 | 실제 서비스 health, frontend 200, 보호 API 401, OOM/restart, 디스크·메모리, 비밀값 로그 없음 | PASS(private 기본 health) / PENDING(public) |
| S11 | 실패 복귀 | SSH 터널 200/401 보존, 공개 실패 시 Caddy 제거 후 private 복귀, named volume 유지 | PENDING — 런북만 작성 |

**공개 결정:** S05는 비공개 경로에서 통과했지만 공개 경로 재확인이 필요하다. S06/S07 공인 경로, S08 SSH 정책, S09 패치·재부팅 검증이 끝나지 않아 80/443 개방을 보류한다. S06/S07의 실제 인증서·포트 검증은 나머지 사전 조건을 충족한 뒤 제한된 공개 검증 창에서 수행하고, 실패 시 즉시 비공개로 복귀한다. S08 변경은 복구 경로와 명시적 승인을 확인한 뒤에만 수행한다. 남은 위험을 승인 없이 `PASS`로 바꾸지 않는다.

**SSH와 CI/CD:** CI만으로 VPS 배포 경로가 생기지 않는다. 현재 Web Console 접속 기록에는 내부 root 공개키 경로가 있어 `PermitRootLogin no`를 복구 검증 없이 적용하지 않는다. `PasswordAuthentication`뿐 아니라 `KbdInteractiveAuthentication`의 유효 설정도 확인한다. 외부 22 차단은 터널과 일일 SSH 점검을 끊는다. 운영 VPS에 일반 self-hosted Actions runner나 Docker/root 권한을 배포 우회책으로 추가하지 않는다(현재 GitHub 저장소는 PUBLIC). 온디맨드 22는 ADR-020의 대체 경로를 실측한 뒤 별도 승인·롤백 절차로 검토한다.

### 최근 게이트 증거 — 2026-10-03 03:51 UTC, private

- 담당: 주 에이전트 UI·API 점검, `security-reviewer` 독립 코드 검토. 서버 코드 SHA `5a372f2`, 작업 트리 청결을 읽기 전용 SSH로 확인. 당시 `main` SHA `0c18b7e`와의 차이는 문서뿐이었다. 이후 `main`이 변경됐으므로 다음 배포 전 정확한 SHA 재대조가 필요하다.
- S05: SSH 터널에서 사용자 직접 로그인 후 UI 로그아웃이 로그인 화면으로 돌아왔고 `/dashboard` 재진입이 로그인 화면으로 리다이렉트됐다. 사용자 직접 대화형 `scripts/verify-admin.ps1 -BaseUrl http://127.0.0.1:13000` 실행 결과, 임시 현장 생성·조회·삭제 후 로그아웃 전의 동일 access token으로 `/api/v1/sites` 재요청이 401이었다. 스크립트는 비밀값과 토큰을 출력하지 않는다.
- 프론트 200, 무인증 현장 API 401, 공개 가입 403, Gateway·8081~8087 health `UP`. 호스트 비loopback 리스닝은 SSH 22만 관측. Docker 컨테이너 재시작/OOM과 실제 외부망 포트는 이 비root 점검에서 `UNVERIFIED`다.

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
