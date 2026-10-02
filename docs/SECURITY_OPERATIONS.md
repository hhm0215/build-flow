# BuildFlow 보안·인프라 점검 기준

> 2026-10-02 기준. 이 문서는 점검 항목과 판정 기준의 단일 진실원이다. `AGENTS.md`의 보안 게이트가 이 문서를 호출한다. 공개 HTTPS는 **테스트 데이터 파일럿**이며 실데이터 운영 승격과 다르다. 실제 호스트명·IP·키·비밀값·덤프는 Git 추적 문서와 점검 결과에 적지 않는다.

## 판정과 기록

- 각 항목을 `PASS`(실측 근거), `FAIL`(기준 위반), `PENDING`(아직 미실행), `UNVERIFIED`(권한·접속 부족), `N/A`(이유 명시)로 기록한다. Compose 정적 검증만으로 외부 차단을 `PASS`로 표시하지 않는다.
- 매 실행에 UTC 시각, private/public 단계, 기대·실제 Git SHA, 점검 명령 또는 CI 링크, 관측 결과, 담당 역할을 기록한다. 민감한 출력은 요약만 남긴다.
- `FAIL`이 인증·TLS·비밀값·공개 포트·백업 무결성에 걸리면 배포/데이터 입력을 중단한다. 점검자는 위험을 수정 제안할 수 있지만 포트 개방, 방화벽·SSH 변경, 재부팅, 볼륨 삭제, 복원, 운영 배포를 정기 작업으로 자동 실행하지 않는다.
- 공개 파일럿에서 인증서·로그인·포트 검증에 실패하면 `docs/VPS_PUBLIC_PILOT.md`의 **볼륨 보존** 롤백을 사용한다. 실데이터 운영은 별도 게이트를 모두 통과할 때까지 금지한다.

## 공개 HTTPS 파일럿 사전 게이트

| ID | 점검 | 통과 근거 | 2026-10-02 상태 |
|---|---|---|---|
| S01 | CI 검증된 `main` SHA와 VPS checkout/빌드 대상 일치, 작업 트리 청결 | PR CI·SHA 대조 및 서버 `git status` | PASS — PR #68 SHA `49f7274` 서버 고정 |
| S02 | `.env`와 키 보호 | 서버 `.env` 소유/모드 `root:root 0600`, 값 비출력; 개인키 미복사 | PASS — 서버 메타데이터 확인 |
| S03 | 내부 서비스 포트 비공개 | Compose 검증 + 호스트 IPv4/IPv6 리스닝 + 외부망 접속 확인. public은 22/80/443만, private은 22만 | PENDING — 정적/호스트 private 통과, public 실측 전 |
| S04 | 관리자 인증 | 공개 회원가입 없음; 무인증 업무 API 401; 로그인 후 권한 경계 | PASS(private) / PENDING(public) |
| S05 | 로그아웃 토큰 폐기 | UI 로그아웃이 서버 API를 호출하고 **로그아웃 전 발급된 동일 토큰** 재사용이 401 | FAIL — 기존 UI는 로컬 상태만 삭제, 수정·회귀 검증 전 공개 보류 |
| S06 | TLS·호스트 | 공인 신뢰 인증서, 호스트명 일치, HTTP→HTTPS, 만료/갱신 경로; 잘못된 Host/SNI 거부 | PENDING — 80/443 미기동 |
| S07 | 웹 남용·CORS | 실제 공개 경로의 로그인 429, 변형 경로/위조 XFF 우회 실패, 미허용 Origin 거부 | PENDING — 설정·CI만 통과 |
| S08 | SSH 복구·하드닝 | 일반 계정 키·Web Console 복구 확인 후 root/password 정책과 `sshd -t`·별도 재접속 검증 | PENDING — 현재 root/password 허용; 무검증 변경 금지 |
| S09 | OS·의존성 패치 | 보안 업데이트/커널 상태와 영향 평가, 재부팅 시 서비스 복귀 검증 | PENDING — 커널 업데이트 대기, 리부팅 미검증 |
| S10 | 리소스·로그 | 실제 서비스 health, frontend 200, 보호 API 401, OOM/restart, 디스크·메모리, 비밀값 로그 없음 | PASS(private 기본 health) / PENDING(public) |
| S11 | 실패 복귀 | SSH 터널 200/401 보존, 공개 실패 시 Caddy 제거 후 private 복귀, named volume 유지 | PENDING — 런북만 작성 |

**공개 결정:** S05는 현재 실패이므로 80/443 개방을 보류한다. 수정·CI·서버 반영 후 S01–S11을 다시 실측한다. S08/S09는 테스트 파일럿에서도 우선 개선하며, 변경 전 복구 경로를 검증한다. 남은 위험을 승인 없이 `PASS`로 바꾸지 않는다.

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

`security-reviewer`는 독립 보안 검토, `infrastructure-reviewer`는 가용성·배포·복구 검토를 담당한다. 이들은 상주 프로세스가 아니다. 작업 시 주 에이전트가 독립 서브에이전트로 위임하고 결과를 통합한다. 정기 실행은 Codex heartbeat 자동화가 같은 체크리스트를 다시 평가하도록 별도로 등록한다. 자동화 알림은 Codex 앱 내 보고이며 이메일·Discord 전송은 별도 수신 채널 설정/검증 전까지 완료로 표시하지 않는다.

참고 기준: [OWASP ASVS](https://owasp.org/www-project-application-security-verification-standard/), [OWASP REST Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html), [Docker 포트 공개](https://docs.docker.com/engine/network/port-publishing/), [Caddy Automatic HTTPS](https://caddyserver.com/docs/automatic-https/).
