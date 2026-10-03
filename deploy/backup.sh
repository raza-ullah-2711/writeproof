#!/usr/bin/env bash
# Dumps the database to deploy/backups/writeproof-<UTC timestamp>.sql.gz.
# The dump holds ciphertext for letters and handwriting; restoring it needs the same
# HANDWRITING_DATA_KEY (deploy/.env), so back that up too, separately.
set -euo pipefail
# Behind an existing proxy: WRITEPROOF_COMPOSE=compose.proxied.yml deploy/backup.sh
compose="${WRITEPROOF_COMPOSE:-compose.yml}"
db=postgres
[[ "$compose" == compose.proxied.yml ]] && db=writeproof-db
cd "$(dirname "$0")"
mkdir -p backups
file="backups/writeproof-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
docker compose -f "$compose" exec -T "$db" pg_dump -U writeproof --clean --if-exists writeproof | gzip > "$file"
echo "Wrote deploy/$file"
