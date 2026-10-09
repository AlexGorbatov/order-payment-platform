# shellcheck shell=bash
# Shared helpers for scripts/*.sh. Source it; do not execute.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/infra/docker-compose.yml"
ENV_FILE="$REPO_ROOT/.env"

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-opp}"
TOKEN_ENDPOINT="$KEYCLOAK_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/token"

die() {
    echo "error: $*" >&2
    exit 1
}

require() {
    local cmd
    for cmd in "$@"; do
        command -v "$cmd" > /dev/null 2>&1 || die "'$cmd' is required but not installed"
    done
}

# Exports variables from the repo-root .env, if present (values already set in the environment win).
load_env() {
    [[ -f "$ENV_FILE" ]] || return 0
    local line key value
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
        key="${line%%=*}"
        [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
        [[ -n "${!key+x}" ]] && continue
        value="${line#*=}"
        # Strip one pair of surrounding quotes, as docker compose does.
        if [[ "$value" =~ ^\"(.*)\"$ || "$value" =~ ^\'(.*)\'$ ]]; then
            value="${BASH_REMATCH[1]}"
        fi
        export "$key=$value"
    done < "$ENV_FILE"
}

# Runs docker compose on infra/docker-compose.yml and the repo-root .env. Files listed in COMPOSE_OVERLAYS (space
# separated, relative to infra/) are layered on top, e.g. COMPOSE_OVERLAYS=docker-compose.stripe-test.yml.
compose() {
    local args=(-f "$COMPOSE_FILE") overlay
    for overlay in ${COMPOSE_OVERLAYS:-}; do
        args+=(-f "$REPO_ROOT/infra/$overlay")
    done
    [[ -f "$ENV_FILE" ]] && args+=(--env-file "$ENV_FILE")
    docker compose "${args[@]}" "$@"
}

# POSTs a form to the token endpoint and prints the access token; prints Keycloak's error otherwise.
request_token() {
    local response token
    response="$(curl -sS -X POST "$TOKEN_ENDPOINT" -H 'Content-Type: application/x-www-form-urlencoded' "$@")" \
        || die "Keycloak is not reachable at $KEYCLOAK_URL (is ./scripts/up.sh running?)"
    token="$(jq -r '.access_token // empty' <<< "$response" 2> /dev/null || true)"
    if [[ -z "$token" ]]; then
        die "token request failed: $(jq -r '"\(.error // "unknown"): \(.error_description // "")"' <<< "$response" 2> /dev/null || echo "$response")"
    fi
    printf '%s\n' "$token"
}

# Prints the decoded payload of a JWT (no signature verification — inspection only).
jwt_payload() {
    local payload
    payload="$(cut -d. -f2 <<< "$1" | tr '_-' '/+')"
    case $((${#payload} % 4)) in
        2) payload+='==' ;;
        3) payload+='=' ;;
    esac
    base64 -d <<< "$payload" | jq .
}
