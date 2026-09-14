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
case_begin "a generated secret lands in the file whatever the assignment looks like"
# THE FAILURE THIS IS FOR: set_secret looked for the name one way and replaced it another.
# `env_value` accepts leading whitespace and an `export`, and so does the grep that decides whether
# there is a line to replace - but the awk that does the replacing compared the whole first field,
# so `  NAME=` was found and not replaced, and `export NAME=` was not found at all and appended a
# second assignment below the first one. Either way the script logged "generated" and compose got
# an empty value, which is the one outcome a setup script must never report as success.
for form in "STEWARD_API_TOKEN=" "  STEWARD_API_TOKEN=" "export STEWARD_API_TOKEN=" \
            "STEWARD_API_TOKEN = " "  export  STEWARD_API_TOKEN="; do
    secrets="$WORK/secret.env"
    printf 'BEFORE=1\n%s\nAFTER=2\n' "$form" > "$secrets"

    set_assignment "$secrets" STEWARD_API_TOKEN "$(printf '%064d' 7 | tr '0-9' 'a-f0-3')"

    written="$(env_value "$secrets" STEWARD_API_TOKEN)"
    [[ "$written" =~ ^[0-9a-f]{64}$ ]] \
        || bad "«$form» left the token as «$written» and said it had generated one"
    count="$(grep -cE '^[[:space:]]*(export[[:space:]]+)?STEWARD_API_TOKEN[[:space:]]*=' "$secrets")"
    [[ "$count" == "1" ]] || bad "«$form» left $count assignments of the name in the file"
    # And nothing else moved. A rewrite of the whole file is a rewrite of the whole file.
    [[ "$(env_value "$secrets" BEFORE)" == "1" && "$(env_value "$secrets" AFTER)" == "2" ]] \
        || bad "«$form» disturbed the lines around it"
done
ok "every spelling of an empty assignment is replaced, once, in place"

case_begin "a name that is not in the file is appended, not lost"
secrets="$WORK/secret.env"
printf 'BEFORE=1\n' > "$secrets"
set_assignment "$secrets" STEWARD_DEPLOYER_TOKEN "abc"
[[ "$(env_value "$secrets" STEWARD_DEPLOYER_TOKEN)" == "abc" ]] || bad "the new name was not written"
[[ "$(env_value "$secrets" BEFORE)" == "1" ]] || bad "the file it was appended to was disturbed"
ok "an absent name is appended once"

case_begin "an answer whose shape cannot be right is refused at the prompt"
# These run at the prompt, where the person can still fix it. The alternative is Caddy asking Let's
# Encrypt for a certificate for "https://steward.nordtal.eu" and a deployment that waits forever for
# a name with a scheme in it to resolve.
for id in 214906139328839681 1234567890123456 123456789012345678901; do
    looks_like_snowflake "$id" || bad "the snowflake $id was refused"
done
for wrong in "" "abc" "<@&214906139328839681>" "21490613932883968 " "12345" "214906139328839681x" \
             "2149061393288396810123456789"; do
    if looks_like_snowflake "$wrong"; then bad "«$wrong» was accepted as a Discord id"; fi
done
ok "a real id passes; brackets, letters, a short one and a long one do not"

for host in steward.dev.nordtal.eu nordtal.eu a.b; do
    looks_like_host "$host" || bad "the host name $host was refused"
done
for wrong in "" "https://steward.nordtal.eu" "steward.nordtal.eu/" "steward" "steward .eu" \
             "-steward.eu" "steward.eu." "steward..eu"; do
    if looks_like_host "$wrong"; then bad "«$wrong» was accepted as a host name"; fi
done
ok "a name passes; a URL, a path, a bare label and a stray dot do not"

for address in info@nordtal.eu a@b.c first.last+tag@sub.domain.org; do
    looks_like_email "$address" || bad "the address $address was refused"
done
for wrong in "" "info" "info@" "@nordtal.eu" "info@nordtal" "in fo@nordtal.eu" "a@b@c.de"; do
    if looks_like_email "$wrong"; then bad "«$wrong» was accepted as an e-mail address"; fi
