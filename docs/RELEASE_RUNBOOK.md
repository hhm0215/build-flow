# VPS 반영 런북 (develop → main → 공개 테스트 VPS)

공개 테스트 VPS를 CI 검증된 `main`으로 올리는 절차다. **테스트 데이터 전용 운영**이며 실데이터 승격(복구·마이그레이션·최소 권한 게이트)은 이 런북의 범위가 아니다. `main` 병합과 VPS 적용은 사용자 승인 후에만 진행한다.

## 이번 반영의 변경 요약 (VPS `4e56596` → 새 `main`)

- 스키마(Hibernate `ddl-auto=update`가 자동 반영, 2026-10-22 로컬 리허설 확인):
  - `buildflow_site`: `clients.archived_at`, `sites.archived_at` nullable 추가
  - `buildflow_notification`: `defect_warranties.expiry_confirmed_at`, `outbox_events.suppressed_at` nullable 추가, `outbox_events.status` enum에 `SUPPRESSED` 추가, 새 테이블 `warranty_audit_events`·`warranty_file_retentions`
  - 다른 5개 스키마는 엔티티 변경 없음. Flyway는 기본 비활성이라 실행되지 않는다
- 기존 수기 보증은 `expiry_confirmed_at`이 비어 **"확인 필요"**로 보이고 자동 만료 알림에서 빠진다. 원본과 대조해 한 번 저장하면 확인된다(의도된 동작)
- 컨테이너: 앱 이미지 `TZ=Asia/Seoul`(업무 시각 KST 통일), notification `mem_limit` 640m→896m, notification에 `JWT_SECRET`·`REDIS_HOST` 환경변수 추가, 설정 서버 주소 변수 `CONFIG_SERVER_URL`로 변경
- 시간대 전환 주의: 기존 행의 시각 값은 보정하지 않는다. 적용 순간 처리 중인 outbox·OCR `PENDING`이 있으면 재시도·`FAILED` 전환될 수 있으므로 아래 사전 점검에서 0건을 확인한다

## SSH 배포 경로 (일회성 root 설정 후)

PC의 `buildflow-deploy` SSH 키로 접속한 에이전트가 배포할 수 있도록, root 소유 래퍼 하나만 sudo로 허용한다. 래퍼는 CI 검증된 `origin/main`과 정확히 같은 40자리 SHA만 배포하고, 아래 0~5단계(처리 중 작업 0건, 이미지 보존, 순차 빌드, 백업·격리 복원 확인, 기동)를 그대로 실행한다. 임의 명령은 받지 않는다. 일회성 설정은 Web Console root에서 한다.

```bash
cd /opt/buildflow && git fetch origin main:refs/remotes/origin/main
chown -R root:root /opt/buildflow && chmod -R go-w /opt/buildflow   # 래퍼가 소유권·쓰기 권한을 검사한다
id buildflow-deploy   # docker 그룹이 없어야 한다(있으면 sudo 제한이 무의미)
git show origin/main:scripts/vps/root/buildflow-release > /usr/local/sbin/buildflow-release   # CI 검증된 main 기준
chown root:root /usr/local/sbin/buildflow-release && chmod 755 /usr/local/sbin/buildflow-release
install -d -m 700 /etc/buildflow && printf '%s
' "실제-공개-호스트명" > /etc/buildflow/public_host && chmod 600 /etc/buildflow/public_host
printf '%s
' 'buildflow-deploy ALL=(root) NOPASSWD: /usr/local/sbin/buildflow-release' > /etc/sudoers.d/buildflow-release
chmod 440 /etc/sudoers.d/buildflow-release && visudo -cf /etc/sudoers.d/buildflow-release
```

래퍼를 갱신할 때도 같은 명령으로 `origin/main`에서 다시 설치한다(2026-10-23 보안 검토: 설치 원본을 develop에서 main으로, 저장소 root 소유·git 훅 비활성 검사 추가). 2026-10-23 비root 실측: `/opt/buildflow`·`.git`·`.git/config`·`.git/hooks` 모두 `root:root`, deploy 계정 쓰기 가능 파일 0, deploy 계정 그룹에 docker 없음.

이후 PC에서: `ssh buildflow-deploy@<서버> sudo buildflow-release status | deploy <main SHA> | rollback <이전 SHA>`. 접속 시 호스트 키는 2026-10-22 확인한 ED25519 `SHA256:7bqzBJZH/AYX6h8HYLuhDR/0+vUjcFqqo8fUWLJ2zCs`와 일치해야 한다(PC `known_hosts`의 호스트명 항목은 재설치 전 키라 IP 항목으로 접속한다).

## 0. 사전 조건

