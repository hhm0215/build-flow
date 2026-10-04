# BuildFlow 개발 진행상황

> 이 파일은 매 작업 세션 시작/종료 시 업데이트합니다.
> Claude와 대화 시작할 때 "PROGRESS.md 읽어줘"라고 하면 빠르게 컨텍스트 복원 가능.

## 현재 브랜치: `develop`

## 현재 진행 중 — SQL+업로드 파일 복구 게이트 (2026-10-04)

- 기존 SQL-only v1 백업/격리 복원의 성공 메시지에 업로드 파일·Kafka 미포함을 명시했다. 통합 v2 패키지는 실제 복원 DB의 `defect_warranties.file_path`와 파일을 대조하기 전까지 검증·복원을 거부한다.
- 업로드 파일만의 오프라인 무결성 진단과 합성 실패 회귀를 준비했다. 이 진단은 manifest 내부의 참조만 검사하며 SQL 참조는 검사하지 않는다. 보안 역할 검토에서 처음 발견한 잘못된 v2 전체 성공 판정을 fail-closed로 수정했다.
- 로컬 Docker Engine에 접속하지 못해 실제 volume snapshot/통합 복원은 아직 실행하지 않았다. 외부 암호화 사본, OCR `PENDING`, Kafka 미소비 이벤트의 복구/재조정도 실데이터 게이트 `PENDING`이다. USB 원본과 VPS는 변경하지 않았다.

## 현재 진행 중 — USB 견적 원본 목록화 (2026-10-04)

- USB 원본을 변경하거나 VPS에 전송하지 않는 로컬 읽기 전용 `견적서/2026` `.xlsx` 목록화 도구를 작성했다. PowerShell 5.1/7 합성 회귀에서 SHA 동일성, 한글 경로, 숨김 파일, 파일 수 제한, junction 거부, 출력 ACL, 기존 배치 비덮어쓰기를 확인했다. 보안 역할의 독립 재검토에서 지정 폴더 파일럿의 추가 차단 결함은 없었다.
- 실제 지정 폴더는 일반 목록 108개·숨김 포함 169개다. 두 독립 목록화 배치의 상대 경로·크기·수정 시각·SHA-256이 모두 일치했고 오류 0건, 로컬 출력 ACL 상속 차단·Git 제외를 확인했다. 숨김 61개는 관계/업무 가져오기에서 보류한다. 파일명·업체명·해시는 Git/채팅에 기록하지 않았다.
- DB-only 백업의 오프라인 검증은 통과했으나 업로드 파일을 묶은 외부 백업·격리 복원은 아직 없다. 따라서 본 목록을 실데이터 운영 게이트 통과, VPS 사본 보관 또는 손익 확정으로 간주하지 않는다. 후속 구현 순서는 DB+파일 통합 백업/복원과 마이그레이션, 불변 원본 저장, 검토/정정 이력이다.

## 최신 운영 상태 — 2026-10-04 05:55 UTC

- PR #75의 CI 5개 성공·독립 보안 검토 후 `main` merge SHA `e6f3f477928c66ed7088a6fb85446dcd70dc692d`를 VPS clean detached checkout에 적용했다. 실행 환경은 16개 컨테이너의 **공개 HTTPS 테스트 데이터 파일럿**이다. 후속 문서 PR로 `main` SHA가 진전되면 서버 SHA 차이는 문서 전용인지 재대조한다.
- 첫 공개에서 잘못된 Host의 빈 200/임의 호스트 리다이렉트를 발견해 볼륨 보존 private 롤백을 수행했다. 수정 재배포 뒤 공인 신뢰 HTTPS 200, 정상 HTTP 308, 잘못된 HTTP·HTTPS Host 421, 무인증 업무 API 401을 외부망에서 확인했다. 사용자 직접 로그인·대시보드 조회와 재배포 후 세션 새로고침도 성공했다.
- 서버 비loopback 리스닝은 SSH 22·웹 80/443뿐이고, 내부 서비스 외부 포트는 차단됐다. Gateway·8081~8087 health 모두 200, 16개 컨테이너 restart 0/OOM false, 디스크 14%·inode 2%, RAM 가용 약 3.4 GiB. SSH 비밀번호/kbd-interactive는 차단했으며 root 키·22는 복구/터널 때문에 유지한다.
- 남은 게이트: 공개 경로의 로그아웃 전 **동일 토큰** 재사용 401, 전체 CRUD/PDF, 외부 DB+업로드 파일 백업·격리 복원, 서비스별 버전 마이그레이션/최소 권한 DB 계정. 이 전에는 업무 원본을 VPS에 입력하지 않는다. 비지원 GET 로그인 500은 별도 백로그다. 과거 날짜의 아래 상태 기록은 당시 스냅샷이며 최신 상태를 대체하지 않는다.

## 현재 진행 중 — auth Flyway 격리 파일럿 (2026-10-03)

- 사용자의 추가 수동 실행 요청을 멈추고 P1 DB 마이그레이션의 독립 진행 가능 부분을 시작했다. 실제 2026-09-29 MySQL no-data dump의 `admin_accounts` DDL로 V1을 작성하고, 기본 Flyway 비활성화·전용 `auth-flyway` 프로파일·MySQL 8 CI 통합 검증을 준비했다. 기존 로컬·VPS DB, 관리자 데이터, 프로파일은 변경하지 않았다.
- auth-service 단위 테스트와 Flyway core/MySQL 모듈 10.10.0 의존성 확인 통과. 빈 DB CI 결과와 기존 DB 명시적 baseline은 별도 게이트다. Phase B 복원 관리자 로그인은 여전히 PENDING이며 새 파일럿으로 대체하지 않는다.

## 현재 진행 중 — CI와 SSH 접근 분리 (2026-10-03)

- PR #72의 8개 CI를 확인해 `main`·`develop`을 merge SHA `7f1b780`으로 동기화했다. API 명세 48개 실제 라우트 정렬과 알림 응답 계약 수정을 포함하지만 VPS는 아직 이전 SHA `5a372f2`이므로 해당 수정이 배포됐다고 보지 않는다.
- 현재 CI는 테스트·빌드만 실행하며 VPS 배포 job은 없다. ADR-020과 S08에 터널·일일 점검의 SSH 의존성, 온디맨드 22 차단의 선행조건, 공개 저장소의 운영 VPS self-hosted runner 위험을 기록했다. `main` 병합 후 정확한 SHA도 CI에서 재검증하고 토큰을 읽기 전용으로 제한하는 변경을 준비했다. SSH·방화벽·VPS 배포는 변경하지 않았다.
- 로컬 Docker의 격리 임시 volume 복원에서 7개 스키마·manifest/행 수/digest/`CHECK TABLE`·auth `ddl-auto=validate`·원본 DB 불변을 재확인했다. 관리자 비밀번호를 직접 입력하는 Phase B 로그인·로그아웃 검증은 사용자 실행 결과를 기다린다.

## 현재 진행 중 — VPS 비공개 인증 실측 (2026-10-03)

- 고정 host key·비root SSH로 서버 clean checkout `5a372f2`, `.env` `root:root 0600`, 비loopback SSH 22만 리스닝, 프론트 200, 무인증 현장 API 401, Gateway·8081~8087 health `UP`을 재확인했다. 당시 `main` `0c18b7e`와 차이는 문서 변경뿐이었고 서버에 공개 프록시는 없었다. 이후 코드 변경이 병합됐으므로 현재 SHA 차이는 위 기록을 따른다. 커널 `6.8.0-146` 업데이트와 재부팅은 여전히 대기 중이다.
- 사용자 직접 로그인한 SSH 터널 웹에서 대시보드·현장·견적·매입·세금계산서·보증보험·알림의 목록 화면을 확인했다. UI 로그아웃은 로그인 화면으로 이동했고 보호 대시보드 재진입을 막았다. 터널 API는 무인증 401, 공개 가입 403을 반환했다.
- `scripts/verify-admin.ps1`에 로그아웃 후 **동일 access token**의 보호 API 401 확인을 추가했다. 사용자 직접 대화형 실행에서 관리자 로그인·임시 현장 생성/조회/삭제·이전 토큰 401이 모두 통과했다. 모의 성공/실패 PowerShell 테스트와 인증·Gateway Gradle 테스트, 구문 검사가 통과했다. `security-reviewer` 독립 검토는 차단 결함 없음. S05는 private PASS, public 재확인은 대기 중이다.
- PR #70 문서 후속 작업은 8개 CI 통과 후 `main` merge SHA `0c18b7e`로 병합했고 `develop`도 fast-forward했다. 80/443 공개, SSH 정책 변경, 커널 패치·재부팅, 전체 업무 CRUD/PDF 및 DB+파일 외부 백업·복원은 아직 수행하지 않았다.

## 현재 진행 중 — 공개 전 보안·인프라 하네스 (2026-10-02)

