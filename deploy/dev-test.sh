#!/usr/bin/env bash
# Tests the guard on `deploy/dev reset` without Docker: a service must be named and typed back.
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

case_begin "every service compose.yml runs a plugin on is resettable"
for service in proxy limbo hunger-games smp; do
    known_service "$service" || bad "$service is not a known service"
done
ok "the four servers are known"

case_begin "reset refuses anything that is not one of them"
# `deploy/dev reset` with no argument resets nothing.
for wrong in "" " " "postgres" "steward-worker" "all" "smp " "SMP" "../smp" "*"; do
    if known_service "$wrong"; then
        bad "'$wrong' was accepted as a service to reset"
    fi
done
ok "no argument, another container, a wildcard and a near miss are all refused"

case_begin "the confirmation has to be the name itself"
reset_confirmed smp smp || bad "the name typed back was not accepted"
ok "the name is accepted"

# "yes" and a bare Return are what somebody not reading the prompt answers.
for typed in "" " " "y" "Y" "yes" "YES" "SMP" "smp " "limbo" "*"; do
    if reset_confirmed smp "$typed"; then
        bad "'$typed' was accepted as confirmation for smp"
    fi
done
ok "yes, an empty line, another server's name and a wildcard are all refused"

case_begin "a confirmation cannot be satisfied by an empty target"
# If the target were ever empty, an empty answer matching it would delete on a bare Return.
reset_confirmed "" "" && bad "an empty target accepted an empty confirmation"
ok "an empty target confirms nothing"

case_begin "the local question set is the shared table minus what a checkout has no use for"
# Catches a question renamed in deploy/nordtal.sh but not in deploy/dev.
for question in "${LOCAL_QUESTIONS[@]}"; do
    [[ "$question" == STEWARD_UI_PUBLIC_URL ]] && continue
    found=false
    for shared in "${QUESTIONS[@]}"; do
        [[ "$shared" == "$question" ]] && found=true
    done
    $found || bad "$question is asked locally and is in no shared table"
done
ok "every borrowed question is one nordtal.sh defines"

apply_local_questions
for question in "${LOCAL_QUESTIONS[@]}"; do
    [[ -n "${QUESTION_KIND[$question]:-}" ]]   || bad "$question has no kind"
    [[ -n "${QUESTION_CHECK[$question]:-}" ]]  || bad "$question has no check"
    [[ -n "${QUESTION_PROMPT[$question]:-}" ]] || bad "$question has no prompt"
    [[ -n "${QUESTION_HINT[$question]:-}" ]]   || bad "$question has no hint"
done
ok "all four columns are filled for every one of them"

# The five Discord answers are the ones a local setup may decline. EULA and the address are not.
for optional in NORDTAL_BOT_TOKEN STEWARD_UI_DISCORD_CLIENT_ID STEWARD_UI_DISCORD_CLIENT_SECRET \
                NORDTAL_ACCESS_GUILD_ID NORDTAL_ACCESS_ROLES_ADMIN; do
    [[ "${QUESTION_KIND[$optional]}" == optional-* ]] \
        || bad "$optional is ${QUESTION_KIND[$optional]} locally, so a checkout cannot skip it"
done
[[ "${QUESTION_KIND[EULA]}" == licence ]] || bad "the EULA stopped being a licence question"
ok "Discord is skippable here and the licence is not"

case_begin "the address the browser uses, and the domain derived from it"
for good in http://localhost:5173 http://steward.localhost:8080 https://steward.dev.nordtal.eu; do
    looks_like_browser_url "$good" || bad "$good was refused"
done
ok "a scheme and a host, with or without a port"

# Configs.requirePublicUrl refuses a path or a query, since Discord compares the redirect URI as a string.
for wrong in "" " " localhost:5173 http:// https://host/ "https://host/auth" "http://host?x=1" \
             "ftp://host" "http://host:80 " "http://under_score"; do
    if looks_like_browser_url "$wrong"; then
        bad "'$wrong' was accepted as an address"
    fi
done
ok "no scheme, a trailing slash, a path, a query and a wrong scheme are all refused"

[[ "$(host_of_url http://localhost:5173)" == localhost ]] || bad "the port came with the host"
[[ "$(host_of_url https://steward.dev.nordtal.eu)" == steward.dev.nordtal.eu ]] \
    || bad "a host with no port did not survive"
ok "the relying party id is the host and nothing else"

case_begin "help prints the whole header, and the header names every command"
# The help range is found by pattern, so a new command without a help line is noticed.
header="$(sed -n '/^# The local season 2 network/,/^# Everything here runs/p' "$DEV")"
[[ -n "$header" ]] || bad "the help range matched nothing - a marker line was renamed"
grep -q "^# Everything here runs" <<<"$header" || bad "the help stops before the closing marker"

# The verbs, read off the dispatch itself rather than listed here a second time.
verbs="$(sed -n '/^case "\$command" in$/,/^esac$/p' "$DEV" \
    | grep -E '^    [a-z]+\)' | sed -E 's/^    ([a-z]+)\).*/\1/')"
[[ -n "$verbs" ]] || bad "no commands were found in the dispatch"
while read -r verb; do
    [[ -n "$verb" ]] || continue
    grep -qE "(^|[ |])$verb([ |]|$)" <<<"$header" || bad "deploy/dev $verb is in no line of the help"
done <<<"$verbs"
ok "every dispatched command has a line in the header the help prints"


if (( failed > 0 )); then
    printf '\n%d case(s) failed\n' "$failed" >&2
    exit 1
fi
echo "all cases passed"
