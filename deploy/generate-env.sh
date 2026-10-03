#!/usr/bin/env bash
# Creates deploy/.env for the production stack, with fresh random secrets.
# Usage: deploy/generate-env.sh <domain>      (use "localhost" to try it locally)
set -euo pipefail
cd "$(dirname "$0")"

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <domain>" >&2
  exit 2
fi
if [[ -e .env ]]; then
  echo "deploy/.env already exists; refusing to overwrite its secrets." >&2
  echo "Losing HANDWRITING_DATA_KEY makes every stored enrolment unreadable;" >&2
  echo "changing LEDGER_SIGNING_KEY without rotating it (docs/ledger.md) makes clients distrust the ledger." >&2
  exit 1
fi

umask 077
cat > .env <<ENV
DOMAIN=$1
ADMIN_DOMAIN=admin.$1
DB_PASSWORD=$(openssl rand -hex 24)
JWT_SECRET=$(openssl rand -base64 32)
HANDWRITING_DATA_KEY=$(openssl rand -base64 32)
LEDGER_SIGNING_KEY=$(openssl rand -base64 32)
ENV
# Anchor checkpoints in Sigstore's public log (docs/ledger.md), but never from a local trial run.
if [[ "$1" != "localhost" && "$1" != *.localhost ]]; then
  echo "LEDGER_REKOR_URL=https://rekor.sigstore.dev" >> .env
fi
echo "Wrote deploy/.env (mode 600). Back it up somewhere safe and separate from the database backups."
echo "Point DNS for $1 and admin.$1 (the admin app, see docs/admin.md) at this server."
