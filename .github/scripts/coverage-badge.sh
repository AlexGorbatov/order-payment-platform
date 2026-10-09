#!/usr/bin/env bash
# Prints a shields.io endpoint document (https://shields.io/badges/endpoint-badge) with the line coverage of the
# JaCoCo reports found under the given directories (default: the repository). Used by the `badges` job of CI; the
# README badge reads the file it publishes.
#
#   .github/scripts/coverage-badge.sh [dir ...]
#
# Line coverage of everything JaCoCo measured: the libraries and both services, tests excluded. (The 80 % gate of the
# build applies to the `domain` and `application` packages of the services; this number is the whole of it.)
set -euo pipefail

dirs=("$@")
[[ ${#dirs[@]} -gt 0 ]] || dirs=(.)

files=()
while IFS= read -r file; do
    files+=("$file")
done < <(find "${dirs[@]}" -name jacoco.csv -path '*jacoco*' | sort)
[[ ${#files[@]} -gt 0 ]] || { echo "no jacoco.csv found under ${dirs[*]}" >&2; exit 1; }

# columns: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,...
read -r missed covered < <(awk -F, 'FNR > 1 { missed += $8; covered += $9 } END { print missed + 0, covered + 0 }' "${files[@]}")
total=$((missed + covered))
[[ $total -gt 0 ]] || { echo "the reports hold no lines" >&2; exit 1; }

percent=$((covered * 100 / total))
if   [[ $percent -ge 90 ]]; then color="brightgreen"
elif [[ $percent -ge 80 ]]; then color="green"
elif [[ $percent -ge 70 ]]; then color="yellowgreen"
elif [[ $percent -ge 60 ]]; then color="yellow"
else color="orange"
fi

printf '{"schemaVersion":1,"label":"coverage","message":"%s%%","color":"%s"}\n' "$percent" "$color"
echo "line coverage: $covered of $total lines in ${#files[@]} reports" >&2
