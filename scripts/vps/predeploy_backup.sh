#!/usr/bin/env bash
# 배포 직전 되돌리기용 백업과 격리 복원 확인.
#
#   sudo bash scripts/vps/predeploy_backup.sh /root/buildflow-backups
#
# 1) 앱 컨테이너(DB 쓰기 주체)가 멈춰 있는지 확인한다. 실행 중이면 중단한다.
# 2) 7개 스키마를 mysqldump로 받고 테이블별 정확한 행 수를 기록한다.
# 3) 보증 PDF volume을 tar로 묶는다(DB의 file_path가 가리키는 원본).
# 4) 임시 MySQL 컨테이너·volume에 덤프를 복원해 행 수가 같은지 확인하고, 그 임시 자원만 지운다.
# 비밀번호는 컨테이너 환경변수에서만 읽고 명령행·파일·로그에 남기지 않는다. 운영 volume은 읽기만 한다.
set -euo pipefail
umask 077

OUT_ROOT=${1:?usage: predeploy_backup.sh <existing private output root>}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-buildflow-mysql}
NOTIFICATION_CONTAINER=${NOTIFICATION_CONTAINER:-buildflow-notification}
WRITERS=(buildflow-auth buildflow-site buildflow-estimate buildflow-purchase buildflow-tax buildflow-notification buildflow-chat)
SCHEMAS=(buildflow_auth buildflow_site buildflow_estimate buildflow_purchase buildflow_tax buildflow_notification buildflow_chat)

fail() { echo "중단: $*" >&2; exit 1; }
# Git Bash(로컬 리허설)에서는 docker -v에 Windows 경로가 필요하다. Linux에서는 그대로 쓴다.
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
export MSYS_NO_PATHCONV=1

[ -d "$OUT_ROOT" ] || fail "출력 루트가 없다: $OUT_ROOT (미리 만들고 0700으로 둔다)"
if ! command -v cygpath >/dev/null 2>&1; then
  [ "$(stat -c '%u %a' "$OUT_ROOT")" = "$(id -u) 700" ] || fail "출력 루트는 현재 사용자 소유 0700이어야 한다"
fi

[ "$(docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null)" = "true" ] || fail "$MYSQL_CONTAINER 가 실행 중이 아니다"
for c in "${WRITERS[@]}"; do
  if [ "$(docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null || true)" = "true" ]; then
    fail "$c 가 실행 중이다. 일관된 시점을 위해 앱 컨테이너를 먼저 멈춘다(mysql은 유지)"
  fi
done

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT="$OUT_ROOT/$STAMP"
mkdir "$OUT"
echo "백업 위치: $OUT"