done
ok "an address passes; a missing half, a missing dot and a space do not"

case_begin "the licence question takes yes for an answer and nothing else for one"
# The only question in the script whose default matters legally. Silence is no.
for yes in y Y yes YES Yes true; do
    answer_is_yes "$yes" || bad "«$yes» was not read as yes"
done
# `j` and `ja` are in this list rather than the one above on purpose: Steward speaks
# English, and a German spelling accepted at a prompt is German in the codebase.
for no in "" n N no nope maybe "y e s" 1 0 accept j ja; do
    if answer_is_yes "$no"; then bad "«$no» was read as yes"; fi
done
ok "six spellings of yes; everything else, silence and German included, is no"

case_begin "a written secret is never on a command line"
# /proc/<pid>/cmdline is world-readable, so `awk -v value=<the Discord token>` publishes it to every
# user on the host for as long as that awk runs. This asserts the mechanism rather than the race:
# the value reaches awk through the environment, so the command line cannot carry it.
grep -q 'awk -v name=.*-v value=' "$SETUP" && bad "a value is still passed to awk with -v"
grep -q 'ENVIRON\["SET_ASSIGNMENT_VALUE"\]' "$SETUP" || bad "the value does not come from the environment"
ok "set_assignment hands the value to awk through the environment"

case_begin "what a deployment demands of a person is the short list"
# The regression this guards: every role and channel used to be in REQUIRED, so a host could not be
# deployed until somebody had hand-written six snowflakes into a file. They are optional now - an
# unset channel means that feature is not served - and this is the list that is left.
for name in COMPOSE_PROFILES POSTGRES_PASSWORD VELOCITY_FORWARDING_SECRET EULA NORDTAL_BOT_TOKEN \
            NORDTAL_ACCESS_GUILD_ID NORDTAL_ACCESS_ROLES_ADMIN STEWARD_HOST STEWARD_ACME_EMAIL \
            STEWARD_ENV_FILE STEWARD_UI_DISCORD_CLIENT_ID STEWARD_UI_DISCORD_CLIENT_SECRET; do
    printf '%s\n' "${REQUIRED[@]}" | grep -qx "$name" || bad "$name is not required and should be"
done
for name in NORDTAL_ACCESS_ROLES_ACCESS NORDTAL_ACCESS_ROLES_DONOR NORDTAL_ACCESS_ROLES_ADMIN_PING \
            NORDTAL_ACCESS_CHANNELS_ADMIN NORDTAL_ACCESS_LANGUAGES NORDTAL_ACCESS_TIERS \
            NORDTAL_BOT_BUNQ_API_KEY NORDTAL_BOT_BUNQ_ACCOUNT_ID; do
    if printf '%s\n' "${REQUIRED[@]}" | grep -qx "$name"; then
        bad "$name is required, and a deployment must not stop for it"
    fi
done
ok "twelve required; the roles, the channels, the languages, the tiers and bunq are not"

# ------------------------------------------------------------------------------------------------
case_begin "a value full of shell metacharacters survives the round trip"
# The same argument env_value makes: an environment file is not a script. A generated secret is hex
# today, but this function is the one place a value is written, and the next caller may not be.
secrets="$WORK/secret.env"
printf 'NAME=old\n' > "$secrets"
set_assignment "$secrets" NAME 'a(b)c$(touch "'"$WORK"'/executed")'
[[ -e "$WORK/executed" ]] && bad "writing a value executed it"
[[ "$(env_value "$secrets" NAME)" == 'a(b)c$(touch "'"$WORK"'/executed")' ]] \
    || bad "a value with brackets and a substitution did not come back unchanged"
ok "a value is text on the way in and text on the way out"

# ------------------------------------------------------------------------------------------------

if (( failed > 0 )); then
    printf '\n%d case(s) failed\n' "$failed" >&2
    exit 1
fi
echo "all cases passed"
