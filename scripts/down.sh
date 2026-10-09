#!/usr/bin/env bash
# Stops the local infrastructure (all profiles).
#
#   ./scripts/down.sh        # keep data volumes
#   ./scripts/down.sh -v     # also delete volumes (PostgreSQL, Kafka, Prometheus, Grafana data)
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

args=(--remove-orphans)
case "${1:-}" in
    "") ;;
    -v | --volumes) args+=(--volumes) ;;
    *) echo "usage: $(basename "$0") [-v|--volumes]" >&2; exit 2 ;;
esac

require docker
compose --profile apps --profile observability --profile stripe-test down "${args[@]}"