mysql_q() { docker exec -i "$MYSQL_CONTAINER" sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B "$@" 2>/dev/null' sh "$@"; }

count_sql() {
  local in_list
  in_list=$(printf "'%s'," "${SCHEMAS[@]}"); in_list=${in_list%,}
  mysql_q -e "SELECT CONCAT('SELECT ''', table_schema, '.', table_name, ''', COUNT(*) FROM \`', table_schema, '\`.\`', table_name, '\` UNION ALL') FROM information_schema.tables WHERE table_schema IN ($in_list) AND table_type='BASE TABLE' ORDER BY table_schema, table_name" \
    | sed '$ s/ UNION ALL$//'
}

echo "1/4 덤프"
docker exec "$MYSQL_CONTAINER" sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --quick --routines --triggers --events --no-tablespaces --databases "$@" 2>/dev/null' sh "${SCHEMAS[@]}" > "$OUT/buildflow.sql"
tail -n 1 "$OUT/buildflow.sql" | grep -q "^-- Dump completed" || fail "덤프가 완료 표시 없이 끝났다"
SQL_COUNTS=$(count_sql)
[ -n "$SQL_COUNTS" ] || fail "테이블 목록을 읽지 못했다"
printf '%s\n' "$SQL_COUNTS" > "$OUT/count.sql"
mysql_q < "$OUT/count.sql" | sort > "$OUT/source_counts.tsv"
echo "  테이블 $(wc -l < "$OUT/source_counts.tsv")개, 행 합계 $(awk '{s+=$2} END {print s+0}' "$OUT/source_counts.tsv")"

echo "2/4 보증 PDF volume"
UPLOADS_VOLUME=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/app/uploads/warranties"}}{{.Name}}{{end}}{{end}}' "$NOTIFICATION_CONTAINER")
[ -n "$UPLOADS_VOLUME" ] || fail "보증 업로드 volume을 찾지 못했다"
APP_IMAGE=$(docker inspect -f '{{.Config.Image}}' "$NOTIFICATION_CONTAINER")
docker run --rm --user 0 --network none --entrypoint tar \
  -v "$UPLOADS_VOLUME:/src:ro" -v "$(host_path "$OUT"):/out" "$APP_IMAGE" -C /src -cf /out/warranty_uploads.tar .
echo "  volume $UPLOADS_VOLUME, 파일 $(tar -tf - < "$OUT/warranty_uploads.tar" | grep -vc '/$' || true)개"

echo "3/4 체크섬"
(cd "$OUT" && sha256sum buildflow.sql warranty_uploads.tar source_counts.tsv > SHA256SUMS)

echo "4/4 격리 복원 확인"
NONCE=$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')
RESTORE="bf-restore-check-$NONCE"
MYSQL_IMAGE=$(docker inspect -f '{{.Config.Image}}' "$MYSQL_CONTAINER")
MYSQL_ROOT_PASSWORD=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
export MYSQL_ROOT_PASSWORD
cleanup() {
  if [ "$(docker inspect -f '{{index .Config.Labels "buildflow.restore-check"}}' "$RESTORE" 2>/dev/null || true)" = "$NONCE" ]; then
    docker rm -f "$RESTORE" >/dev/null
  fi
  if [ "$(docker volume inspect -f '{{index .Labels "buildflow.restore-check"}}' "$RESTORE" 2>/dev/null || true)" = "$NONCE" ]; then
    docker volume rm "$RESTORE" >/dev/null
  fi
}
trap cleanup EXIT
docker volume create --label "buildflow.restore-check=$NONCE" "$RESTORE" >/dev/null
docker run -d --name "$RESTORE" --label "buildflow.restore-check=$NONCE" --network none \
  -e MYSQL_ROOT_PASSWORD -v "$RESTORE:/var/lib/mysql" "$MYSQL_IMAGE" >/dev/null
restore_q() { docker exec -i "$RESTORE" sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B "$@" 2>/dev/null' sh "$@"; }
# 공식 이미지는 초기화용 임시 서버를 띄웠다 내린 뒤 본 서버를 연다. 본 서버(port 3306) 준비 로그를 기다린다.
for _ in $(seq 1 120); do
  docker logs "$RESTORE" 2>&1 | grep -q "ready for connections.*port: 3306" && break
  sleep 2
done
restore_q -e "SELECT 1" >/dev/null || fail "임시 MySQL이 준비되지 않았다"
restore_q < "$OUT/buildflow.sql"
restore_q < "$OUT/count.sql" | sort > "$OUT/restored_counts.tsv"
if ! diff -q "$OUT/source_counts.tsv" "$OUT/restored_counts.tsv" >/dev/null; then
  diff "$OUT/source_counts.tsv" "$OUT/restored_counts.tsv" >&2 || true
  fail "복원 행 수가 원본과 다르다"
fi
echo "  복원 행 수 일치 ($(wc -l < "$OUT/restored_counts.tsv")개 테이블)"

{
  echo "created_utc=$STAMP"
  echo "mysql_image=$MYSQL_IMAGE"
  echo "uploads_volume=$UPLOADS_VOLUME"
  echo "tables=$(wc -l < "$OUT/source_counts.tsv")"
  echo "restore_check=PASS"
} > "$OUT/summary.txt"
echo "완료: $OUT (buildflow.sql·warranty_uploads.tar는 민감 자료다. 서버 밖 암호화 저장소로 옮겨 보관한다)"
