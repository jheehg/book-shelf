#!/usr/bin/env bash
# 매일 03:30 cron. 7일치를 남긴다.
#   30 3 * * * /home/ubuntu/book-shelf/deploy/backup.sh >> /home/ubuntu/backup.log 2>&1
# 한계: 같은 서버 디스크에 있다. 인스턴스가 사라지면 같이 사라진다 → 서버 밖 복사는 Phase 4
set -euo pipefail
DIR=/home/ubuntu/backups
mkdir -p "$DIR"
FILE="$DIR/bookshelf-$(date +%F).sql.gz"
docker exec bookshelf-postgres pg_dump -U bookshelf -d bookshelf | gzip > "$FILE"
# 빈 파일이면 실패로 본다 — pg_dump가 조용히 실패해도 gzip은 성공한다
[ "$(gzip -dc "$FILE" | wc -c)" -gt 1000 ] || { echo "백업이 비었다: $FILE"; exit 1; }
find "$DIR" -name 'bookshelf-*.sql.gz' -mtime +7 -delete
echo "ok $FILE"
