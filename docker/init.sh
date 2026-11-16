#!/bin/bash
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pass="$OMS_DB_PASSWORD" <<'SQL'
CREATE USER order_service PASSWORD :'pass';
CREATE DATABASE order_service OWNER order_service;
REVOKE CONNECT ON DATABASE order_service FROM PUBLIC;
GRANT CONNECT ON DATABASE order_service TO order_service;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pass="$OMS_DB_PASSWORD" <<'SQL'
CREATE USER inventory_service PASSWORD :'pass';
CREATE DATABASE inventory_service OWNER inventory_service;
REVOKE CONNECT ON DATABASE inventory_service FROM PUBLIC;
GRANT CONNECT ON DATABASE inventory_service TO inventory_service;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pass="$OMS_DB_PASSWORD" <<'SQL'
CREATE USER payment_service PASSWORD :'pass';
CREATE DATABASE payment_service OWNER payment_service;
REVOKE CONNECT ON DATABASE payment_service FROM PUBLIC;
GRANT CONNECT ON DATABASE payment_service TO payment_service;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pass="$OMS_DB_PASSWORD" <<'SQL'
CREATE USER fulfillment_service PASSWORD :'pass';
CREATE DATABASE fulfillment_service OWNER fulfillment_service;
REVOKE CONNECT ON DATABASE fulfillment_service FROM PUBLIC;
GRANT CONNECT ON DATABASE fulfillment_service TO fulfillment_service;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pass="$OMS_DB_PASSWORD" <<'SQL'
CREATE USER saga_orchestrator PASSWORD :'pass';
CREATE DATABASE saga_orchestrator OWNER saga_orchestrator;
REVOKE CONNECT ON DATABASE saga_orchestrator FROM PUBLIC;
GRANT CONNECT ON DATABASE saga_orchestrator TO saga_orchestrator;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pass="$OMS_DB_PASSWORD" <<'SQL'
CREATE USER order_query_service PASSWORD :'pass';
CREATE DATABASE order_query_service OWNER order_query_service;
REVOKE CONNECT ON DATABASE order_query_service FROM PUBLIC;
GRANT CONNECT ON DATABASE order_query_service TO order_query_service;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pass="$OMS_DB_PASSWORD" <<'SQL'
CREATE USER catalog_service PASSWORD :'pass';
CREATE DATABASE catalog_service OWNER catalog_service;
REVOKE CONNECT ON DATABASE catalog_service FROM PUBLIC;
GRANT CONNECT ON DATABASE catalog_service TO catalog_service;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pass="$OMS_DB_PASSWORD" <<'SQL'
CREATE USER notification_service PASSWORD :'pass';
CREATE DATABASE notification_service OWNER notification_service;
REVOKE CONNECT ON DATABASE notification_service FROM PUBLIC;
GRANT CONNECT ON DATABASE notification_service TO notification_service;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pass="$OMS_DB_PASSWORD" <<'SQL'
CREATE USER api_gateway PASSWORD :'pass';
CREATE DATABASE api_gateway OWNER api_gateway;
REVOKE CONNECT ON DATABASE api_gateway FROM PUBLIC;
GRANT CONNECT ON DATABASE api_gateway TO api_gateway;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pass="$OMS_DB_PASSWORD" <<'SQL'
CREATE USER payment_provider PASSWORD :'pass';
CREATE DATABASE payment_provider OWNER payment_provider;
REVOKE CONNECT ON DATABASE payment_provider FROM PUBLIC;
GRANT CONNECT ON DATABASE payment_provider TO payment_provider;
SQL
