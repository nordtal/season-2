#!/usr/bin/env bash
#
# The decisions in deploy/setup.sh, exercised without a Docker daemon, without a resolver and
# without a real environment file.
#
# WHY THIS EXISTS, and it is the same shape of reason as dev-test.sh and entrypoint-test.sh: two of
# the functions below decide whether a host gets a certificate or waits forever, and one decides
# whether a secrets file is read or executed. None of the three can be checked by running the script
# and looking - the run either waits or it deploys.
#
# WHAT IT CANNOT SAY ANYTHING ABOUT: whether the deployment then works. That needs a daemon, a name
# that resolves and a release, and it is an item on the checklist rather than a test.
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETUP="$HERE/setup.sh"
[[ -f "$SETUP" ]] || { echo "setup.sh not found beside this script" >&2; exit 1; }

failed=0
current_case=""
case_begin() { current_case="$1"; }
ok()  { printf '  ok    %s\n' "$1"; }
bad() { printf '  FAIL  %s: %s\n' "$current_case" "$1" >&2; failed=$(( failed + 1 )); }

# `$0` is this script, not setup.sh, which is what makes setup.sh's source guard return early.
# shellcheck source=deploy/setup.sh
source "$SETUP"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
ENV="$WORK/season-2.env"

cat > "$ENV" <<'FIXTURE'
# REPLACE_ME   this line is the documentation and must not count as a leftover
COMPOSE_PROFILES=db,bot,mc,backup,steward
PLAIN=value
QUOTED="in double quotes"
SINGLE='in single quotes'
export EXPORTED=exported
EMPTY=
BLANK=
STILL=REPLACE_ME
DANGEROUS=$(touch "$WORK/executed")
BRACKETS=a(b)c
NORDTAL_ACCESS_LANGUAGES='[
  { "role": "REPLACE_ME" }
]'
STEWARD_ENV_FILE=/etc/nordtal/season-2.env
FIXTURE

# ------------------------------------------------------------------------------------------------
case_begin "a value is read out of the file, quotes and all"
[[ "$(env_value "$ENV" PLAIN)"    == "value" ]]            || bad "PLAIN"
[[ "$(env_value "$ENV" QUOTED)"   == "in double quotes" ]] || bad "QUOTED kept its quotes"
[[ "$(env_value "$ENV" SINGLE)"   == "in single quotes" ]] || bad "SINGLE kept its quotes"
[[ "$(env_value "$ENV" EXPORTED)" == "exported" ]]         || bad "an exported assignment"
[[ "$(env_value "$ENV" EMPTY)"    == "" ]]                 || bad "EMPTY"
[[ "$(env_value "$ENV" ABSENT)"   == "" ]]                 || bad "a name that is not in the file"
[[ "$(env_value "$WORK/nope" PLAIN)" == "" ]]              || bad "a file that is not there"
ok "plain, quoted, exported, empty, absent"

# ------------------------------------------------------------------------------------------------
case_begin "reading the file never runs it"
# This is the whole reason env_value greps instead of sourcing. An environment file is not a script:
# `POSTGRES_PASSWORD=a(b` is a good password and a syntax error, and a value with a $( in it would
# be EXECUTED by a shell that sourced the file - as root, on a file that holds every secret.
env_value "$ENV" DANGEROUS >/dev/null
env_value "$ENV" BRACKETS  >/dev/null
[[ -e "$WORK/executed" ]] && bad "a value containing a command substitution was executed"
[[ "$(env_value "$ENV" BRACKETS)" == 'a(b)c' ]] || bad "a value with brackets did not survive"
ok "a command substitution is text, and brackets are not syntax"

# ------------------------------------------------------------------------------------------------
case_begin "missing means absent, empty or still REPLACE_ME - and nothing else"
missing="$(env_missing "$ENV" PLAIN QUOTED EMPTY BLANK ABSENT STILL)"
for name in EMPTY BLANK ABSENT STILL; do
    grep -qx "$name" <<<"$missing" || bad "$name was not reported missing"
