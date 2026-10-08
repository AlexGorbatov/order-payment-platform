#!/usr/bin/env bash
# Gets an access token for the ops service account (client_credentials on the confidential client opp-ops-cli).
#
#   TOKEN=$(./scripts/ops-token.sh)
#   ./scripts/ops-token.sh --decode
#
# Env (or .env): KEYCLOAK_OPS_CLIENT_SECRET (default: the dev secret used by infra/docker-compose.yml).
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

decode=false
case "${1:-}" in
    "") ;;
    -d | --decode) decode=true ;;
    *) echo "usage: $(basename "$0") [--decode]" >&2; exit 2 ;;
esac

require curl jq base64
load_env

token="$(request_token \
    --data-urlencode grant_type=client_credentials \
    --data-urlencode client_id=opp-ops-cli \
    --data-urlencode "client_secret=${KEYCLOAK_OPS_CLIENT_SECRET:-opp-ops-cli-dev-secret}")"

if $decode; then jwt_payload "$token"; else printf '%s\n' "$token"; fi
