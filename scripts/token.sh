#!/usr/bin/env bash
# DEV ONLY. Gets an access token for a realm user via the password grant on the public client opp-web
# (direct access grants are enabled only in the dev realm, see infra/keycloak/realm-opp.json).
#
#   TOKEN=$(./scripts/token.sh customer1)
#   ./scripts/token.sh --decode admin1          # prints the decoded payload instead of the token
#
# Env: OPP_USER_PASSWORD (default: password), KEYCLOAK_URL (default: http://localhost:8180).
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

usage() {
    echo "usage: $(basename "$0") [--decode] <user>   (users: customer1, customer2, admin1, ops1)" >&2
    exit 2
}

decode=false
user=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        -d | --decode) decode=true ;;
        -h | --help) usage ;;
        -*) usage ;;
        *) [[ -z "$user" ]] || usage; user="$1" ;;
    esac
    shift
done
[[ -n "$user" ]] || usage

require curl jq base64
load_env

token="$(request_token \
    --data-urlencode grant_type=password \
    --data-urlencode client_id=opp-web \
    --data-urlencode scope=openid \
    --data-urlencode "username=$user" \
    --data-urlencode "password=${OPP_USER_PASSWORD:-password}")"

if $decode; then jwt_payload "$token"; else printf '%s\n' "$token"; fi