done
for name in PLAIN QUOTED; do
    grep -qx "$name" <<<"$missing" && bad "$name was reported missing and is set"
done
ok "four missing, two set"

case_begin "the report is names, never values"
# What is in these variables on a real host is the Discord token and the database password. A
# report that echoed the value would put both in whatever log the operator pasted it into.
grep -q 'in double quotes' <<<"$missing" && bad "a value appeared in the report"
grep -q 'REPLACE_ME'       <<<"$missing" && bad "a value appeared in the report"
ok "only names came back"

# ------------------------------------------------------------------------------------------------
case_begin "a REPLACE_ME inside a value is found, one in a comment is not"
lines="$(env_replace_me_lines "$ENV")"
[[ -n "$lines" ]] || bad "the REPLACE_ME inside the language table was not found"
comment_line="$(grep -n 'this line is the documentation' "$ENV" | cut -d: -f1)"
grep -qx "$comment_line" <<<"$lines" && bad "the explanatory comment was reported as a leftover"
ok "the value is found, the documentation is not"

# ------------------------------------------------------------------------------------------------
case_begin "the steward profile has to be selected"
# Without it caddy, steward-ui and steward-deployer are defined and never started - and the stack
# comes up entirely healthy with no interface on it, which is the state nobody would think to check.
profiles_include "db,bot,mc,backup,steward" steward || bad "the real selection was refused"
profiles_include "steward" steward                  || bad "steward on its own was refused"
profiles_include "db, steward ,mc" steward          || bad "spaces around the name broke it"
for wrong in "" "db,bot,mc,backup" "stewards" "steward-ui" "db,bot,steward2"; do
    if profiles_include "$wrong" steward; then
        bad "'$wrong' was accepted as selecting the steward profile"
    fi
done
ok "the real selection passes; four near misses and an empty one do not"

# ------------------------------------------------------------------------------------------------
case_begin "the environment file's path has to be absolute"
is_absolute "/etc/nordtal/season-2.env" || bad "an absolute path was refused"
for wrong in "" "." "./.env" "env/.env" "~/.env"; do
    if is_absolute "$wrong"; then
        bad "'$wrong' was accepted as absolute"
    fi
done
ok "a relative path, a bare dot, a tilde and an empty string are all refused"

# ------------------------------------------------------------------------------------------------
case_begin "the name has to point at THIS host, entirely"
ours=$'45.155.173.214\n2a01:4f8::1'

[[ -z "$(addresses_not_ours "45.155.173.214" "$ours")" ]] \
    || bad "the host's own address was reported as a stray"
[[ -z "$(addresses_not_ours $'45.155.173.214\n2a01:4f8::1' "$ours")" ]] \
    || bad "both of the host's addresses were not accepted"

# A stale AAAA is the case this is strict for: on IPv4 the name looks perfect, and Let's Encrypt
# reaches somebody else's server over IPv6 and the certificate fails for a reason that is nowhere
# near this host.
strays="$(addresses_not_ours $'45.155.173.214\n2a01:dead::1' "$ours")"
[[ "$strays" == "2a01:dead::1" ]] || bad "a stale AAAA beside a correct A was not reported: $strays"

[[ "$(addresses_not_ours "203.0.113.9" "$ours")" == "203.0.113.9" ]] \
    || bad "an address belonging to another host was not reported"
ok "every resolved address has to be ours, not just one of them"

case_begin "a name that resolves to nothing does not read as a match"
# The failure that would be silent: an empty answer through a loop that only asks 'is anything
# wrong' is an empty list of wrongs, and setup.sh would carry straight on and deploy an interface
# whose certificate can never be issued.
for nothing in "" " " $'\n'; do
    if [[ -z "$(addresses_not_ours "$nothing" "$ours")" ]]; then
        bad "an empty resolution was accepted as pointing here"
    fi
done
ok "no answer is reported, not passed over"

# ------------------------------------------------------------------------------------------------

if (( failed > 0 )); then
    printf '\n%d case(s) failed\n' "$failed" >&2
    exit 1
fi
echo "all cases passed"
