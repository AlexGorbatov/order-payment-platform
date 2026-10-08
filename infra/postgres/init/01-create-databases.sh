#!/usr/bin/env bash
# Runs once, on first start with an empty volume (docker-entrypoint-initdb.d).
# One database and one owner role per service: a service's credentials cannot reach the other's data.
set -euo pipefail

create_service_db() {
    local db="$1" user="$2" password="$3"
    echo "Creating database ${db} owned by ${user}"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
        -v db="$db" -v user="$user" -v password="$password" <<'SQL'
CREATE ROLE :"user" LOGIN PASSWORD :'password';
CREATE DATABASE :"db" OWNER :"user";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
SQL
}

create_service_db orders_db "$ORDERS_DB_USER" "$ORDERS_DB_PASSWORD"
create_service_db payments_db "$PAYMENTS_DB_USER" "$PAYMENTS_DB_PASSWORD"
