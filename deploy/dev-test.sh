#!/usr/bin/env bash
#
# The guard on `deploy/dev reset`, exercised without Docker.
#
# WHY THIS EXISTS AT ALL, and it is the same reason as deploy/minecraft/entrypoint-test.sh: `reset`
# deletes a server's whole volume, and on smp that volume holds Nordtal - a hand-built world that is
# in no repository and in no release. Everything else in deploy/ is verified by running it and
# looking; this is the piece where looking afterwards is too late.
#
# What it pins is deliberately small, because the guard is deliberately small: a service has to be
# NAMED, and the name has to be typed BACK. Both are functions in deploy/dev above its source guard,
# so this file can drive them the way entrypoint-test.sh drives the seeding.
#
# WHAT IT CANNOT SAY ANYTHING ABOUT: whether the volume then goes away. That needs Docker, and it is
# a checklist item rather than a test.
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEV="$HERE/dev"
[[ -f "$DEV" ]] || { echo "dev not found beside this script" >&2; exit 1; }

failed=0
current_case=""
case_begin() { current_case="$1"; }
ok()  { printf '  ok    %s\n' "$1"; }
bad() { printf '  FAIL  %s: %s\n' "$current_case" "$1" >&2; failed=$(( failed + 1 )); }

# `$0` is this script, not dev, which is what makes dev's source guard return early.
# shellcheck source=deploy/dev
source "$DEV"

# ------------------------------------------------------------------------------------------------
case_begin "every service compose.yml runs a plugin on is resettable"
for service in network-control limbo hunger-games smp; do
    known_service "$service" || bad "$service is not a known service"
done
ok "the four servers are known"

# ------------------------------------------------------------------------------------------------
case_begin "reset refuses anything that is not one of them"
# The empty string is the case that matters: `deploy/dev reset` with no argument must reset nothing
# rather than falling through to some default.
for wrong in "" " " "postgres" "updater" "all" "smp " "SMP" "../smp" "*"; do
    if known_service "$wrong"; then
        bad "'$wrong' was accepted as a service to reset"
    fi
done
ok "no argument, another container, a wildcard and a near miss are all refused"

# ------------------------------------------------------------------------------------------------
case_begin "the confirmation has to be the name itself"
reset_confirmed smp smp || bad "the name typed back was not accepted"
ok "the name is accepted"

# "yes" is what a person types when they have stopped reading, which is the exact moment this guard
# is for. So is the empty string - a bare Return on a prompt somebody did not look at.
for typed in "" " " "y" "Y" "yes" "YES" "SMP" "smp " "limbo" "*"; do
    if reset_confirmed smp "$typed"; then
        bad "'$typed' was accepted as confirmation for smp"
    fi
done
ok "yes, an empty line, another server's name and a wildcard are all refused"

# ------------------------------------------------------------------------------------------------
case_begin "a confirmation cannot be satisfied by an empty target"
# If the target were ever empty, an empty answer matching it would delete on a bare Return.
reset_confirmed "" "" && bad "an empty target accepted an empty confirmation"
ok "an empty target confirms nothing"

# ------------------------------------------------------------------------------------------------

if (( failed > 0 )); then
    printf '\n%d case(s) failed\n' "$failed" >&2
    exit 1
fi
echo "all cases passed"
