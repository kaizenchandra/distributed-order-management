#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
umask 077
if [ ! -f .env ]; then
  printf 'OMS_DB_PASSWORD=%s
' "$(openssl rand -hex 24)" > .env
fi
if ! grep -q OMS_SERVICE_SECRET .env; then
  printf 'OMS_SERVICE_SECRET=%s\n' "$(openssl rand -hex 24)" >> .env
fi
if ! grep -q '^PROVIDER_KEY=' .env; then
  printf 'PROVIDER_KEY=%s\n' "$(openssl rand -hex 32)" >> .env
fi
set -a
. ./.env
set +a
sed "s/__SERVICE_SECRET__/$OMS_SERVICE_SECRET/g" docker/realm.json > docker/realm.local.json
python3 scripts/configure-kafka.py
