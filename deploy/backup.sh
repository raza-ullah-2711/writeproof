#!/usr/bin/env bash
# Dumps the database to deploy/backups/writeproof-<UTC timestamp>.sql.gz.
# The dump holds ciphertext for letters and handwriting; restoring it needs the same
# HANDWRITING_DATA_KEY (deploy/.env), so back that up too, separately.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p backups
file="backups/writeproof-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
docker compose -f compose.yml exec -T postgres pg_dump -U writeproof --clean --if-exists writeproof | gzip > "$file"
echo "Wrote deploy/$file"
