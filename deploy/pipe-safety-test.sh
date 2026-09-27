#!/usr/bin/env bash
# Finds, in every script under `pipefail`, a pipe into a reader that can stop early, such as
# `| head` or `| grep -q`: the producer then gets SIGPIPE and the pipeline exits 141 on a match.
# The fix is a reader that reads to EOF, such as `sed -n '1p'`. Here-strings are not pipes.
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

failed=0
report() { printf '  FAIL  %s\n' "$1" >&2; failed=$(( failed + 1 )); }

# Harmless hits as "<path from the repo root>:<line>", each with its reason.
EXCEPTIONS=(
)
exception_hit=()

is_exception() {
    local hit="$1" e
    for e in "${EXCEPTIONS[@]}"; do
        if [[ "$e" == "$hit" ]]; then
            exception_hit+=("$hit")
            return 0
        fi
    done
    return 1
}

# Every script that sets pipefail, anywhere in the repository; `-I` skips binary files.
mapfile -t targets < <(grep -rlIE 'set -o pipefail|set -Eeuo pipefail|set -euo pipefail' \
    --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude-dir=node_modules \
    --exclude-dir=.idea --exclude-dir=.vscode --exclude-dir=.fleet \
    "$ROOT" | sort)

[[ ${#targets[@]} -gt 0 ]] \
    || { echo "no file under pipefail was found anywhere in the repository - the discovery above broke" >&2; exit 1; }

for file in "${targets[@]}"; do
    # Excluded, since its own regex literals look like pipes.
    [[ "$file" == "$HERE/pipe-safety-test.sh" ]] && continue

    rel="${file#"$ROOT"/}"
    lineno=0
    while IFS= read -r line || [[ -n "$line" ]]; do
        lineno=$(( lineno + 1 ))

        # Comments hold no code.
        trimmed="${line#"${line%%[![:space:]]*}"}"
        [[ "$trimmed" == \#* ]] && continue

        # Drop `||` first, so a remaining `|` is a real pipe.
        stripped="${line//||/}"
        [[ "$stripped" == *"|"* ]] || continue

        risky=""
        if [[ "$stripped" =~ \|[[:space:]]*head([[:space:]]|$) ]]; then
            risky="head closes its end of the pipe as soon as it has enough lines"
        elif [[ "$stripped" =~ \|[[:space:]]*grep[^\|]*(-[A-Za-z]*q|-[A-Za-z]*l|-[A-Za-z]*L|-m[[:space:]]*[0-9]) ]]; then
            risky="grep -q/-l/-L/-m stops reading at its first match"
        elif [[ "$stripped" =~ \|[[:space:]]*sed ]] && [[ "$stripped" =~ [\;\{\'\"\$0-9]q([[:space:]\'\"\}]|$) ]]; then
            risky="sed's q command quits before its input reaches EOF"
        elif [[ "$stripped" =~ \|[[:space:]]*(awk|perl) ]] && [[ "$stripped" =~ exit ]]; then
            risky="an explicit exit inside awk/perl quits before its input reaches EOF"
        fi
        [[ -n "$risky" ]] || continue

        hit="$rel:$lineno"
        is_exception "$hit" && continue
        report "$hit under pipefail - $risky:
        $line"
    done < "$file"
done

# A listed exception that matches nothing is stale.
for e in "${EXCEPTIONS[@]}"; do
    seen=0
    for s in "${exception_hit[@]:-}"; do
        [[ "$s" == "$e" ]] && { seen=1; break; }
    done
    (( seen )) || report "$e is in EXCEPTIONS but nothing matches it any more - the line moved or was fixed outright; update the list"
done

if (( failed > 0 )); then
    printf '\n%d finding(s)\n' "$failed" >&2
    exit 1
fi
echo "every pipefail script is clean of early-terminating pipe readers"