- `develop`→`main` PR(#99) 필수 CI 통과·SHA 대조 후 **사용자 승인으로 병합**된 `main` SHA
- 서버 Web Console root 세션, 작업 디렉터리 `/opt/buildflow`, 작업 트리 깨끗함
- 아래 명령의 `PUBLIC_HOST` 값은 Git 제외 운영 기록의 실제 호스트명으로 치환한다. `.env` 값은 출력하지 않는다

```bash
cd /opt/buildflow
export PUBLIC_HOST="your-vps-hostname.example"
C="docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml -f docker-compose.public.yml"
git status --short --branch                      # 출력이 브랜치 줄뿐이어야 한다
OLD_SHA=$(git rev-parse HEAD); echo "$OLD_SHA"    # 되돌리기 기준. 기록해 둔다
```

## 1. 처리 중 작업 0건 확인 (읽기 전용)

```bash
docker exec buildflow-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "
SELECT \"notification outbox 미완료\", COUNT(*) FROM buildflow_notification.outbox_events WHERE status IN (\"PENDING\",\"CLAIMED\");
SELECT \"OCR 처리 중\", COUNT(*) FROM buildflow_notification.defect_warranties WHERE ocr_status = \"PENDING\";
SELECT \"estimate outbox 미완료\", COUNT(*) FROM buildflow_estimate.outbox_events WHERE status IN (\"PENDING\",\"CLAIMED\");
SELECT \"purchase outbox 미완료\", COUNT(*) FROM buildflow_purchase.outbox_events WHERE status IN (\"PENDING\",\"CLAIMED\");
SELECT \"tax outbox 미완료\", COUNT(*) FROM buildflow_tax.outbox_events WHERE status IN (\"PENDING\",\"CLAIMED\");" 2>/dev/null'
```

모두 0이 아니면 몇 분 기다려 다시 확인한다. 계속 남으면 중단하고 원인을 기록한다.

## 2. 새 코드로 이미지 빌드 (서비스 중단 없음)

```bash
for img in $(docker ps --filter name=buildflow- --format '{{.Image}}' | sort -u); do
  docker image tag "$img" "${img%%:*}:pre-${OLD_SHA:0:7}"      # 되돌리기용 현재 이미지 보존
done
git fetch origin main:refs/remotes/origin/main
git switch --detach origin/main
test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" && git rev-parse HEAD
$C config --quiet
$C config --format json | python3 scripts/vps/verify_public_compose.py
bash scripts/vps/build_images.sh                    # 한 서비스씩 순차 빌드, 기존 컨테이너는 계속 동작
```

빌드 실패 시 `git switch --detach "$OLD_SHA"`로 돌아가고 중단한다(운영 컨테이너는 바뀌지 않았다).

## 3. 앱 정지 → 백업·격리 복원 확인

```bash
$C stop frontend gateway-server auth-service site-service estimate-service purchase-service tax-service notification-service chat-service
install -d -m 700 /root/buildflow-backups
bash scripts/vps/predeploy_backup.sh /root/buildflow-backups
```

마지막 줄이 `완료:`이고 `복원 행 수 일치`가 출력돼야 한다. 실패하면 **4단계로 가지 않고** `git switch --detach "$OLD_SHA"` 후 `$C start ...`(위와 같은 서비스)로 원래 상태를 되살린다. 백업 폴더(`buildflow.sql`·`warranty_uploads.tar`)는 민감 자료이므로 서버 밖 암호화 저장소로 옮긴다.

## 4. 새 컨테이너 기동

```bash
$C up -d --no-build
$C ps
```

## 5. 확인

```bash
$C logs --since=5m site-service notification-service | grep -iE "error|exception|alter table|create table" | head -40
docker exec buildflow-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "
SHOW COLUMNS FROM buildflow_site.sites LIKE \"archived_at\";
SHOW COLUMNS FROM buildflow_notification.outbox_events LIKE \"status\";
SHOW TABLES FROM buildflow_notification LIKE \"warranty_%\";" 2>/dev/null'
docker exec buildflow-site sh -c 'echo TZ=$TZ; date'
curl -s -o /dev/null -w '%{http_code}\n' "https://$PUBLIC_HOST/"                 # 200
curl -s -o /dev/null -w '%{http_code}\n' "https://$PUBLIC_HOST/api/v1/sites"     # 401
```

그다음 사용자가 브라우저로 로그인해 현장 목록·상세, 보증 목록(기존 수기 보증의 "확인 필요" 표시), 알림 시각, 합성 `[QA]` 현장 생성→보관을 확인한다. 결과는 `docs/SECURITY_OPERATIONS.md` S01·S10과 `.claude/PROGRESS.md`에 SHA와 함께 기록한다.

## 되돌리기

스키마 변경은 모두 추가(nullable 컬럼·새 테이블·enum 값 추가)라 이전 코드가 그대로 읽는다. 일반적으로 DB 복원 없이 코드·이미지만 되돌린다.

```bash
git switch --detach "$OLD_SHA"
for img in $(docker images --format '{{.Repository}}:{{.Tag}}' | grep ":pre-${OLD_SHA:0:7}$"); do
  docker image tag "$img" "${img%%:*}:latest"
done
$C up -d --no-build
```

새 코드가 데이터를 손상시킨 경우에만 3단계 백업으로 DB를 복원한다. 운영 volume 복원은 되돌리기 어려운 작업이므로 별도 승인 후 `docs/DATABASE_OPERATIONS.md` 원칙(격리 복원으로 먼저 확인)에 따라 진행한다. `down -v`나 volume 삭제는 사용하지 않는다.
