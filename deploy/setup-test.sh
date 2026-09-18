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

# Membership without a pipe, and that is the whole point (season-2-ops/27, 2026-09-16). This used to
# be `printf '%s\n' "${REQUIRED[@]}" | grep -qx "$name"`, which is wrong under the `pipefail` on the
# line above: grep -q closes the pipe the moment it matches, printf upstream takes SIGPIPE, and
# pipefail reports 141 for a pipeline whose grep succeeded. Measured on this host: five misses in
# nine thousand, which is about one in a hundred whole runs of this file - enough that CI went red
# on a commit and green on the identical tree when it was re-run, which is the worst kind of guard.
contains() {
    local needle="$1"; shift
    local item
    for item in "$@"; do
        [[ "$item" == "$needle" ]] && return 0
    done
    return 1
}
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
# steward/107: setup.sh's own `up` (via steward-deployer's Compose#pull) pulls every image compose.yml
# names, unconditionally, before it stops anything - there is no `pull_policy` anywhere that would
# make it "only what's missing". A tag the registry still answers for silently replaces whatever this
# host built locally under the same name, which is exactly how the release-less workaround
# deploy/README.md documents (`docker compose build <service>` + `up -d --no-deps <service>`) gets
# quietly undone by the next `setup.sh` run. at_risk_images is the decision that has to catch this
# before §7's `up` ever runs - given as data, not as a live docker call, the same way
# addresses_not_ours above takes `resolved`/`ours` as strings rather than calling `getent` itself.
#
# THE FIRST VERSION OF THIS CHECK COMPARED ONLY RepoDigests AGAINST "[]", and measured wrong on this
# very host: the containerd image store (unlike the classic one) assigns a RepoDigest to a locally
# built image too - identical to its image ID - so `loc == "[]"` never fired here, and the warning
# was silent for exactly the case it exists for (steward found this by rebuilding steward-ui and
# steward-deployer locally on 2026-09-17 and watching the check say nothing). What actually answers
# "would a pull replace this" is a THIRD input: what the registry currently serves under the tag.
case_begin "no local RepoDigests at all is RISK, whatever the registry says"
pairs=$'nordtal/discord-bot:redtest-107\tdiscord-bot'
local_digests=$'nordtal/discord-bot:redtest-107\t[]'
registry_digests=$'nordtal/discord-bot:redtest-107\tsha256:aaaaaaaaaaaa'
result="$(at_risk_images "$pairs" "$local_digests" "$registry_digests")"
[[ "$result" == $'RISK\tnordtal/discord-bot:redtest-107\tdiscord-bot' ]] \
    || bad "expected a RISK line for the image with no local digest, got: «$result»"
ok "an image with no RepoDigests is RISK even when the registry answered"

case_begin "local and registry digest agree - silent"
pairs=$'ghcr.io/nordtal/minecraft:latest\tsmp'
local_digests=$'ghcr.io/nordtal/minecraft:latest\t["ghcr.io/nordtal/minecraft@sha256:same0000"]'
registry_digests=$'ghcr.io/nordtal/minecraft:latest\tsha256:same0000'
[[ -z "$(at_risk_images "$pairs" "$local_digests" "$registry_digests")" ]] \
    || bad "a matching local and registry digest was still reported"
ok "an image whose local digest matches what the registry currently serves is silent"

case_begin "local and registry digest disagree - RISK (the containerd-store case)"
# This is the shape steward measured against the real host: `docker image inspect` on
# ghcr.io/nordtal/steward-ui:latest, freshly rebuilt with \`docker compose build\`, answered a
# RepoDigest (containerd's own image ID, not "[]"), and \`docker buildx imagetools inspect\` against
# the same tag answered a DIFFERENT manifest digest - the one the last release published.
pairs=$'ghcr.io/nordtal/steward-ui:latest\tsteward-ui'
local_digests=$'ghcr.io/nordtal/steward-ui:latest\t["ghcr.io/nordtal/steward-ui@sha256:a429f360e0c8"]'
registry_digests=$'ghcr.io/nordtal/steward-ui:latest\tsha256:39d016200b67'
result="$(at_risk_images "$pairs" "$local_digests" "$registry_digests")"
[[ "$result" == $'RISK\tghcr.io/nordtal/steward-ui:latest\tsteward-ui' ]] \
    || bad "a locally built image with a mismatched registry digest was not reported, got: «$result»"
ok "a locally built image is RISK when the registry's current digest differs from its own"

case_begin "the registry did not answer - UNKNOWN, neither cleared nor flagged"
# Folding "could not compare" into "safe" trades one silent failure for another - a private image, a
# network hiccup or a tag the registry has never heard of must not read the same as "checked, fine".
pairs=$'ghcr.io/nordtal/steward-worker:latest\tsteward-worker'
local_digests=$'ghcr.io/nordtal/steward-worker:latest\t["ghcr.io/nordtal/steward-worker@sha256:ea9364be13a9"]'
result="$(at_risk_images "$pairs" "$local_digests" "")"
[[ "$result" == $'UNKNOWN\tghcr.io/nordtal/steward-worker:latest\tsteward-worker' ]] \
    || bad "a registry that did not answer should be its own line, got: «$result»"
ok "no registry answer is its own line, not a pass and not an alarm"

case_begin "one locally built image used by several services names all of them"
pairs_shared=$'nordtal/minecraft:redtest\tsmp
nordtal/minecraft:redtest\tlimbo'
digests_shared=$'nordtal/minecraft:redtest\t[]'
result_shared="$(at_risk_images "$pairs_shared" "$digests_shared" "")"
[[ "$result_shared" == $'RISK\tnordtal/minecraft:redtest\tsmp,limbo' ]] \
    || bad "expected both services comma-joined, got: «$result_shared»"
ok "services sharing one at-risk image are comma-joined on its one line"

case_begin "an image never seen locally, or with nothing at all to go on, is silent"
[[ -z "$(at_risk_images "" "" "")" ]] || bad "empty compose config produced a finding"
[[ -z "$(at_risk_images "$pairs_shared" "" "")" ]] || bad "an image absent from local_digests produced a finding"
ok "no compose config and no local digests both come back silent, not as a false alarm"

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
            STEWARD_ENV_FILE STEWARD_ENV_DIR STEWARD_ENV_FILE_NAME STEWARD_UI_DISCORD_CLIENT_ID \
            STEWARD_UI_DISCORD_CLIENT_SECRET; do
    contains "$name" "${REQUIRED[@]}" || bad "$name is not required and should be"
done
for name in NORDTAL_ACCESS_ROLES_ACCESS NORDTAL_ACCESS_ROLES_DONOR NORDTAL_ACCESS_ROLES_ADMIN_PING \
            NORDTAL_ACCESS_CHANNELS_ADMIN NORDTAL_ACCESS_LANGUAGES NORDTAL_ACCESS_TIERS \
            NORDTAL_STEWARD_BUNQ_API_KEY NORDTAL_STEWARD_BUNQ_ACCOUNT_ID; do
    if contains "$name" "${REQUIRED[@]}"; then
        bad "$name is required, and a deployment must not stop for it"
    fi
done
ok "fourteen required; the roles, the channels, the languages, the tiers and bunq are not"

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
