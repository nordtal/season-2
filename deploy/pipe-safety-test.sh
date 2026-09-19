#!/usr/bin/env bash
#
# The guard season-2-ops/27 asked for, 2026-09-17: every script in this repository that runs under
# `pipefail` (`set -o pipefail`, `set -Eeuo pipefail`, `set -euo pipefail`), searched for a pipe
# into a reader that can stop reading before its producer is done writing - `| head`, `| grep -q`,
# and anything else shaped the same way.
#
# THE BUG THIS IS FOR: `printf '%s\n' "${REQUIRED[@]}" | grep -qx "$name"` under `pipefail`. grep -q
# exits the moment it matches and closes its end of the pipe; if printf still has more lines queued
# - which it does whenever the match is not the last element - the next write it attempts gets
# SIGPIPE, and `pipefail` reports that 141 as the whole pipeline's exit status, even though grep
# found exactly what it was looking for. Measured on this host: 5 misses in 9,000 tries, about 1%
# per whole run of the script that carried it - rare enough to read as "flaky CI", frequent enough
# to have actually happened (finding this ticket opens with). `deploy/nordtal-test.sh`,
# `deploy/restore.sh` and `deploy/minecraft/entrypoint.sh` each carried a real instance of this and
# were fixed by hand, one grep -rn at a time - which is exactly the process that does not scale to
# a fifth instance nobody thought to search for.
#
# WHAT THIS CANNOT DECIDE ON ITS OWN: whether a hit is a real bug or a harmless one. `sed -n '1p'`
# asks for "just the first line" exactly as much as `head -n1` does, and is the fix used throughout
# this repository for exactly that reason: unlike `head`, `sed -n` reads its input to EOF instead of
# quitting the moment its address matches, so there is nothing left for it to fail to write. A
# `docker compose ps` that only ever answers one line today might answer two tomorrow. That
# judgement is the EXCEPTIONS list below - written by hand, one entry per hit, each with the reason
# it stays. Same shape as the named `Set<String>` allow-lists in GateTest
# (steward-ui/src/test/java/eu/nordtal/s2/steward/ui/GateTest.java): a decision, not a rule, and one
# a reviewer can see in full without deriving it from the code that uses it.
#
# WHAT THIS CANNOT SEE AT ALL, BY CONSTRUCTION RATHER THAN BY EXCEPTION: a pipe fed by a here-string
# or here-doc (`<<<`, `<<EOF`) is not a pipe in the sense this bug needs, because there is no
# producer PROCESS on the other end for an early-exiting reader to race against - the whole
# here-string already sits in a temporary file descriptor before the reader even starts. So
# `grep -qx "$name" <<<"$missing"` (deploy/nordtal.sh, deploy/nordtal-test.sh) never matches the pattern
# below at all; it is not the kind of thing this bug can happen to.
#
# WHAT THIS CANNOT SAY ANYTHING ABOUT: whether a hit that IS a real bug ever actually fires. That
# needs the same kind of stress loop the fixes above were measured with, run by hand, once, for
# whichever construct is in question - this script only finds the shape.
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

failed=0
report() { printf '  FAIL  %s\n' "$1" >&2; failed=$(( failed + 1 )); }

# --- the exception list -----------------------------------------------------------------------
# "<path relative to the repo root>:<line number>", exactly as this script's own report prints it.
# Empty today: an audit of every pipefail script in this repository on 2026-09-17, done to build
# this guard, found three surviving instances of the bug (deploy/dev's `version`, `compose_project`
# and the reset volume lookup, all `sed ... | head -n1`) and none that were worth keeping instead of
# fixing outright - the fix is one word, so there was nothing here cheap enough to argue for keeping
# the anti-pattern over. The list stays here, present but empty, so the next genuinely harmless case
# has somewhere to go without turning this file into a new rule.
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

# Discovered, not hardcoded to deploy/ - season-2-ops/27 asked for both, and a script that sets
# pipefail somewhere else tomorrow should not need this file edited to be covered. `-I` skips
# binary files (the resource pack carries plenty) rather than printing "binary file matches".
mapfile -t targets < <(grep -rlIE 'set -o pipefail|set -Eeuo pipefail|set -euo pipefail' \
    --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude-dir=node_modules \
    --exclude-dir=.idea --exclude-dir=.vscode --exclude-dir=.fleet \
    "$ROOT" | sort)

[[ ${#targets[@]} -gt 0 ]] \
    || { echo "no file under pipefail was found anywhere in the repository - the discovery above broke" >&2; exit 1; }

for file in "${targets[@]}"; do
    # This script excludes itself. It has to talk about `| head` and `| grep -q` to describe what
    # it is looking for, in regex literals that contain a bare `|` themselves - and a checker that
    # flags its own pattern list is not a finding, it is noise nobody can fix by touching a
    # pipeline. (Seen once, 2026-09-17: this line, unexcluded, reported itself over the very regex
    # two lines below reading "\|[[:space:]]*(awk|perl)" as a pipe into awk.)
    [[ "$file" == "$HERE/pipe-safety-test.sh" ]] && continue

    rel="${file#"$ROOT"/}"
    lineno=0
    while IFS= read -r line || [[ -n "$line" ]]; do
        lineno=$(( lineno + 1 ))

        # A comment contributes no code - and this file's own header, plus
        # deploy/nordtal-test.sh's, quote the old `printf | grep -qx` bug in prose. Without this,
        # both would report themselves.
        trimmed="${line#"${line%%[![:space:]]*}"}"
        [[ "$trimmed" == \#* ]] && continue

        # A real pipe, not `||`: remove every literal `||` first, so a lone `|` left over is an
        # actual pipeline rather than a boolean fallback.
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

# A listed exception that no longer matches anything is a stale decision - this guard would then
# pass having verified nothing about it, which is exactly the silent drift a hand-run
# `grep -rn "| grep -q\|| head -" deploy/` (season-2-ops/27's own original method) cannot catch.
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