- 공개 전 독립 보안·인프라 리뷰에서 UI 로그아웃이 서버 토큰을 폐기하지 않는 문제와 Compose 포트 검증의 host-network/권한 우회 가능성을 확인했다. `docs/SECURITY_OPERATIONS.md`에 `PASS/FAIL/PENDING/UNVERIFIED` 근거와 공개/실데이터 게이트를 분리하고, `AGENTS.md`·`CLAUDE.md`·`.claude/agents/`에 두 역할을 연결했다.
- 프론트 로그아웃은 서버 성공 확인 후에만 로컬 상태를 삭제하도록 수정했고 실패 시 토큰을 유지해 재시도한다. Compose 검증기에 host networking·privileged·Docker socket 마운트 차단을 추가했다. 로컬 lint, 프론트 34파일 118테스트/빌드, Python 11테스트, 4파일 Compose 검증 통과. 테스트·빌드는 Windows 샌드박스 상위 경로 접근 제약으로 권한 확장 재실행해 성공했다.
- Codex 앱의 일일 보안/인프라 읽기 전용 heartbeat 두 건을 등록했다(현지 09:00/09:15 의도). 정상/변화 없음은 조용히, 실패·노출 변화·복구·사용자 판단 필요 시 보고한다. 메일/Discord 수신 채널은 아직 연결되지 않았다.
- **80/443 공개는 여전히 미기동.** 이후 2026-10-03 비공개 경로의 로그아웃 전 토큰 재사용 401을 실측했다. 공인 TLS/포트, SSH root/password, 커널 업데이트·재부팅과 실데이터 운영 게이트는 별도 미해결 항목이다.
- [PR #69](https://github.com/hhm0215/build-flow/pull/69)은 8개 CI와 local/origin/PR head `0264eb8` 대조 후 merge commit `5a372f2`로 병합했고 `develop`도 fast-forward했다. VPS 서버는 clean checkout을 `5a372f2`에 고정하고 직전 **빌드된** frontend image(`buildflow-frontend:latest`)에 `pre-pr69` 태그를 남긴 뒤 frontend만 재빌드·재기동했다. 앞서 실행 중이던 더 오래된 image ID `542aedda26da`는 저장소에서 이미 제거되어 직접 태그할 수 없었으므로, 이 태그를 실제 직전 실행 이미지의 완전한 롤백 증거로 보지 않는다. private 15개 실행, frontend 200, 무인증 sites 401, 비loopback SSH 22만 확인했다. 공개 Caddy는 아직 기동하지 않았다.
- SSH 정책 변경은 원격 잠금 위험으로 자동 안전 검토가 중단했다. 서버 설정 파일은 생성되지 않았고 정책도 그대로다. Web Console root 경로와 비root 키 재접속은 확인했으며, 사용자에게 설정 범위·복구 절차를 명시해 승인을 요청했다. 커널 패치/재부팅도 아직 수행하지 않았다.

## 현재 진행 중 — VPS 공개 HTTPS 테스트 파일럿 (2026-10-01)

- 사용자가 Tailscale 대신 외부 브라우저의 공개 HTTPS를 선택했고, 자체 도메인 없이 VPS 기본 호스트명으로 공인 인증서 발급 가능성을 실측하기로 했다. ADR-019에 따라 **테스트 데이터만** 접속하며 실데이터 운영 게이트와 CI/CD 자동 배포는 아직 열지 않는다.
- 80/443 Caddy 프록시 override, Gateway HTTPS CORS, 프론트 로그인 API IP별 제한, Compose 공개 포트 검증기·CI 구문 검사와 운영/롤백 런북을 준비했다. 실제 VPS는 현재 기존 15개 컨테이너와 SSH 22만 공개된 상태다. Web Console 새 세션으로 root 접근을 확인했다.
- VPS 테스트 현장 1건 생성·수정에서 대시보드 수치가 SPA 이동 직후 갱신되지 않는 캐시 문제를 발견해 현장 변이 후 대시보드 캐시 무효화와 4개 회귀 테스트를 추가했다. 테스트 현장 삭제와 HTTPS 실측은 아직 남았다.
- 프론트 lint, 33파일 112테스트, 프로덕션 빌드, Python 검증기 5테스트, 4파일 Compose 병합·공개 포트 검증을 통과했다. Docker Engine이 꺼진 로컬에서는 Caddy/Nginx 이미지 구문 검사를 실행하지 못해 CI에 연결했다. 보안·배포 독립 리뷰에서 stale server checkout 위험을 발견해 `origin/main` 고정과 SHA 대조를 런북에 추가했다.
- [PR #68](https://github.com/hhm0215/build-flow/pull/68)은 공개 HTTPS 설정·검증·런북 및 대시보드 캐시 수정을 포함했고, push/PR 8개 CI 검사와 PR/원격 두 커밋 SHA 대조 후 merge commit `49f7274`로 병합했다. `develop`도 같은 커밋으로 fast-forward했다. VPS 공개 적용은 사용자 확인과 공인 인증서 실측 전이라 아직 수행하지 않았다.
- 2026-10-02 서버의 단일 `develop` clone은 일반 fetch 후 `origin/main`이 없어 명시적 refspec으로 `main`을 가져왔다. 작업 트리 청결·서버 `.env` `root:root 0600`·릴리스 `49f7274` SHA 일치 확인 후 detached checkout했다. 서버 Compose의 공개 포트/CORS 검증, 프론트 이미지 단일 빌드, 기존 15개 컨테이너 실행·Gateway health `UP`을 확인했다. **80/443은 아직 미기동**이며 공개 확인 답변을 기다린다.

## 현재 진행 중 — 실데이터용 DB 마이그레이션 기반 Phase B (2026-09-30)

- Phase A와 Phase B의 격리 실제 복원·무결성·auth `ddl-auto=validate` 기동·전후 DB 불변 검증을 완료했다.
- 2026-10-03 Docker Desktop 재기동 후 검증된 로컬 백업을 다시 임시 프로젝트·볼륨에 복원해 정확한 행 수·digest·`CHECK TABLE`·인증 서비스 기동·전후 불변을 재확인했다. 검증용 프로젝트와 볼륨만 제거했고 기존 앱 볼륨은 건드리지 않았다.
- Phase B는 관리자 비밀번호를 저장하지 않는 대화형 로그인·로그아웃 1건만 남았다. 통과 후 auth-service Flyway 파일럿으로 진행하며 실제 DDL 확인 전에는 V1을 추정 작성하지 않는다.

## 현재 진행 중 — VPS 비공개 파일럿 (2026-10-01)

- 사용자가 기존 VPS에 보존할 데이터·서비스가 없음을 확인했고, Hostinger 주간 백업 2026-09-27/20 두 건을 확인했다. 복원은 옛 Docker·Traefik 템플릿과 SSH 상태 전체로 돌아가는 임시 안전망이며 자동 순환된다.
- Ubuntu Plain OS용 비공개 SSH 터널 배포 override·런북·서버 전용 비밀값/관리자 초기화 도구를 준비했다. Compose 15개 서비스 정적 검증(외부 공개 포트 0), 독립 보안 리뷰, 전체 Gradle 41 tasks, 프론트 lint·108 tests·build를 통과했다.
- Hostinger에서 두 주간 백업을 다시 확인하고 사용자가 최종 삭제 경고 및 새 root 비밀번호 단계를 직접 완료해 Plain OS Ubuntu 24.04 LTS로 전환했다. Web Console은 Ubuntu 24.04.5 LTS와 새 ED25519 host key 지문을 확인했다. 기존 Docker·Traefik/OpenClaw 템플릿은 현재 OS가 아니다.
- `buildflow-deploy` 일반 계정에 기존 PC 공개키를 등록하고 서버·PC 지문, 소유권·권한, 고정 host key를 사용한 별도 SSH 키 로그인을 검증했다. sudo·Docker 그룹 권한은 주지 않았다. Docker 공식 apt 저장소의 Engine 29.8.2/Compose 5.5.1을 설치하고 공개 `develop` 코드를 root 소유 경로에 clone했다. 서버 전용 DB/JWT 비밀값을 `root:root 0600` `.env`에 생성하고 VPS Compose 설정에서 15개 published port 전부 loopback임을 검증했다. Compose의 `COMPOSE_PARALLEL_LIMIT=1`만으로는 이미지 빌드가 직렬화되지 않아 첫 병렬 빌드 작업만 중단하고, 서비스별 단일 빌드로 11개 앱 이미지를 성공시켰다. MySQL·Redis·Kafka 포함 15개 컨테이너가 실행되고 8081~8087/Gateway health `UP`, 프론트 200, 비인증 현장 API 401, 재시작/OOM 0, 비loopback published port 0을 확인했다. Gateway 초기 healthy까지 약 144초 소요. 사용자가 Web Console에서 대화형 관리자 계정을 직접 생성했고 성공 메시지를 확인했다. PC의 SSH 터널 URL에서 웹 로그인 후 대시보드·빈 현장 목록 표시를 확인했다. CRUD/PDF·재부팅 및 DB+파일 복구 검증은 남았다. 로컬 Docker Desktop Engine은 꺼져 있다. AI 모델/공개 도메인/업무 데이터 입력은 후속 게이트다. 공개 절차는 `docs/VPS_PRIVATE_PILOT.md`, 실제 식별자는 Git 제외 로컬 기록을 따른다.
- 사용자와 ADR-018로 로컬 개발(`develop`)·GitHub CI·단일 VPS 운영(`main` 릴리스) 분리를 확정했다. CI/CD 배포 자동화는 DB/파일 복원과 마이그레이션 게이트 뒤에 구현하며, 같은 VPS에 개발용 전체 스택을 상시 이중 기동하지 않는다.
- [PR #66](https://github.com/hhm0215/build-flow/pull/66)은 VPS 기동 실측 문서와 순차 빌드 스크립트를 포함했고, CI 6개 성공 및 PR/원격 3개 커밋 SHA 일치 확인 후 merge commit `0b5c931`으로 병합했다. `develop`도 같은 커밋으로 fast-forward했다. [PR #65](https://github.com/hhm0215/build-flow/pull/65)는 앞선 OS/SSH 기록을 병합했다.
- [PR #67](https://github.com/hhm0215/build-flow/pull/67)은 ADR-018 운영 경계와 VPS 로그인 확인 기록을 포함했다. CI 6개 통과·PR/원격 커밋 3개 SHA 일치 후 merge commit `c8306f5`로 병합했고 `develop`도 fast-forward했다. VPS에는 이 문서 전용 커밋을 재배포하지 않았다.

---

## 완료된 작업

### ✅ API 명세·알림 프론트 계약 동기화 (2026-10-03)

- Controller/DTO, Gateway 명시 라우트, 프론트 호출을 대조해 `docs/API_SPEC.md`의 구현 엔드포인트·요청/응답·인증을 다시 작성했다. 서버 페이징/파일 다운로드/채팅 세션 REST처럼 존재하지 않는 경로는 미구현 계획으로 분리했다.
- 서버 알림 JSON의 `eventType`·`read`와 미읽음 수 `data.count`를 프론트가 `type`·숫자로 정규화하도록 수정하고 MSW를 실제 서버 계약에 맞췄다. 백엔드 Jackson 직렬화 및 프론트 API 회귀 테스트를 추가했다. 프론트 lint·35파일 120테스트·빌드와 notification-service 직렬화 테스트를 통과했다. 번들 크기 경고는 기존 P2 과제다.

### ✅ VPS 비공개 파일럿 코드·런북 준비 (실제 배포는 진행 중, 2026-10-01)
- `docker-compose.vps.yml`에 SSH 터널용 loopback 공개 정책을 유지하며 재시작·메모리·JVM heap·로그 회전과 Linux AI 내부 주소를 추가했다. 서버 전용 랜덤 `.env` 생성, 일회성 비웹 관리자 초기화, Hostinger OS/SSH/복구 런북을 준비했다.
- 프론트 프록시의 업로드 한도·Host 포트 전달과 백엔드 multipart 여유를 맞추고 Gateway 터널 origin을 허용했다. Compose 15개 서비스 병합에서 공개 포트 0, 자원/재시작 누락 0을 확인했다. 독립 보안 리뷰와 전체 Gradle 41 tasks, 프론트 lint·108 tests·build를 통과했다. 컨테이너 실기동은 Docker Engine이 꺼져 있어 아직 미검증이다.
- [PR #64](https://github.com/hhm0215/build-flow/pull/64)는 CI 6개 성공과 로컬·원격·PR head 및 커밋 3개 SHA 일치 후 merge commit `ced4fca`로 병합했다. VPS OS/서비스는 변경하지 않았다.

### ✅ DB 마이그레이션 기반 Phase B — 격리 복원 핵심 검증 (로그인 제외, 2026-09-30)
- 기존 Compose의 고정 `container_name`·network name을 재사용하지 않는 전용 MySQL/Redis/auth Compose와 무작위 project·nonce 기반 복원 도구를 추가했다. 임시 MySQL은 호스트 포트를 열지 않으며 container·volume label과 원본 불일치를 확인한 뒤에만 복원·자동 정리를 허용한다.
- 기준 백업 `20260929T125754Z`를 실제 복원해 7개 스키마·19개 테이블·정확한 행 수·Flyway history 유무·관리자 비노출 digest·모든 `CHECK TABLE ... status OK`를 manifest와 대조했다.
- 복원 auth-service는 Config/Eureka를 끄고 SQL init 금지·Hibernate `ddl-auto=validate`로 기동해 health `UP`을 확인했다. 기동 후 동일 무결성 검사를 다시 수행해 DB 불변을 확인한다.
- 임시 project의 container·volume이 모두 제거되고 운영 환경은 15개 컨테이너 정상, 관리자 1행·견적/현장/매입/세금 0행 그대로임을 확인했다. 실제 관리자 로그인·로그아웃은 대화형 `-ValidateLogin` 실행만 남았다.
- Windows CI의 PowerShell 검사를 `scripts/` 전체 재귀로 확장하고 Docker 없는 백업 도구 회귀를 연결했다. Windows PowerShell 5.1의 빈 JSON 배열·배열 파이프라인 차이도 회귀에서 발견해 수정했다. 로컬 전체 Gradle 41 tasks, 프론트 lint·32파일 108테스트·프로덕션 빌드, 전체 PowerShell parser와 실제 격리 복원을 통과했다. 되돌릴 수 있는 UI 시현과 확정/입금/OCR 샌드박스 경계를 `docs/DEMO_GUIDE.md`에 기록했다. PR #63은 CI 6개 성공과 head SHA 일치를 확인한 뒤 main에 병합했다.

### ✅ Docker Desktop 런타임 복구 및 새 로컬 환경 기준선 (2026-09-29)
- Docker Desktop 4.91의 `sailor-ingest.sock`/`engine.sock` Windows 재분석 지점 오류를 DB·volume과 무관한 임시 런타임 소켓 장애로 확인했다. Docker 프로세스를 완전히 종료한 뒤 `%LOCALAPPDATA%`의 두 런타임 폴더만 삭제 없이 quarantine 이름으로 이동해 Engine 29.8.0을 복구했다.
- 공장 초기화 전 대응 절차를 `scripts/repair-docker-runtime.ps1`과 Windows 설정 문서에 고정했다. 스크립트는 Docker 실행 중 중단하고, 고정된 두 경로만 같은 상위 폴더로 이동하며 이미지·컨테이너·volume·WSL 데이터를 건드리지 않는다.
- 기존 DB를 먼저 백업한 뒤 BuildFlow Compose의 네 volume만 제거해 새 환경을 구축했으며, 다른 Compose 프로젝트의 컨테이너와 volume은 보존했다. 기존 관리자 계정 1명만 digest가 일치하도록 복원하고 견적·현장·매입·세금·알림·채팅 업무 데이터는 0행으로 시작했다.
- 새 기준 백업 `backups/db/20260929T125754Z`를 생성·재검증했다. auth 1행 외 estimate/site/purchase/tax/notification/chat은 모두 0행이며, 백업 파일은 Git 제외 상태다.
- 전체 15개 컨테이너를 재기동해 API 8081~8087 health `UP`과 프론트 3000 HTTP 200을 확인했다. 기존 `qwen2.5:7b-instruct`에 서비스가 요구하는 `qwen2.5:7b` 별칭을 추가했고 Ollama API도 정상이다. `buildflow.ps1 check`의 Ollama 확인은 멈출 수 있는 CLI 호출 대신 3초 제한 HTTP 조회로 전환했다.

### ✅ DB 마이그레이션 기반 Phase A — 백업·오프라인 검증 도구 (완료, 2026-09-29)
- 7개 BuildFlow 스키마 고정 allowlist, 실제 Compose mysql container/working directory/image/named volume label 검증, DB writer·외부 연결 차단 확인 후 `mysqldump`를 수행하는 PowerShell 도구를 추가했다.
- dump SHA-256·byte size·스키마/테이블별 exact row count·Flyway history 유무·단일 관리자 비노출 digest와 원본 volume 신원을 manifest로 기록한다. 백업 전후 inventory/digest가 달라지면 승인하지 않는다.
- stderr/stdout을 분리하고 정상 명령의 stderr도 실패 처리하며, `.partial-*` + `INCOMPLETE`에서 시작해 오프라인 검증 직전만 최종 디렉터리로 승격한다.
- 오프라인 검증은 7개 DB·테이블 정의·관리자 INSERT·mysqldump footer·manifest 교차 일치와 변조를 검사한다. 민감 평문 백업 보관 규칙과 Flyway 단계별 전환 가드레일을 `docs/DATABASE_OPERATIONS.md`에 기록했다.
- PowerShell 구문, 정상 fixture, dump 변조 거부, Windows 공백/따옴표/끝 역슬래시/빈 인자 전달 테스트를 통과했다. Docker 복구 후 기존 실제 DB `20260929T123815Z`와 새 관리자 전용 기준 DB `20260929T125754Z`의 실제 dump·오프라인 검증까지 완료했다. 격리 복원은 Phase B다. 계획: `.claude/plans/2026-09-24-db-migration-foundation.md`.
- [PR #61](https://github.com/hhm0215/build-flow/pull/61) CI 6개 성공과 로컬·원격·PR head `f8b087f` 일치를 확인한 뒤 merge commit `29319f5`로 병합했다.

### ✅ 실사용 UI 라이프사이클 — 세금계산서 금액 검증 및 수정·삭제 (2026-09-24)
- 생성·수정 DTO와 Entity가 공급가액·세액의 비음수, 정수 13자리/소수 2자리, 합계 `DECIMAL(15,2)` 범위를 반올림 없이 검증한다. 실패 시 생성 DB/outbox 및 수정 전 필드가 보존되며 HTTP 400/Jackson 계약도 고정했다.
- 입금 확인은 매출 세금계산서에만 허용한다. 매입 건 직접 호출은 409로 거부하고 DB/outbox를 유지하며, 기존 입금확정 건의 수정·삭제 409와 비관적 잠금 정책도 보존했다.
- 미입금 건 수정·삭제 모달, nullable 안전 표시·정렬, siteId 없는 수정 요청, 오류 후 입력 보존·재시도, 중복 요청·진행 중 닫기 방지와 tax prefix 캐시 무효화를 구현했다.
- MSW의 정적 outstanding 라우트 우선순위, 공급가·세액·미수금 exact cents 계산, 404/409 상태 불변을 실서버 계약과 맞췄다. 직전 매입 MSW의 큰 2자리 단가 오거절과 소수 총액 부동소수 오차도 함께 회귀 보정했다.
- 전체 Gradle 41 tasks, tax-service 26 tests, 프론트 Vitest 32파일 108개·lint·build와 독립 역할 재검토를 통과했다. Docker 컨테이너 스모크는 기존 Desktop stale socket 장애로 후속이다. 계획: `.claude/plans/2026-09-24-tax-edit-delete-ui.md`.
- [PR #60](https://github.com/hhm0215/build-flow/pull/60) CI 6개 성공과 로컬·원격·PR head `8181432` 일치를 확인한 뒤 merge commit `bed27c9`로 병합했다.

### ✅ 실사용 UI 라이프사이클 — 매입 금액 검증 및 수정·삭제 (2026-09-24)
- 생성·수정 DTO와 Entity에서 수량 1 이상 정수, 단가 0 이상·정수 10자리/소수 2자리, 계산 총액 DECIMAL(15,2) 범위를 강제한다. 소수 수량 JSON은 운영 ObjectMapper도 400으로 거부하며 invalid create/update의 DB·revision·outbox 불변을 검증했다.
- siteId 없는 전용 수정 요청 타입, 기존값 사전 채움 수정 모달, 삭제 확인 모달, 서버 오류 표시·입력 보존·재시도, 중복 제출 및 진행 중 닫기 방지를 구현했다. 등록 모달도 같은 중복 방지·오류 처리로 보강했다.
- nullable 매입 응답으로 인한 SiteList/SiteDetail 날짜 정렬 크래시를 막고, MSW PUT/DELETE 성공·404·금액 400 계약과 행별 대상 선택을 테스트했다. 변경 후 매입 목록, 해당 현장 손익, 대시보드 캐시를 올바르게 stale 처리한다.
- 독립 세 역할 리뷰가 큰 정상 2자리 단가의 부동소수 오거절을 발견해 BigInt cents 계산으로 수정했다. 전체 Gradle test, purchase-service 재실행, 프론트 Vitest 29파일 89개·lint·build를 통과했다. Docker 컨테이너 스모크는 기존 Desktop stale socket 장애로 후속이다. 계획: `.claude/plans/2026-09-24-purchase-edit-delete-ui.md`.
- [PR #59](https://github.com/hhm0215/build-flow/pull/59) CI 6개 성공과 로컬·원격·PR head `9285fe2` 일치를 확인한 뒤 merge commit `91d7868`로 병합했다.

### ✅ 회계 확정 상태 변경 보호 (2026-09-24)
- CONFIRMED 견적 삭제와 입금 확인된 세금계산서 수정·삭제를 전용 409로 거부한다. 견적 삭제와 세금계산서 update/delete/confirm은 비관적 행 잠금 뒤 상태를 판정하며, 거부 시 DB와 outbox가 변하지 않는다.
- DRAFT 견적 삭제와 미입금 세금계산서 수정·삭제 회귀, MockMvc 실제 HTTP 409 JSON 계약, H2의 실제 서비스 경합 및 차단 세션을 검증했다. 견적 MSW도 확정 삭제 409와 원본 유지를 모사한다.
- 전체 Gradle test, 프론트 Vitest 73개·lint·build, 독립 역할 재검토를 통과했다. 리뷰가 발견한 handler 직접 호출과 sleep 기반 경합 테스트를 실제 ControllerAdvice 및 `BLOCKER_ID` 확인으로 보강했다.
- Docker Desktop 4.91이 stale `sailor-ingest.sock` 접근 거부로 기동하지 않아 컨테이너 스모크는 후속으로 남겼다. 임시 소켓 외 이미지·볼륨·DB는 건드리지 않았고 공장 초기화도 하지 않았다. 계획: `.claude/plans/2026-09-23-accounting-finalization-guards.md`.
- [PR #58](https://github.com/hhm0215/build-flow/pull/58) CI 6개 성공과 로컬·원격·PR head `db5250b` 일치를 확인한 뒤 merge commit `b068f19`로 병합했다.

### ✅ Kafka 손익 집계 신뢰성 Phase 3 — 매입 revision/projection (2026-09-23)
- purchase-service에 생성 1부터 수정·삭제마다 증가하는 명시적 `eventRevision`을 추가하고 등록·수정·삭제 outbox payload에 revision과 전체 현재 상태를 저장했다.
- site-service에 purchase별 최신 상태·삭제 tombstone을 보존하는 `purchase_profit_projections`를 추가했다. 낮은 revision은 stale ledger 처리, 같은 revision의 동일 상태는 no-op, 상충 상태는 DLT, 높은 revision은 gap을 허용해 이전 기여분과의 delta만 손익에 반영한다. ledger·projection·손익은 현장 행 잠금 아래 한 트랜잭션으로 commit/rollback한다.
- 6개 생명주기 순열, update/delete 선도착, stale, 동일 revision 충돌, 유형별 잘못된 revision, 다중/동시 purchase, outbox revision/rollback을 검증했다. projection을 우회하던 공개 매입 증감 API는 독립 리뷰에서 발견해 제거했다. 캐시를 끈 purchase/site 테스트와 전체 Gradle test가 성공했다.
- Docker baseline에서 source purchase 0건·consumer lag 0·DLT 0을 확인했다. 과거 스모크로 남은 SENT outbox 1건은 이력으로 보존하고, 원본 0건과 불일치한 총매입 400,000원 한 행을 조건부 reconciliation해 0원으로 복구했다. 새 이미지의 두 서비스 health UP, `event_revision` NOT NULL·projection 테이블 생성을 확인했다.
- 격리 purchaseId로 `delete r3 → update r2 → register r1`을 실제 Kafka에 발행해 revision 3 삭제 tombstone, 처리 ledger 3건, 현장 총매입 0원으로 수렴함을 확인했다. tombstone/ledger는 재생 안전성을 위해 보존하며 손익 기여는 0이다. 혼합 버전 방지 배포 순서·기존 데이터 backfill/seed 조건·삭제 DLT 대조 절차를 운영 문서에 기록했다.
- 계획: `.claude/plans/2026-09-23-kafka-purchase-revision-projection.md`.
- [PR #57](https://github.com/hhm0215/build-flow/pull/57) 로컬·원격·PR head `aa41814` 일치와 CI 6개 성공을 확인한 뒤 merge commit `36508bf`로 병합했다.

### ✅ 제품 비전 v2 — 범용 정보관리 플랫폼 방향 정립 (2026-09-23)
- 건설 현장 관리를 첫 번째 수직 도메인으로 유지하면서 원본·출처·버전·관계·확정 판단을 보존하는 범용 정보관리 플랫폼으로 단계적으로 확장하는 비전을 `docs/PRODUCT_VISION.md`와 ADR-017에 확정했다.
- 범용 정보 코어 후보·도메인 팩·근거 기반 Assistant의 경계를 정의하고, 원본 불변성·확장 스키마·감사·데이터 이동성·사람의 최종 통제를 불변 원칙으로 기록했다. README·기획·아키텍처·AGENTS·CLAUDE를 같은 방향으로 동기화했다.
- 현재 P0 순서는 변경하지 않고, P1에 2026년 `.xlsx` 견적만 대상으로 하는 문서 코어 Stage 1을 등록했다. 독립 역할 검토에서 실제 업체명/절대경로 노출, 바이트·출처 모델 혼합, 보존/삭제·사례 격리·보안/복구 기준 누락을 찾아 같은 작업에서 보완했다. 객체 저장소·벡터 DB·멀티테넌시·신규 서비스 경계는 실데이터 검증 전까지 보류했다. 계획: `.claude/plans/2026-09-23-product-vision-information-platform.md`.
- [PR #56](https://github.com/hhm0215/build-flow/pull/56)에서 공개 develop의 초기 경로 포함 커밋을 정제된 단일 SHA로 교체하고, CI 6개 성공·PR/원격 SHA 일치 후 merge commit `92a828c`로 병합했다.

### ✅ Kafka 손익 집계 신뢰성 Phase 2 — 트랜잭셔널 outbox (2026-09-21)
- 견적·매입·세금계산서·보증보험 이벤트를 업무 DB 변경과 같은 트랜잭션에 저장하고, lease/claim token·원본 JSON 재전송·broker ACK 후 `SENT`를 구현했다. 매입 수정·삭제/세금 입금 확인 잠금과 보증보험 장기 장애 중복 방지·ACK 기준 쿨다운을 적용했다.
- 전체 Gradle test, 변경 4개 서비스 재검증, 독립 리뷰 보완 및 Docker 새 이미지·health/API 200, 4개 MySQL outbox 테이블, 격리 토픽 실제 ACK/SENT 4건을 확인했다. 임시 테스트 행·토픽은 모두 제거했고 네 outbox 행 수는 0이다. MySQL 다중 dispatcher 실경합/장애 재시도는 H2·Mockito로만 검증했다.
- [PR #55](https://github.com/hhm0215/build-flow/pull/55) 최신 CI 6개 성공·4개 커밋 SHA 일치 후 merge commit `504decf` 병합. 계획: `.claude/plans/2026-09-20-kafka-reliability-outbox.md`.

### ✅ Kafka 손익 집계 신뢰성 Phase 1 — 소비자 보호 (2026-09-20)
- site-service의 고유 eventId 처리 기록과 손익 갱신을 현장 행 잠금 아래 한 트랜잭션으로 묶고, notification-service의 알림·처리 기록도 원자적으로 저장했다. 두 소비자의 예외 삼키기를 제거하고 제한 재시도·원문 DLT를 설정했다. 실제 세금 이벤트 소비자/미수금 계산 계약을 문서에 맞췄다.
- 전체 Gradle test, 서비스별 H2 중복·동시성·롤백 테스트 통과. 독립 리뷰 CRITICAL/HIGH 0건. Docker 두 서비스 health·목록 GET 200 및 처리 기록 테이블 생성 확인. MySQL 실제 동시 잠금과 Kafka offset 보존은 아직 통합 검증하지 않았다. 계획: `.claude/plans/2026-09-20-kafka-reliability-phase1.md`.
- [PR #54](https://github.com/hhm0215/build-flow/pull/54) GitHub CI 6개 성공·2개 커밋 SHA 일치 후 merge commit `637ec5c` 병합.

### ✅ 실사용 UI 라이프사이클 — 세금계산서 입금 확인 계약·오류 처리 (2026-09-20)
- 프론트 PATCH에 실제 입금일 JSON 본문을 추가하고 대상·금액·입금일 확인 모달, 중복 제출 방지, 오류 표시·재시도를 연결했다. MSW는 빈/잘못된 본문 400, 없는 ID 404, 중복 확인 409를 처리한다. 백엔드의 읽을 수 없는 요청 본문도 400 래퍼로 응답한다.
- 전체 Gradle test 및 프론트 lint·Vitest 72개·build 통과. 정적 점검에서 서버 DTO·오류·캐시 무효화·UI 경합을 확인했다. Docker 엔진 복구 후 tax-service·프론트를 재빌드·재기동하고 health/화면 200, 본문 없는 PATCH 400, 없는 ID의 유효한 PATCH 404를 실서버에서 확인했다. 영속 데이터 변경 스모크는 수행하지 않았다. 계획: `.claude/plans/2026-09-20-tax-payment-confirm-contract.md`.
- [PR #53](https://github.com/hhm0215/build-flow/pull/53) GitHub CI 6개 성공·2개 커밋 SHA 일치 후 merge commit `e293e3b` 병합.

### ✅ 실사용 UI 라이프사이클 — 보증보험 OCR 실패 수동 보정·수정 (2026-09-20)
- OCR 실패·부분 추출 항목의 빈 필드를 보정하고 기존 보험을 수정하는 목록 동선을 추가했다. PENDING 수정은 UI/서버에서 차단하고 수동 수정 후 `MANUAL` 상태로 전환한다. 날짜 역전·음수 보증금액은 서버에서도 거절한다.
- 응답의 nullable 필드와 만료 필터·현장 상세 표시, MSW PUT 계약을 정렬했다. 독립 리뷰 MEDIUM 3건 수정·재검토 완료. 전체 Gradle 테스트, 프론트 Vitest 65개·lint·build 통과. Docker 재빌드 후 프론트·notification health/목록 GET 200, Gateway 미인증 보증보험 API 401 확인. 실제 보험 파일 업로드/보정 PUT 스모크는 수행하지 않았다.
- [PR #52](https://github.com/hhm0215/build-flow/pull/52) CI 6개 성공·2개 커밋 SHA 일치 후 merge commit `af4ad46` 병합. 계획: `.claude/plans/2026-09-20-warranty-manual-correction.md`.

### ✅ 실사용 UI 라이프사이클 — 작성 중 견적 수정·삭제 (2026-09-20)
- DRAFT 견적의 항목·제목·날짜·메모 수정과 삭제 확인 동선을 추가했다. 서버 금액 저장 정밀도·항목/총액 범위를 검증하고 수정·확정·삭제를 비관적 행 잠금으로 직렬화했다.
- 독립 리뷰 P1 2건을 수정·재검토했으며 전체 Gradle 테스트, 프론트 Vitest 55개·lint·build를 통과했다. Docker 프론트 200·견적 health 200·미인증 API 401 확인; 실사용 견적 변경/삭제 스모크는 생략했다.
- [PR #51](https://github.com/hhm0215/build-flow/pull/51) CI 6개 성공·2개 커밋 SHA 일치 후 merge commit `3429a88` 병합. 계획: `.claude/plans/2026-09-19-estimate-draft-edit-delete.md`.

### ✅ 실사용 UI 라이프사이클 — 현장 수정 (2026-09-19)
- 현장 상세의 수정 버튼·사전 채움 모달에서 현장명·거래처·주소·기간·메모를 전체 교체 PUT으로 수정한다. 선택 필드 비움은 null로 전송한다.
- 저장 중 중복 요청·닫기를 막고 실패 시 입력을 유지한다. MSW PUT→GET 및 404 계약 테스트, 프론트 Vitest 42개·lint·build, 독립 코드 리뷰를 통과했다. Docker 프론트 200·미인증 API 401 확인; 실사용 DB 변경 PUT 스모크는 생략했다.
- [PR #50](https://github.com/hhm0215/build-flow/pull/50) CI 6개 성공·2개 커밋 SHA 일치 후 merge commit `3e86833` 병합. 계획: `.claude/plans/2026-09-19-site-edit-ui.md`.

### ✅ GitHub 운영 위임 규칙·PR #49 병합 (2026-09-19)
- BuildFlow의 커밋·push·PR 생성·CI·SHA 검증 후 병합을 건별 확인 없이 수행하는 범위와 멈춤 조건을 CLAUDE/자동화 가이드/ADR-016에 일치시켰다.
- [PR #49](https://github.com/hhm0215/build-flow/pull/49): 독립 코드 리뷰 지적 수정, Gradle·프론트 검증, CI 6개 성공, PR/원격 7개 커밋 SHA 일치 후 merge commit `754dcf0`으로 병합.
- 계획: `.claude/plans/2026-09-19-github-delegation.md`.

### ✅ 실사용 UI 라이프사이클 — 거래처 등록 (2026-09-19)
- 현장 생성 모달 안에서 거래처를 등록하면 새 ID가 현장 폼에 자동 선택되도록 연결했다. 업체명 필수·선택 필드 길이/이메일 검사·서버 오류 표시·재시도를 구현했다.
- 백엔드 DTO에 맞춘 POST mutation과 nullable 거래처 응답 타입, MSW POST/GET 및 신규 거래처의 현장 연결을 추가했다. 계약 검토는 역할 분리 에이전트가 읽기 전용으로 수행했다.
- 독립 코드 리뷰에서 중복 제출·거래처 모달 경합, Kafka 손익 지연, 조회 오류 시 금액 오표시를 발견해 수정했다. 현장 상세 금액은 최신 확정 견적·매입 목록으로 계산하며 조회 실패·로딩 상태를 구분한다.
- 프론트 lint·Vitest 37개·production build 통과. 프론트 Docker 재빌드 후 HTTP 200, 미인증 거래처 API 401 확인. 영속 데이터가 남는 거래처 POST 실서비스 스모크와 브라우저 수동 E2E는 수행하지 않았다.
- 계획: `.claude/plans/2026-09-19-client-create-ui.md`.

### ✅ 실사용 UI 라이프사이클 — 견적 확정 동선 (2026-09-19)
- DRAFT 견적 행에만 확정 버튼을 표시하고, 확인 모달에서 기존 API mutation을 연결했다. 중복 요청·진행 중 닫기 방지, 서버 오류 표시·재시도를 추가했다.
- 현장 허브·상세의 프론트 손익 합계에서 초안 견적 금액을 제외하도록 통일했다.
- 프론트 lint·Vitest 27개·production build 통과. 프론트 Docker 컨테이너 재빌드·재기동 후 HTTP 200 확인. 실제 Kafka 손익 이벤트 스모크는 집계 신뢰성 P0 이후로 보류했다.
- 계획: `.claude/plans/2026-09-19-estimate-confirm-ui.md`.

### ✅ 단일 관리자 loginId 인증 전환 (2026-09-19)
- 실사용 데이터가 없음을 확인하고 관리자 1개를 가족이 공유하기로 결정. 공개 signup·VIEWER 역할을 제거하고 `admin_accounts` 고정 PK 1, 로컬 대화형 관리자 초기화, `loginId/password` 로그인을 구현했다.
- 최초 관리자 비밀번호 최소 길이를 사용자 요청에 따라 9자로 조정하고 서버·스크립트·테스트·문서를 일치시켰다. 계정 1개와 60자 BCrypt 해시를 DB에서 확인했다.
- JWT `authVersion=2` 및 Gateway access/ADMIN 검증, Discovery 자동 라우트 비활성화, 프론트·MSW·실서버 개발 모드·로그인 401 표시를 동기화했다.
- Gradle 전체 테스트, 프론트 lint·Vitest 20개·build, PowerShell 구문 검사 통과. 공개 signup 403, 잘못된 로그인 401, 미인증 현장 요청 401, Discovery 자동 경로 404를 확인했다.
- 로컬 대화형 `verify-admin.ps1`의 관리자 로그인·현장 생성/조회/삭제 API 스모크 통과. 임시 현장 제거 후 DB는 관리자 1명, 현장 0개, 현장 손익 0건. 기존 BuildFlow 데이터는 없었고 다른 프로젝트 컨테이너·볼륨은 건드리지 않았다.
- 계획: `.claude/plans/2026-09-19-single-admin-login-id.md`. 기존 실데이터 스키마 마이그레이션 체계는 P1 백로그로 분리했다.

### ✅ 실사용 UI 라이프사이클 — 현장 생성 첫 단계 (2026-09-19)
- 현장 추가 버튼을 생성 폼·기존 mutation에 연결하고 선택적 거래처 조회, 날짜 변환, 오류 표시, 성공 시 상세 이동을 구현했다. MSW 거래처 목록과 생성 응답도 맞췄다.
- UI 테스트 4개를 포함한 프론트 20개 테스트, lint, build 통과. 관리자 인증 후 현장 생성/조회/삭제 API 스모크도 통과했다. 브라우저 수동 E2E는 수행하지 않았고 나머지 UI 동선은 P0 백로그에 남는다.
- 계획: `.claude/plans/2026-09-19-ui-lifecycle-first-slice.md`.

### ✅ Windows fresh clone 개발 준비 (2026-09-18)
- PowerShell helper 3종: 안전한 `.env` 생성·전체 스택 관리, 서비스별 환경 로드, 대화형 관리자 생성
- README/Windows 가이드를 Bun 1.3.11, 루트 Gradle wrapper, 포트 3000, native/Compose Ollama 실제 경로에 맞게 전면 정렬
- 모든 개발 host publish를 `127.0.0.1`로 제한하고 컨테이너 Ollama를 선택 profile로 분리
- 루트 `.dockerignore`로 백엔드 context 약 366MB → 서비스별 약 12~89kB, `.env`·VCS·로컬 산출물 전송 차단
- Gradle wrapper 공식 SHA-256 고정, CI runner Ubuntu 24.04 고정, Windows PowerShell 5.1·`gradlew.bat` job 추가
- 검증: Gradle 전체 테스트, frontend lint·Vitest 7개·build, Compose/15개 컨테이너/HTTP 200, PR CI run `35340803038` 3개 job 통과
- 자동 리뷰 2회 CRITICAL/HIGH 0, 계획: `.claude/plans/2026-09-18-windows-fresh-clone-readiness.md`

### ✅ 실데이터 파일럿 선행 런타임 안정화 (2026-09-15)
- notification-service Docker DB 환경/MySQL health 의존성 추가, 보증보험 업로드를 `buildflow_warranty_uploads` named volume으로 영속화
- site-service Docker Ollama 주소를 호스트 네이티브 인스턴스로 정렬, AI summary 실제 호출 HTTP 200
- 트레이싱 앱 8개의 Zipkin endpoint를 서비스 DNS로 정렬 — Zipkin에서 8개 서비스 span 수집 확인
- 결합 Compose가 `buildflow-net`을 직접 생성·관리하도록 수정, clean host 단일 기동 경로 정렬
- Bun packageManager/Docker/CI를 1.3.11로 고정, GitHub Actions를 `checkout@v7`·`setup-java@v6`으로 갱신, `.dockerignore`로 frontend build context 약 266.69MB → 5.63kB 축소
- 서비스별 IDE `bin/` 산출물 ignore, 풀 Docker/계약 동기화/신규 서비스 편입 규칙을 CLAUDE.md에 고정
- 검증: Gradle 전체 테스트 29개, frontend lint·Vitest 7개·build, Compose config, 16개 컨테이너 기동, 핵심 health/API HTTP 200 모두 통과
- 계획: `.claude/plans/2026-09-15-pilot-runtime-stabilization.md`

### ✅ 인프라 서비스
- **eureka-server** — 서비스 디스커버리 완료
- **config-server** — 중앙 설정 서버 완료
- **gateway-server** — API Gateway, JWT 검증, 라우팅 완료
- **docker-compose.yml** — MySQL, Redis, Kafka(KRaft), Zipkin 로컬 환경 완료

### ✅ auth-service (Port 8081)
- 단일 관리자 로그인 / 토큰 갱신 / 로그아웃 (공개 회원가입 없음)
- JWT (access 30분, refresh 7일)
- Redis 블랙리스트 (로그아웃 시 access token 무효화)
- 역할: ADMIN 1개 계정 공유

### ✅ estimate-service (Port 8082) — CRUD + AI 파싱 완료
- 견적서 CRUD (생성/조회/수정/삭제)
- 견적 항목 자동 합계 계산
- 견적 확정(CONFIRM) — 확정 후 수정/삭제 불가
- `siteId`로 현장별 견적서 필터링
- Kafka 이벤트 구조 정의 (`KafkaEvent<T>`)
- 공내역서 AI 파싱: Ollama 기반 (qwen2.5:7b) — 엑셀 업로드 → POI 파싱 → Ollama JSON 구조화
  - 기술 전환: Claude API(claude-sonnet-4-6) → Ollama(로컬 LLM, 무료)
  - `58f6fa8` Ollama API 설정 (application.yml, OllamaConfig)
  - `3ac97b4` docker-compose에 Ollama 서비스 추가
  - `9128d2e` Ollama 기반 공내역서 AI 파싱 구현

### ✅ frontend — 관리자 로그인 + 현장관리 허브
- 관리자 아이디 로그인 화면 개편
- 현장관리 허브 화면 대규모 개편 (SiteListPage)
- `4b83125` feat(frontend): 관리자 아이디 로그인 및 현장관리 허브 화면 개편

### ✅ site-service (Port 8083) — CRUD 완료
- 거래처(Client) CRUD: `POST/GET/PUT /api/v1/clients`
- 현장(Site) CRUD: `POST/GET/PUT/DELETE /api/v1/sites` + 상태변경 `PATCH /api/v1/sites/{id}/status`
- SiteStatus: IN_PROGRESS, SETTLING, WARRANTY, COMPLETED
- JPA Auditing 활성화, ddl-auto: update
- `ed70866` chore(site-service): JPA Auditing 활성화, ddl-auto update 변경, global 패키지 구성
- `a6f986f` feat(site-service): 거래처(Client) CRUD 구현
- `efc75c7` feat(site-service): 현장(Site) CRUD 구현

### ✅ site-service — 손익 계산 + Kafka 연동
- SiteProfit 엔티티: 현장별 총견적액, 총매입액, 마진, 마진율 저장
- Kafka Consumer: `estimate.parsed`, `purchase.registered` 토픽 수신 → 손익 자동 갱신
- Profit API: `GET /api/v1/sites/{id}/profit` — 마진/마진율 조회
- ProfitService: 견적/매입 금액 누적 시 마진 자동 재계산

---

### ✅ purchase-service (Port 8084) — CRUD + Kafka 발행 완료
- 매입(Purchase) CRUD: `POST/GET/PUT/DELETE /api/v1/purchases` (siteId 필터 지원)
- Purchase 엔티티: siteId, 품목명, 수량, 단가, 총액(자동계산), 공급업체, 매입일
- Kafka 발행: `purchase.registered` 토픽 → site-service 소비하여 손익 재계산
- global 패키지: ApiResponse, ErrorCode, BusinessException, GlobalExceptionHandler, KafkaEvent
- `b6248f5` chore(purchase-service): JPA Auditing 활성화, ddl-auto update 변경, global 패키지 구성
- `076cfd9` feat(purchase-service): 매입(Purchase) CRUD + Kafka 발행 구현

---

### ✅ tax-service (Port 8085) — CRUD + 미수금 추적 완료
- 세금계산서(TaxInvoice) CRUD: `POST/GET/PUT/DELETE /api/v1/taxes` (siteId, type 필터)
- TaxInvoice 엔티티: siteId, 구분(SALES/PURCHASE), 공급가액, 세액, 총액(자동계산), 거래처, 입금여부
- 입금 확인: `PATCH /api/v1/taxes/{id}/confirm-payment`
- 미수금 조회: `GET /api/v1/taxes/outstanding?siteId={id}`
- 미수금 = 매출 세금계산서 총액 - 입금 확인 금액
- `98d49c3` chore(tax-service): JPA Auditing 활성화, ddl-auto update 변경, global 패키지 구성
- `2ca9b70` feat(tax-service): 세금계산서 CRUD + 미수금 추적 + 입금 확인 구현

---

### ✅ notification-service (Port 8086) — 인앱 알림 + 하자보증보험 완료
- build.gradle에 JPA, MySQL, Validation 의존성 추가
- Kafka Consumer: estimate.parsed, purchase.registered 수신 → 인앱 알림 자동 생성
- 알림 API: `GET /api/v1/notifications`, 미읽음 건수, 개별/전체 읽음 처리
- 하자보증보험 CRUD: `POST/GET/PUT/DELETE /api/v1/warranties` (siteId 필터)
- 만료 임박 조회: `GET /api/v1/warranties/expiring?days=30`
- 응답에 만료까지 남은 일수, 만료 여부 포함
- `c16e73e` chore: JPA/MySQL 추가, global 패키지 구성
- `c3109f2` feat: 인앱 알림 (Kafka Consumer + 조회/읽음)
- `1066213` feat: 하자보증보험 CRUD + 만료 임박 조회
- ⚠️ 미구현: PDF 업로드 + Tesseract OCR, 만료 경고 스케줄러 (다음 단계)

---

### ✅ site-service — AI 요약 대시보드 완료
- Ollama 기반 현장 종합 분석 (qwen2.5:7b)
- DashboardController: `/api/v1/dashboard/stats`, `/api/v1/dashboard/summary`
- `f8ba39b` feat(site-service): AI 요약 대시보드 구현

---

### ✅ frontend — API 연동 기반 구축 완료
- 도메인 타입 전면 재설계 (백엔드 DTO 1:1 대응)
- 전 서비스 CRUD API 모듈 + TanStack Query 훅 구현
- MSW 핸들러 전체 엔드포인트 커버 (GET/POST/PUT/DELETE)
- 신규 API: dashboard, notifications, warranties
- 페이지 컴포넌트 타입 적용 (Estimate/Purchase/Site/Tax ListPage)
- `0c11e87` refactor(frontend): 도메인 타입 재정의 + API/MSW CRUD 패턴 통일
- `1e32ebb` refactor(frontend): 페이지 컴포넌트 신규 타입 적용

---

### ✅ frontend — 알림 + 보증보험 페이지 + 대시보드 API 연동
- NotificationListPage: 알림 목록 (읽음/미읽음, 전체 읽음)
- WarrantyListPage: 하자보증보험 CRUD + 만료 임박 배너
- DashboardPage: 하드코딩 제거 → useDashboardStats/useDashboardSummary 연동
- 사이드바 메뉴에 알림/보증보험 추가, 라우트 등록

---

### ✅ frontend — CRUD 등록 모달 4종 완료
- 견적서 작성 / 매입 등록 / 세금계산서 등록 / 보증보험 등록 모달
- `3b2a919`, `21e708f`, `f2d9ed7`, `219d1e0`

---

### ✅ frontend — useListFilters 훅 추상화 (2026-07-04, P1 완료)
- 5페이지(estimate/purchase/tax/warranty/site) 필터 scaffolding을 `useListFilters` 훅으로 통합
- filterFn(검색+술어)만 페이지에 남기고 resetFilters·activeCount는 schema/groups에서 자동 도출
- 5.5 리뷰 자가 발견: `filtered` memo가 검색어 타이핑마다 재계산되던 디바운스 회귀 → `filterSig`+ref로 in-cycle fix
- 계획: `.claude/plans/2026-07-04-use-list-filters.md`

---

### ✅ chat-service Phase 2 — SSE 스트리밍 + 채팅 패널 UI (2026-07-15)
- 백엔드: `POST /api/v1/chat/stream`(SseEmitter 180s, 전용 executor) — 이벤트 session/status/token/done/error. 동기 API 유지
- 프론트: fetch+ReadableStream SSE 클라이언트(UTF-8 청크 경계 파서, 단위 테스트) + ChatPanel 플로팅 패널(전송/중단/새 대화) MainLayout 장착
- 설계 변경: 툴 선택 라운드 답변을 조각 relay(chunkAnswer) — 5.5 리뷰 HIGH(이중 LLM 생성) fix. 실시간 stream:true+tools는 Phase 3
- 5.5 리뷰(high) findings 10건 전부 in-cycle fix: emitter 생명주기/중단 INFO 분류/CHAT_BUSY 거부 처리/ErrorCode 보존/lombok.config/reader.cancel/uuid 폴백 등
- 런타임 검증(Gateway 경유): 툴콜 스트리밍·2라운드 체인·세션 연속성·중단(부분 미저장, ERROR 0)·DB 영속화 전부 ✅
- 환경 함정 4건 런북화(plan 문서): 11434 이중 리스너(네이티브/도커 Ollama), nginx 8080/8081 점유, DB_PASSWORD 필수, config-server 우회
- 계획: `.claude/plans/2026-07-04-chat-service.md` "Phase 2 결과"

### ✅ chat-service Phase 1 런타임 검증 + 잠복 버그 3건 fix (2026-07-04)
- Claude가 직접 실행: docker(mysql/redis/ollama+qwen2.5:7b) + bootRun(eureka→site→chat) → 툴콜 E2E 검증
- "등록된 현장 목록" → listSites 툴콜 → Feign 실데이터 답변 ✅ / 같은 세션 "마진 얼마?" → getSiteProfit 체인 ✅ / chat_messages 영속화 ✅
- 잠복 버그 fix: ① bitnami/kafka Docker Hub 소멸 → bitnamilegacy 교체 ② 전 서비스 JDBC `allowPublicKeyRetrieval=true` 추가(새 볼륨에서 기동 불가 버그) ③ init SQL에 buildflow_chat 추가
- 7b 토큰 잡음("마argin율") 실측 → Phase 3 견고화 근거. 검증 후 mariadb 복구·컨테이너 정리 완료

### ✅ 테스트 파운데이션 + CI (2026-07-04, ADR-015)
- **CI**: GitHub Actions `.github/workflows/ci.yml` — PR(→main)/push(develop)마다 백엔드 `./gradlew test`(JDK 17) + 프론트 `bun lint/test/build`
- **프론트**: Vitest + Testing Library + jsdom 셋업 + 파일럿 `useListFilters.test.tsx`(4개 통과)
- **백엔드**: 파일럿 `ToolExecutorTest`/`ToolCatalogTest`(chat-service, Mockito) + 기존 notification 3종 = `./gradlew test` green
- **워크플로우**: 5.6단계(순수 로직 변경 시 단위 테스트 동반) 신설, ADR-015. 수동 회귀 검증 토일 → CI 자동화
- 로컬 검증: `bun run test`(4 pass) + `./gradlew :chat-service:test :notification-service:test`(green)

### ✅ chat-service Phase 1 — 툴콜 에이전트 챗봇 (2026-07-04)
- 신규 마이크로서비스(포트 8087, buildflow_chat). 아키텍처 A(툴콜 에이전트) — 벡터스토어 없이 OpenFeign 실데이터 기반
- `OllamaToolService` 에이전트 루프(Ollama `/api/chat` 툴콜 왕복) + 도구 4종(listSites/getSiteProfit/getOutstandingTax/getDashboardSummary)
- Feign: SiteClient/TaxClient, 이력: MySQL(ChatSession/ChatMessage) + Redis 세션 TTL, API: `POST /api/v1/chat`
- Gateway `/api/v1/chat/**` 라우트 추가, settings.gradle 등록. compileJava 10모듈 통과(JDK 17), 5.5 리뷰 CRITICAL/HIGH 0
- 계획: `.claude/plans/2026-07-04-chat-service.md` (Phase 2 SSE+UI, Phase 3 확장 남음)

### ✅ 인프라 — Gradle wrapper 생성 + 백엔드 컴파일 검증 (2026-07-04)
- 루트에 `gradlew` + gradle 8.10 wrapper 생성·커밋 (멀티프로젝트 9개 서비스 공통)
- `brew install gradle`(9.6.1)로 호스트 gradle 확보 → `gradle wrapper --gradle-version 8.10`
- `brew install openjdk@17` + `JAVA_HOME` 지정 → `./gradlew compileJava` **9개 서비스 전부 BUILD SUCCESSFUL** (프로젝트 최초 백엔드 컴파일 검증)
- 실행법 CLAUDE.md "빌드 & 실행 > Gradle"에 JDK 17/JAVA_HOME 문서화

---

### ✅ frontend — 현장 상세 페이지 (SiteDetailPage)
- `/sites/:id` 라우트 신설, SiteListPage에서 진입 버튼 추가
- 헤더: 현장명/상태/거래처/공사기간/주소/메모 + 상태 변경 Select (Antd message 토스트)
- 손익 카드 4종: 매출(견적합계) / 매입 / 마진(마진율) / 미수금 — `useSiteProfit` 우선, 미응답 시 클라이언트 계산 폴백
- 탭 4개: 견적서 / 매입 / 세금계산서 / 보증보험 (각 도메인 API에 `siteId` 필터 전달)
- 잘못된 siteId 가드 + 목록 복귀 동작
- AI 요약 영역은 비용 검토 후 별도 진행 (이번 범위 제외)

---

### ✅ frontend — 헤더 알림 벨 + 브레드크럼 보정 (2026-06-07)
- NotificationBell: 헤더 우측 미읽음 뱃지(99+ 캡) + 최근 5건 드롭다운 + ESC/외부 클릭 닫기
- 알림 클릭 시 markAsRead + siteId 있으면 현장 상세로 이동
- `/sites/:id` 브레드크럼: matchPath + useSite로 siteName 동적 표시
- 비숫자 siteId NaN 가드
- `ddfddf1` feat(frontend): 헤더 알림 벨 + /sites/:id 브레드크럼 보정

---

### ✅ frontend — 공내역서 업로드 UI (2026-06-09)
- 엑셀 업로드 모달 신설: 드래그앤드롭 + .xlsx/.xls 검증 + 10MB 제한
- `POST /api/v1/estimates/parse` 호출 (multipart/form-data, timeout 120s)
- 파싱 결과 항목 테이블 미리보기 + 합계 표시
- "견적서로 만들기" → 견적서 작성 모달 열기 + items/title 자동 채움 (afterOpenChange + form.setFieldsValue)
- MSW 핸들러: 1.5초 지연 + 10개 더미 항목 (실서버 Ollama는 30초~1분 소요, 모달에 안내)
- 신규 타입: `ParseResult`, `ParsedItemResult`
- 신규 훅: `useParseEstimateFile`

---

### ✅ P2 LOW 2건 정리 (2026-06-26)
- `WarrantyOcrParser.findPeriod` — `if (!label.find())` 단발 → `while (label.find())` 다중 라벨 순회. 두 날짜 모두 추출되는 첫 라벨 채택, 못 찾으면 첫 라벨의 start만 fallback으로 반환 (테스트 1건 추가)
- `DefectWarranty.isExpiringSoon` — 호출자 0건 dead 메서드 제거. 만료 판정은 Repository의 BETWEEN 쿼리(`findExpiringSoon`/`findExpiringNotYetAlerted`)와 `isExpired()` 메서드가 담당
- 5.5단계 인라인 검토 (변경 양 작아 finder agent 생략) — HIGH/CRITICAL 없음

---

### ✅ P1 MEDIUM 6건 + 5.5단계 첫 적용 (2026-06-22)
- useMemo+setState 안티패턴 → React Query refetchInterval 함수 전달 (setState 제거)
- `useWarranties` refetchInterval 옵션 타입 확장 (`number | false | function`)
- `DefectWarranty.isExpiringSoon/isExpired` — boundary today 포함 (당일 임박, 다음날 만료)
- `SiteSelect` — AntD `SelectProps<number>` spread, Form.Item 주입 props forward
- `WarrantyOcrParser` — PERIOD_LABEL + DATE 분리 패턴 (단방 날짜 추출 가능, 테스트 2건 추가)
- `DefectWarranty.update` — null 인자 skip (partial update 시맨틱)
- `DefectWarrantyService.delete` — TransactionSynchronization.afterCommit으로 파일 cleanup 이동 (commit 전 inverse-orphan 방지)
- **5.5단계 자동 코드 리뷰** (ADR-013 첫 적용) — finder 2개 병렬, HIGH 1건 발견 즉시 같은 PR fix, MEDIUM 2건 + LOW 2건은 BACKLOG 분리 등록

---

### ✅ warranty 핫픽스 Phase 1.5 — /code-review CRITICAL+HIGH 7건 (2026-06-22)
- **@Async tx race 해소**: `createFromOcr`에서 `@Transactional` 제거 → save 자체 tx commit 후 processAsync 호출 (PENDING 영구 고착 사고 방지)
- **servlet.multipart YAML 위치 수정**: `spring.servlet.multipart.*`로 이동 → 20MB 한도 정상 적용
- **applyOcrResult 가드**: 1개 이상 추출 시 SUCCESS, 모두 null이면 FAILED (거짓 SUCCESS 뱃지 방지)
- **Tesseract bean prototype scope + ObjectProvider**: 동시 호출 시 native lib race 회피
- **Kafka send 동기 await**: `CompletableFuture.get(5s)` + 실패 시 markExpiringAlertSent skip → 다음 cron 재시도
- **timezone 명시**: `@Scheduled(zone="Asia/Seoul")` + `LocalDate.now(KST)` → UTC 컨테이너에서도 09:00 KST 정확 발사
- **ocrStatus 마이그레이션 안전**: `@ColumnDefault("'MANUAL'")` → 기존 운영 데이터 backfill
- 계획 문서: `.claude/plans/2026-06-22-warranty-hotfix.md`
- /code-review MEDIUM 7건은 P1 신규 등록

---

### ✅ 백엔드 DefectWarranty.coverageAmount 필드 추가 (2026-06-21)
- `DefectWarranty.coverageAmount BIGINT` 필드 + 빌더/update 메서드 인자 추가
- `WarrantyCreateRequest` / `WarrantyUpdateRequest`에 `Long coverageAmount` 옵션 필드
- `WarrantyResponse`에 `coverageAmount` 포함
- `DefectWarrantyService.create/update` 호출부 갱신
- `docs/ERD.md`: warranties → defect_warranties (실제 테이블명) + insurance_company/policy_number/coverage_amount 컬럼 반영, NOT NULL 표시
- `ddl-auto: update`로 컬럼 자동 마이그레이션 (NULL 허용)
- frontend는 이미 옵셔널 처리 완료 (사이클 2 짝 완성)

---

### ✅ 위임 모드 P1 일괄 처리 + 자동화 가이드 (2026-06-21)
- **사이클 1**: InputNumber `as 0`/`as any` 캐스트 정리 — 5개 InputNumber `<number>` 제네릭 명시 + createWarranty/updateWarranty 시그니처 좁힘. lint warning 4 → 0
- **사이클 2**: `Warranty.coverageAmount` optional 처리 + WarrantyListPage/SiteDetailPage null 가드. 백엔드 추가는 BACKLOG P2로 분리
- **사이클 3**: `SiteSelect` 컴포넌트 신설 + 4개 모달(estimate/purchase/tax/warranty) siteId 입력을 useSites 기반 검색 드롭다운으로 교체
- **사이클 4**: `useFilterParams` 함수 오버로드 + `InferFilters<S>` 타입 추론. Warranty 필터 `endStart/endEnd` → `expiryFrom/expiryTo` 명명. useListFilters 추상화는 위험 분리로 별도 P1 등록
- **사이클 5**: `docs/AUTOMATION_GUIDE.md` 신설 — 자동화 3모드 / 8단계 사이클 / 진입 키워드 / 멈춤 조건 / FAQ / 트러블슈팅 / 치트시트
- 위임 모드 진입 키워드 1회로 5 사이클 자동 처리 (ADR-012 본격 활용)

---

### ✅ frontend — 보증보험 PDF 업로드 모달 + OCR 상태 뱃지 + 폴링 (2026-06-18)
- 신규: `components/OcrStatusBadge.tsx`, `pages/warranty/WarrantyUploadModal.tsx`
- 갱신: `types/domain.types.ts`(OcrStatus + ocrStatus/filePath 추가), `api/warranties.api.ts`(uploadWarranty + useUploadWarranty + refetchInterval), `WarrantyListPage`(UploadCloud 버튼 + 보험사 셀 뱃지 + PENDING 5초 폴링), MSW 핸들러(/upload 202 + 10초 자동 SUCCESS 시뮬)
- `bun run lint` 0 errors / `bun run build` 통과
- 계획 문서: `.claude/plans/2026-06-18-warranty-upload-ui.md`

---

### ✅ notification-service — PDF OCR Phase 2 (2026-06-18)
- `notification-service/Dockerfile` 전용 신설 — debian jammy + Tesseract 4 + kor·eng traineddata (다른 8 서비스는 alpine 그대로)
- `docker-compose.app.yml` notification-service build.dockerfile 경로 변경
- `build.gradle`: `pdfbox:3.0.3` + `tess4j:5.13.0` 의존성
- `OcrStatus` enum (PENDING/SUCCESS/FAILED/MANUAL) + `DefectWarranty` 필드 추가, 기존 빌더는 default MANUAL
- `WarrantyOcrParser` 정규식 — 보험사 화이트리스트 12종 + 증권번호/날짜 추출, 6 단위 테스트
- `WarrantyOcrService.@Async` 하이브리드 파이프라인: PDFBox 텍스트 1차 → 50자 미만이면 Tess4J 2차 (300 DPI)
- `WarrantyOcrConfig.@EnableAsync` + Tesseract 빈 (`kor+eng`, datapath 외부화)
- `POST /api/v1/warranties/upload` (multipart, 202 Accepted) — 즉시 PENDING + 비동기 OCR
- `application.yml`: `app.upload-dir`, `app.ocr.{min-text-length,scan-dpi,tessdata-path,languages}`, multipart 20MB 한도
- `.gitignore`: `uploads/` 추가
- ⚠️ 빌드 검증 못함 (gradlew 부재, BACKLOG P2). 실제 PDF 샘플 정규식 튜닝은 첫 사용 후 보강
- 계획 문서: `.claude/plans/2026-06-18-warranty-ocr-phase2.md`

---

### ✅ notification-service — 만료 스케줄러 Phase 1 (2026-06-18)
- `@EnableScheduling` 도입 + `WarrantyExpirationScheduler` (매일 09:00 cron)
- `KafkaProducerService` 신설 — `warranty.expiring` 토픽 (notification-service 최초 발행자)
- `WarrantyExpiringPayload`: warrantyId/siteId/insuranceCompany/endDate/daysUntilExpiry
- `DefectWarranty.lastExpiringAlertSentAt` + `markExpiringAlertSent` 추가 — cooldown 7일 단일 컬럼 중복 방지
- `findExpiringNotYetAlerted(today, threshold, cooldownThreshold)` 쿼리
- `KafkaConsumerService`에 `warranty.expiring` 핸들러 → 인앱 알림 `WARRANTY_EXPIRING` 자동 생성
- application.yml: kafka producer 설정 + `app.warranty.{alert-threshold-days=30, alert-cooldown-days=7, scheduler-cron="0 0 9 * * *"}` 외부화
- 계획 문서: `.claude/plans/2026-06-18-warranty-ocr-scheduler.md`
- Phase 2 (OCR): BACKLOG P0 재등록
- ⚠️ 빌드 검증 못함 (gradlew 부재 BACKLOG P2) — 정적 정합성 점검만 수행, 머지 후 `docker compose up`으로 통합 검증 필요

---

### ✅ frontend — /review 후속 fix 2건 (2026-06-10) — 워크플로우 시범 사이클
- `estimates.api.ts`: 공내역서 parse 요청에서 `Content-Type: multipart/form-data` 수동 헤더 제거 (axios 자동 boundary에 위임)
- `UploadParseModal.tsx`: parse 실패 시 `isAxiosError` narrow 후 백엔드 `error.response?.data?.error?.message` 우선 노출
- 워크플로우 시스템(BACKLOG/RETROSPECTIVE/plans + CLAUDE.md 사이클) 도입 후 첫 시범 사이클로 완주
- 계획 문서: `.claude/plans/2026-06-10-review-fix-2.md`

---

### ✅ 잡다한 수정 누적 (~2026-06-08)
- `estimate-service/build.gradle` line 9 타이포(`boo,t`) 수정 (2026-04-09)
- tax-service `ddl-auto: update`로 변경
- ESLint v9 flat config 도입 (`66af734`, 2026-06-08)

> 미완 항목 "Gradle wrapper 설치"는 `BACKLOG.md` P2로 이관.

---

### ✅ warranty P1 MEDIUM 2건 정리 + 5.5 HIGH 2건 즉시 fix (2026-07-01)
- `WarrantyOcrParser.findPeriod` 거리 제약 강화: 라벨↔시작일 60자, 시작일↔종료일 15자. 발급일자 등 부가 날짜 오매칭 방지
- `DefectWarranty.update` 3-state 시맨틱: `Optional<T>` DTO로 skip/clear/update 구분 (memo/policyNumber/coverageAmount)
- 5.5단계 자동 리뷰 HIGH 2건 즉시 fix — PERIOD_LABEL greedy `[\s\S]{0,200}` 캡처가 다음 라벨을 삼키는 문제(라벨만 매칭+substring 방식으로 재작성) / START_TO_END_GAP=30이 오매칭 케이스 통과시켜 15로 조임
- 신규 테스트 3건 (parser 2건 + WarrantyUpdateRequest 3-state + DefectWarranty entity update)

---

### ✅ frontend — 검색/필터링 5페이지 일괄 패턴화 (2026-06-08)
- 공통 부품: FilterBar(children 패턴) + FilterSearch / FilterSelect / FilterDateRange / FilterAmountRange
- 공통 훅: useFilterParams(URL 동기화 + 스키마 검증 + 무효값 자동 정리) + useDebouncedValue(250ms)
- 5페이지 적용:
  - PurchaseListPage: 검색(품목/공급업체) · 매입일 · 금액 범위
  - EstimateListPage: 검색(제목) · 상태 · 견적일 · 총액 범위
  - WarrantyListPage: 검색(보험사/증권번호) · 유효/만료 · 만료일 범위
  - TaxListPage: 검색(거래처) · 구분 · 입금상태 · 발행일 · 총액 범위
  - SiteListPage: 사이드바 인라인 검색 + 상태
- /review 적용 fix (P1/P2): cleanupOnceRef 제거 + setFilters functional updater + AmountRange 0 잔류 방지 + Select/DateRange 메모이제이션 + motion 스태거 캡 + 날짜 ISO 정규화 + SiteListPage 4-dataset 메모이제이션
- 별도 PR 예정: useListFilters 추상화, useFilterParams 타입 추론, Warranty 필드 명명 일관성

---

### ✅ 풀 도커 실서비스 모드 전환 (2026-07-16, 커밋 4abbc7f + 23f35b8)
- 파일럿 온보딩 선행 작업: dev 서버는 MSW 전 도메인 목업이라 실사용 불가 → 프로덕션 빌드 풀 도커 스택으로 전환
- 잠복 갭 8건 발견·수정: chat-service 도커 편입 누락 / arm64 베이스 3종 / 로그인 loginId↔email 계약 불일치(MSW가 가림) / config-server git repo 요구 / 컨테이너 내 OLLAMA_URL localhost / 도커 Ollama OOM(VM 7.8GB) / Ollama 11434 이중 점유 / nginx SSE 버퍼링
- 스모크 전 구간 통과 (로그인→사이트/견적/매입/세금→chat SSE 툴콜 실DB 조회)
- 5.5 리뷰: CRITICAL/HIGH 0건, LOW 1건(nginx location 중복 — nonblocking)

---

## 다음 작업

→ **`.claude/BACKLOG.md`** 참조 (우선순위 단일 진실원).

---

## 서비스 포트 정리

| 서비스 | 포트 |
|--------|------|
| eureka-server | 8761 |
| config-server | 8888 |
| gateway-server | 8080 |
| auth-service | 8081 |
| estimate-service | 8082 |
| site-service | 8083 |
| purchase-service | 8084 |
| tax-service | 8085 |
| notification-service | 8086 |
| chat-service | 8087 |

## Kafka 토픽 현황

| 토픽 | 발행 서비스 | 소비 서비스 | 구현 여부 |
|------|-----------|-----------|---------|
| estimate.parsed | estimate-service | site-service | ✅ 발행+소비 구현 |
| purchase.registered | purchase-service | site-service | ✅ 발행+소비 구현 |

## 다음 세션 진입점 (2026-10-01 갱신)

**Git 상태**: PR #49~#68 merge 완료. `origin/main`·로컬/원격 `develop`은 PR #68 merge commit `49f7274`로 동기화했다. 이 병합 기록은 후처리 문서 커밋으로 반영한다.

**로컬 실행 상태**: 2026-09-29에는 새 로컬 환경의 15개 컨테이너와 Ollama가 정상임을 확인했으나, 2026-10-01 현재 Docker Desktop Engine은 실행되지 않는다. 현재 컨테이너/API 상태는 미검증이다. 기존 관리자 1명 외 업무 데이터 0행 기준이었으며 `.env`와 `backups/`는 Git에서 제외된다.

**다음 작업**: VPS 비공개 스택과 웹 로그인·테스트 현장 생성/수정 확인까지 완료했다. PR #68 코드의 공개 HTTPS 적용은 사용자 확인 후 인증서·포트·로그인 검증이 필요하다. CRUD/PDF·재부팅 검증도 남았다. 실데이터 입력 전 DB Phase B의 격리 복원 관리자 대화형 로그인·로그아웃 검증과 Flyway·DB/업로드 파일 복구 체계를 완료한다. CI/CD의 운영 자동 배포는 그 이후 작업이다. 실제 MySQL DDL 확인 전에는 V1을 작성하지 않는다.

**검증 상태**: 2026-10-01 VPS private override Compose 15개 서비스 공개 포트 0, public override는 Caddy 80/443 외 비loopback 포트 0을 정적 검증했다. 독립 보안·배포 리뷰, 전체 Gradle 41 tasks(기존), 프론트 lint·33파일 112테스트·build, Python 5테스트, PR #68 CI 8개 성공. 새 VPS OS·별도 SSH 키 로그인·Docker daemon/Compose, 기본 런타임 health/401 및 웹 로그인·테스트 현장 생성/수정을 실측 성공했다. 공개 HTTPS, 업무 전체 CRUD/PDF·재부팅/복구 및 로컬 컨테이너 런타임 스모크는 미실시다. Config Client 중복 접속/프론트 번들 크기 경고는 P2 백로그다.

**자동화 가이드**: `docs/AUTOMATION_GUIDE.md` (8단계 + 5.5단계 자동 코드 리뷰). ADR-014 능동 발의 규칙은 상시 적용.

**활성화된 워크플로우 자동화** (2026-06-13 갱신):
- ✅ PR 생성 자동 (`gh pr create`)
- ✅ PR 자동 머지 (`gh pr merge --merge`) — SHA 검증 안전망 통과 시
- ✅ main 브랜치 보호 룰: force-push/delete 차단, PR 경로 강제
- ⚠️ 활성 PR 동안 develop 추가 push 시 PR 본문 즉시 갱신 의무 (RETROSPECTIVE 회고)

## 능동 발의 로그 (실험 종료 — 2026-07-04 확정, 아카이브)

> **[TRIAL] 종료**: 2회차 회고(2026-07-04) 승인률 100%(3/3)·정합성 이탈 0으로 **확정(CONFIRMED)**. ADR-014 v1.0 정식화, 규칙 상시 적용. 아래는 실험 기간 계측 기록 (상시 축적은 중단).

- [2026-07-03] 회고/규칙 진화 발의 — 판단: 회고 트리거를 "다음 세션 시작"→"발의 5건/실사이클 3회"로 조정 / 근거: 도입 세션(PR #34) 직후 종료로 회고 시점 발의 0건 공회전 / 대안: 트리거 유지 시 매 세션 빈 회고 반복 / 응답: 승인(실험 연장)
- [2026-07-03] BACKLOG 발의 — 판단: 미추적 AGENTS.md를 버전 관리에 편입 / 근거: 세션 시작부터 `?? AGENTS.md` 방치, .gitignore에도 없어 매 세션 노이즈 + 크로스툴 지침 drift 위험 / 대안: gitignore 로컬전용(공유 포기) 또는 방치(노이즈 지속) / 응답: 승인(커밋)
- [2026-07-04] 설계 자문 발의 — 판단: useListFilters를 Approach A(콜백 기반, scaffolding만 흡수)로 구현 / 근거: 5페이지 차이는 검색필드+술어뿐, reset·activeCount는 schema 도출 가능, tax/warranty 특수 술어는 선언형에 안 접힘 / 대안: Approach B 완전 선언형(가독성 ↓ 반려), 1페이지 PoC 우선 / 응답: 승인(A 전면 구현)

## 회고

→ **`.claude/RETROSPECTIVE.md`** 참조.
