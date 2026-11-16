#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 -U postgres -d postgres -v pass="$OMS_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE payment_provider LOGIN PASSWORD %L', :'pass') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='payment_provider') \gexec
SELECT 'CREATE DATABASE payment_provider OWNER payment_provider' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='payment_provider') \gexec
REVOKE CONNECT ON DATABASE payment_provider FROM PUBLIC;
GRANT CONNECT ON DATABASE payment_provider TO payment_provider;
SQL
