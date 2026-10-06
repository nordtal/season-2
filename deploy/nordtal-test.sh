#!/usr/bin/env bash
# Tests the decisions in deploy/nordtal.sh without a Docker daemon, a resolver or a real environment file.
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETUP="$HERE/nordtal.sh"
[[ -f "$SETUP" ]] || { echo "nordtal.sh not found beside this script" >&2; exit 1; }

failed=0
current_case=""
case_begin() { current_case="$1"; }

# Membership without a pipe, which `grep -q` would break under `pipefail`.
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

# `$0` is this script, not nordtal.sh, which is what makes nordtal.sh's source guard return early.
# shellcheck source=deploy/nordtal.sh
source "$SETUP"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
ENV="$WORK/season-2.env"

cat > "$ENV" <<'FIXTURE'
# REPLACE_ME   this line is the documentation and must not count as a leftover
COMPOSE_PROFILES=db,bot,mc,steward
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

case_begin "a value is read out of the file, quotes and all"
[[ "$(env_value "$ENV" PLAIN)"    == "value" ]]            || bad "PLAIN"
[[ "$(env_value "$ENV" QUOTED)"   == "in double quotes" ]] || bad "QUOTED kept its quotes"
[[ "$(env_value "$ENV" SINGLE)"   == "in single quotes" ]] || bad "SINGLE kept its quotes"
[[ "$(env_value "$ENV" EXPORTED)" == "exported" ]]         || bad "an exported assignment"
[[ "$(env_value "$ENV" EMPTY)"    == "" ]]                 || bad "EMPTY"
[[ "$(env_value "$ENV" ABSENT)"   == "" ]]                 || bad "a name that is not in the file"
[[ "$(env_value "$WORK/nope" PLAIN)" == "" ]]              || bad "a file that is not there"
ok "plain, quoted, exported, empty, absent"

case_begin "reading the file never runs it"
# env_value greps instead of sourcing, so a value is never parsed or executed as shell.
env_value "$ENV" DANGEROUS >/dev/null
env_value "$ENV" BRACKETS  >/dev/null
[[ -e "$WORK/executed" ]] && bad "a value containing a command substitution was executed"
[[ "$(env_value "$ENV" BRACKETS)" == 'a(b)c' ]] || bad "a value with brackets did not survive"
ok "a command substitution is text, and brackets are not syntax"

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
# The report names variables, never their secret values.
grep -q 'in double quotes' <<<"$missing" && bad "a value appeared in the report"
grep -q 'REPLACE_ME'       <<<"$missing" && bad "a value appeared in the report"
ok "only names came back"

case_begin "a REPLACE_ME inside a value is found, one in a comment is not"
lines="$(env_replace_me_lines "$ENV")"
[[ -n "$lines" ]] || bad "the REPLACE_ME inside the language table was not found"
comment_line="$(grep -n 'this line is the documentation' "$ENV" | cut -d: -f1)"
grep -qx "$comment_line" <<<"$lines" && bad "the explanatory comment was reported as a leftover"
ok "the value is found, the documentation is not"

case_begin "the steward profile has to be selected"
# Without the steward profile, caddy and steward-agent never start.
profiles_include "db,bot,mc,steward" steward       || bad "the real selection was refused"
profiles_include "steward" steward                  || bad "steward on its own was refused"
profiles_include "db, steward ,mc" steward          || bad "spaces around the name broke it"
for wrong in "" "db,bot,mc" "stewards" "steward-agent" "db,bot,steward2"; do
    if profiles_include "$wrong" steward; then
        bad "'$wrong' was accepted as selecting the steward profile"
    fi
done
ok "the real selection passes; four near misses and an empty one do not"

case_begin "the environment file's path has to be absolute"
is_absolute "/etc/nordtal/season-2.env" || bad "an absolute path was refused"
for wrong in "" "." "./.env" "env/.env" "~/.env"; do
    if is_absolute "$wrong"; then
        bad "'$wrong' was accepted as absolute"
    fi
done
ok "a relative path, a bare dot, a tilde and an empty string are all refused"

case_begin "the name has to point at THIS host, entirely"
ours=$'45.155.173.214\n2a01:4f8::1'

[[ -z "$(addresses_not_ours "45.155.173.214" "$ours")" ]] \
    || bad "the host's own address was reported as a stray"
[[ -z "$(addresses_not_ours $'45.155.173.214\n2a01:4f8::1' "$ours")" ]] \
    || bad "both of the host's addresses were not accepted"

# A stale AAAA record fails the certificate even when IPv4 is right.
strays="$(addresses_not_ours $'45.155.173.214\n2a01:dead::1' "$ours")"
[[ "$strays" == "2a01:dead::1" ]] || bad "a stale AAAA beside a correct A was not reported: $strays"

[[ "$(addresses_not_ours "203.0.113.9" "$ours")" == "203.0.113.9" ]] \
    || bad "an address belonging to another host was not reported"
ok "every resolved address has to be ours, not just one of them"

case_begin "a name that resolves to nothing does not read as a match"
# A name that resolves to nothing is wrong, not an empty list of wrong addresses.
for nothing in "" " " $'\n'; do
    if [[ -z "$(addresses_not_ours "$nothing" "$ours")" ]]; then
        bad "an empty resolution was accepted as pointing here"
    fi
done
ok "no answer is reported, not passed over"

# `up` pulls every image, which replaces a locally built one under the same tag.
# at_risk_images compares the local digest with what the registry serves, since the containerd store
# gives a local build a RepoDigest too.
case_begin "no local RepoDigests at all is RISK, whatever the registry says"
pairs=$'nordtal/discord-bot:redtest-107\tdiscord-bot'
local_digests=$'nordtal/discord-bot:redtest-107\t[]'
registry_digests=$'nordtal/discord-bot:redtest-107\tsha256:aaaaaaaaaaaa'
result="$(at_risk_images "$pairs" "$local_digests" "$registry_digests")"
[[ "$result" == $'RISK\tnordtal/discord-bot:redtest-107\tdiscord-bot' ]] \
    || bad "expected a RISK line for the image with no local digest, got: «$result»"
ok "an image with no RepoDigests is RISK even when the registry answered"

case_begin "local and registry digest agree - silent"
pairs=$'ghcr.io/nordtal/minecraft:0.10.3\tsmp'
local_digests=$'ghcr.io/nordtal/minecraft:0.10.3\t["ghcr.io/nordtal/minecraft@sha256:same0000"]'
registry_digests=$'ghcr.io/nordtal/minecraft:0.10.3\tsha256:same0000'
[[ -z "$(at_risk_images "$pairs" "$local_digests" "$registry_digests")" ]] \
    || bad "a matching local and registry digest was still reported"
ok "an image whose local digest matches what the registry currently serves is silent"

case_begin "local and registry digest disagree - RISK (the containerd-store case)"
# The containerd store: a local build has a RepoDigest that differs from the registry's.
pairs=$'ghcr.io/nordtal/discord-bot:0.10.3\tdiscord-bot'
local_digests=$'ghcr.io/nordtal/discord-bot:0.10.3\t["ghcr.io/nordtal/discord-bot@sha256:a429f360e0c8"]'
registry_digests=$'ghcr.io/nordtal/discord-bot:0.10.3\tsha256:39d016200b67'
result="$(at_risk_images "$pairs" "$local_digests" "$registry_digests")"
[[ "$result" == $'RISK\tghcr.io/nordtal/discord-bot:0.10.3\tdiscord-bot' ]] \
    || bad "a locally built image with a mismatched registry digest was not reported, got: «$result»"
ok "a locally built image is RISK when the registry's current digest differs from its own"

case_begin "the registry did not answer - UNKNOWN, neither cleared nor flagged"
# "Could not compare" is reported apart from "safe".
pairs=$'ghcr.io/nordtal/steward:0.10.3\tsteward'
local_digests=$'ghcr.io/nordtal/steward:0.10.3\t["ghcr.io/nordtal/steward@sha256:ea9364be13a9"]'
result="$(at_risk_images "$pairs" "$local_digests" "")"
[[ "$result" == $'UNKNOWN\tghcr.io/nordtal/steward:0.10.3\tsteward' ]] \
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

case_begin "a generated secret lands in the file whatever the assignment looks like"
# set_secret finds and replaces a name the same way, with leading whitespace or `export`.
for form in "STEWARD_AGENT_TOKEN=" "  STEWARD_AGENT_TOKEN=" "export STEWARD_AGENT_TOKEN=" \
            "STEWARD_AGENT_TOKEN = " "  export  STEWARD_AGENT_TOKEN="; do
    secrets="$WORK/secret.env"
    printf 'BEFORE=1\n%s\nAFTER=2\n' "$form" > "$secrets"

    set_assignment "$secrets" STEWARD_AGENT_TOKEN "$(printf '%064d' 7 | tr '0-9' 'a-f0-3')"

    written="$(env_value "$secrets" STEWARD_AGENT_TOKEN)"
    [[ "$written" =~ ^[0-9a-f]{64}$ ]] \
        || bad "«$form» left the token as «$written» and said it had generated one"
    count="$(grep -cE '^[[:space:]]*(export[[:space:]]+)?STEWARD_AGENT_TOKEN[[:space:]]*=' "$secrets")"
    [[ "$count" == "1" ]] || bad "«$form» left $count assignments of the name in the file"
    # No other line changed.
    [[ "$(env_value "$secrets" BEFORE)" == "1" && "$(env_value "$secrets" AFTER)" == "2" ]] \
        || bad "«$form» disturbed the lines around it"
done
ok "every spelling of an empty assignment is replaced, once, in place"

case_begin "a name that is not in the file is appended, not lost"
secrets="$WORK/secret.env"
printf 'BEFORE=1\n' > "$secrets"
set_assignment "$secrets" STEWARD_AGENT_TOKEN "abc"
[[ "$(env_value "$secrets" STEWARD_AGENT_TOKEN)" == "abc" ]] || bad "the new name was not written"
[[ "$(env_value "$secrets" BEFORE)" == "1" ]] || bad "the file it was appended to was disturbed"
ok "an absent name is appended once"

case_begin "an answer whose shape cannot be right is refused at the prompt"
# A host name with a scheme is refused at the prompt, where it can still be fixed.
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

# The transfer packet needs the port, which people tend to leave out.
for address in play.nordtal.eu:25565 nordtal.eu:25566 45.155.173.214:25565; do
    looks_like_public_address "$address" || bad "the address $address was refused"
done
for wrong in "" "play.nordtal.eu" "play.nordtal.eu:" ":25565" "play.nordtal.eu:0" \
             "play.nordtal.eu:70000" "play.nordtal.eu:25565x" "https://play.nordtal.eu:25565" \
             "play:25565"; do
    if looks_like_public_address "$wrong"; then bad "«$wrong» was accepted as a public address"; fi
done
ok "host:port passes; a bare host, a bare port, a scheme and an impossible port do not"

case_begin "the licence question takes yes for an answer and nothing else for one"
# The EULA answer defaults to no.
for yes in y Y yes YES Yes true; do
    answer_is_yes "$yes" || bad "«$yes» was not read as yes"
done
# Only English spellings are accepted.
for no in "" n N no nope maybe "y e s" 1 0 accept j ja; do
    if answer_is_yes "$no"; then bad "«$no» was read as yes"; fi
done
ok "six spellings of yes; everything else, silence and German included, is no"

case_begin "the Mojang question is asked until true or false is written down"
MOJANG="$WORK/mojang.env"
: > "$MOJANG"
if mojang_assets_answered "$MOJANG"; then bad "an installation without the variable counted as answered"; fi
for unanswered in "" "maybe" "yes"; do
    printf 'NORDTAL_MOJANG_ASSETS=%s\n' "$unanswered" > "$MOJANG"
    if mojang_assets_answered "$MOJANG"; then bad "«$unanswered» counted as an answer"; fi
done
for answered in true false; do
    printf 'NORDTAL_MOJANG_ASSETS=%s\n' "$answered" > "$MOJANG"
    mojang_assets_answered "$MOJANG" || bad "«$answered» was asked again"
done
ok "absent, empty and anything but true or false ask; true and false do not"

case_begin "a written secret is never on a command line"
# The value reaches awk through the environment, never the world-readable command line.
grep -q 'awk -v name=.*-v value=' "$SETUP" && bad "a value is still passed to awk with -v"
grep -q 'ENVIRON\["SET_ASSIGNMENT_VALUE"\]' "$SETUP" || bad "the value does not come from the environment"
ok "set_assignment hands the value to awk through the environment"

case_begin "what a deployment demands of a person is the short list"
# bunq stays optional; without it no payments are taken.
for name in COMPOSE_PROFILES POSTGRES_PASSWORD VELOCITY_FORWARDING_SECRET EULA NORDTAL_BOT_TOKEN \
            NORDTAL_ACCESS_GUILD_ID STEWARD_HOST STEWARD_ACME_EMAIL \
            STEWARD_ENV_FILE STEWARD_ENV_DIR STEWARD_ENV_FILE_NAME STEWARD_DISCORD_CLIENT_ID \
            STEWARD_DISCORD_CLIENT_SECRET; do
    contains "$name" "${REQUIRED[@]}" || bad "$name is not required and should be"
done
for name in NORDTAL_STEWARD_BUNQ_API_KEY NORDTAL_STEWARD_BUNQ_ACCOUNT_ID STEWARD_ROOT_DISCORD_ID; do
    if contains "$name" "${REQUIRED[@]}"; then
        bad "$name is required, and a deployment must not stop for it"
    fi
done
contains NORDTAL_DIR "${REQUIRED[@]}" || bad "NORDTAL_DIR is not required and should be"
ok "fourteen required; bunq is not"

case_begin "a value full of shell metacharacters survives the round trip"
# A value is written literally, never as shell.
secrets="$WORK/secret.env"
printf 'NAME=old\n' > "$secrets"
set_assignment "$secrets" NAME 'a(b)c$(touch "'"$WORK"'/executed")'
[[ -e "$WORK/executed" ]] && bad "writing a value executed it"
[[ "$(env_value "$secrets" NAME)" == 'a(b)c$(touch "'"$WORK"'/executed")' ]] \
    || bad "a value with brackets and a substitution did not come back unchanged"
ok "a value is text on the way in and text on the way out"

case_begin "a secret is three dots, whatever is behind them"
# A value is masked by its kind, so a new secret is masked too.
[[ "$(shown_value secret "hunter2")"          == "•••" ]] || bad "a secret was printed"
[[ "$(shown_value optional-secret "a-key")"   == "•••" ]] || bad "an optional secret was printed"
[[ "$(shown_value secret "")"                 == "(not set)" ]] || bad "an unset secret"
[[ "$(shown_value secret "   ")"              == "(not set)" ]] || bad "a blank secret"
[[ "$(shown_value plain "steward.nordtal.eu")" == "steward.nordtal.eu" ]] || bad "a host name"
[[ "$(shown_value licence "true")"            == "true" ]] || bad "the licence"
ok "secret and optional-secret are dots; everything else reads back as itself"

case_begin "every secret in the menu is masked by kind, and no kind is missing one"
# Every question has a kind, or `shown_value` would echo it as not a secret.
for name in "${QUESTIONS[@]}"; do
    [[ -n "${QUESTION_KIND[$name]:-}" ]]   || bad "$name has no kind"
    [[ -n "${QUESTION_CHECK[$name]:-}" ]]  || bad "$name has no check"
    [[ -n "${QUESTION_PROMPT[$name]:-}" ]] || bad "$name has no prompt"
    [[ -n "${QUESTION_HINT[$name]:-}" ]]   || bad "$name has no hint"
done
for name in NORDTAL_BOT_TOKEN STEWARD_DISCORD_CLIENT_SECRET NORDTAL_STEWARD_BUNQ_API_KEY; do
    case "${QUESTION_KIND[$name]}" in
        secret|optional-secret) ;;
        *) bad "$name is not a secret kind, so the menu would print it" ;;
    esac
done
ok "eleven questions, all four columns each, and the three secrets are secret kinds"

case_begin "a bare Return in the menu deploys nothing"
# An empty answer never confirms stopping the servers.
[[ "$(menu_choice "" 11)"    == "" ]]        || bad "an empty answer was taken for something"
[[ "$(menu_choice " " 11)"   == "" ]]        || bad "a space was taken for something"
[[ "$(menu_choice "d" 11)"   == "deploy" ]]  || bad "d"
[[ "$(menu_choice "D" 11)"   == "deploy" ]]  || bad "D"
[[ "$(menu_choice "q" 11)"   == "quit" ]]    || bad "q"
[[ "$(menu_choice "1" 11)"   == "edit 1" ]]  || bad "1"
[[ "$(menu_choice "11" 11)"  == "edit 11" ]] || bad "the last entry"
[[ "$(menu_choice "0" 11)"   == "" ]]        || bad "0 is not an entry"
[[ "$(menu_choice "12" 11)"  == "" ]]        || bad "one past the end"
[[ "$(menu_choice "08" 11)"  == "edit 8" ]]  || bad "a leading zero is not octal"
[[ "$(menu_choice "1x" 11)"  == "" ]]        || bad "1x"
[[ "$(menu_choice "yes" 11)" == "" ]]        || bad "yes is not one of the answers"
ok "deploy, quit and a number in range; everything else redraws"

case_begin "a profile selection is a list of names and not a path"
looks_like_profiles "db,bot,mc,steward"        || bad "the production selection"
looks_like_profiles "bot"                      || bad "one profile"
looks_like_profiles "db, bot"                  || bad "a space after the comma"
looks_like_profiles "/etc/nordtal"             && bad "a path was accepted"
looks_like_profiles "db,,bot"                  && bad "an empty profile was accepted"
looks_like_profiles ""                         && bad "nothing was accepted"
ok "names and commas; a path, an empty element and nothing are refused"

case_begin "a name compose.yml selects nothing by is reported"
[[ -z "$(unknown_profiles "db,bot,mc,steward")" ]]    || bad "the production selection has an unknown name"
[[ -z "$(unknown_profiles "db, mc ,devpack")" ]]      || bad "spaces around a known name made it unknown"
[[ "$(unknown_profiles "db,backup,steward")" == "backup" ]] || bad "backup is no profile"
[[ "$(unknown_profiles "standby,mc")" == "standby" ]] || bad "standby is never selected"
ok "every name but the selectable profiles is reported, one per line"

case_begin "the selectable profiles are compose.yml's"
# Every profile compose.yml declares, standby included, which nobody selects.
declared="$(grep -o 'profiles: \[[^]]*\]' "$HERE/../compose.yml" | grep -o '"[a-z0-9-]*"' | tr -d '"' | sort -u)"
listed="$(printf '%s\n' "${SELECTABLE_PROFILES[@]}" standby | sort -u)"
[[ -n "$declared" ]] || bad "no profile found in compose.yml"
[[ "$declared" == "$listed" ]] || bad "compose.yml declares $(tr '\n' ' ' <<<"$declared")but nordtal.sh knows $(tr '\n' ' ' <<<"$listed")"
ok "SELECTABLE_PROFILES and standby are exactly compose.yml's profiles"

case_begin "a downloaded file has to be this script before it replaces this script"
# The renewal only runs a download that is complete and is the script.
printf '#!/usr/bin/env bash\nSELF_NAME="nordtal.sh"\necho hello\n' > "$WORK/good.sh"
looks_like_this_script "$WORK/good.sh" || bad "a real script was refused"
printf '<html><body>404</body></html>\n' > "$WORK/page.html"
looks_like_this_script "$WORK/page.html" && bad "an error page was accepted"
printf '#!/usr/bin/env bash\necho hello\n' > "$WORK/other.sh"
looks_like_this_script "$WORK/other.sh" && bad "some other bash script was accepted"
printf '#!/usr/bin/env bash\nSELF_NAME="nordtal.sh"\nif true; then\n' > "$WORK/half.sh"
looks_like_this_script "$WORK/half.sh" && bad "a truncated script was accepted"
: > "$WORK/empty.sh"
looks_like_this_script "$WORK/empty.sh" && bad "an empty file was accepted"
ok "the shebang, the marker and a syntax check"

case_begin "update: the flags, and what they refuse"
# An unknown flag or a value the database constraints would refuse stops the command.

# `die` exits, so every refusal is checked in a subshell.
refuses() {
    local why="$1"; shift
    if ( parse_update_args "$@" ) >/dev/null 2>&1; then
        bad "$why: parse_update_args $* was accepted"
    fi
}

parse_update_args
[[ "$UPDATE_KIND" == UPDATE ]] || bad "a bare \`update\` is not an UPDATE run but $UPDATE_KIND"
[[ -z "$UPDATE_SCOPE" ]]       || bad "a bare \`update\` carried a scope: $UPDATE_SCOPE"
[[ "$UPDATE_DELAY" == 0 ]]     || bad "a bare \`update\` waits $UPDATE_DELAY minutes"
[[ "$UPDATE_WAIT" == true ]]   || bad "a bare \`update\` did not wait"
ok "no flags is the whole network, now, and the command waits for it"

parse_update_args --restart
[[ "$UPDATE_KIND" == RESTART ]] || bad "--restart gave $UPDATE_KIND"
parse_update_args --backup
[[ "$UPDATE_KIND" == BACKUP ]]  || bad "--backup gave $UPDATE_KIND"
parse_update_args --down smp
[[ "$UPDATE_KIND" == DOWN && "$UPDATE_SCOPE" == smp ]] || bad "--down smp gave $UPDATE_KIND/$UPDATE_SCOPE"
parse_update_args --start
[[ "$UPDATE_KIND" == START && -z "$UPDATE_SCOPE" ]] || bad "a bare --start carried $UPDATE_SCOPE"
parse_update_args --start limbo
[[ "$UPDATE_KIND" == START && "$UPDATE_SCOPE" == limbo ]] || bad "--start limbo gave $UPDATE_SCOPE"
ok "every kind this command offers is one steward_inbox.kind accepts"

# A bare --start means "release every hold", so the flag after it must not be eaten as a service.
parse_update_args --start --no-wait
[[ "$UPDATE_KIND" == START && -z "$UPDATE_SCOPE" && "$UPDATE_WAIT" == false ]] \
    || bad "--start swallowed the flag after it: scope='$UPDATE_SCOPE' wait=$UPDATE_WAIT"
ok "a flag after --start is a flag and not a service name"

refuses "a second kind" --restart --backup
refuses "--down with nothing" --down
refuses "a path as a service" --down /etc/passwd
refuses "an upper-case service" --down SMP
refuses "a service with a space" --down "smp limbo"
refuses "a trailing comma" --down "smp,"
refuses "minutes that are not a number" --in soon
refuses "negative minutes" --in -5
refuses "more than a day" --in 2000
refuses "a typo" --restartt
refuses "a bare word" smp
refuses "replacing local builds on a restart" --restart --replace-local
ok "two kinds, a missing service, a shape the database would reject and a typo are all refused"

parse_update_args --in 10 --no-wait --timeout 60
[[ "$UPDATE_DELAY" == 10 && "$UPDATE_WAIT" == false && "$UPDATE_TIMEOUT" == 60 ]] \
    || bad "--in/--no-wait/--timeout did not all land"
ok "the countdown, the timeout and not waiting at all"

case_begin "update: the request is the agent's, never SQL of this script's"
mapfile -t words < <(update_request_words UPDATE "" 0)
[[ "${words[*]:0:2}" == "steward-agent request" ]] || bad "the request is not steward-agent's: ${words[*]}"
[[ "${#words[@]}" == 5 && -z "${words[3]}" ]] \
    || bad "an empty scope has to stay one empty word, or the minutes would be read as services"
[[ "${words[2]}" == UPDATE && "${words[4]}" == 0 ]] || bad "the kind or the delay did not land: ${words[*]}"
ok "the agent writes the row, so the open run stays the one lock"

mapfile -t words < <(update_request_words DOWN smp,limbo 15)
[[ "${words[3]}" == smp,limbo && "${words[4]}" == 15 ]] || bad "a scoped, delayed run lost one: ${words[*]}"
ok "a scoped, delayed run carries both"

parse_update_args --replace-local
[[ "$UPDATE_KIND" == UPDATE && "$UPDATE_REPLACE_LOCAL" == true ]] || bad "--replace-local did not land"
mapfile -t words < <(update_request_words UPDATE "" 0 true)
[[ "${#words[@]}" == 6 && "${words[5]}" == --replace-local ]] \
    || bad "the confirmation is not the last word of the request: ${words[*]}"
mapfile -t words < <(update_request_words UPDATE "" 0 false)
[[ "${#words[@]}" == 5 ]] || bad "an update that was not confirmed carries a sixth word: ${words[*]}"
ok "an update confirmed to replace local builds says so to the agent, and only then"
grep -q 'INSERT INTO steward_inbox' "$SETUP" && bad "nordtal.sh still writes the inbox itself"
ok "no statement of its own is left in the script"

case_begin "update: which statuses end the wait"
for over in DONE FAILED CANCELLED; do
    update_is_over "$over" || bad "$over did not end the wait"
done
for running in PENDING RUNNING "" unknown; do
    update_is_over "$running" && bad "'$running' ended the wait"
done
ok "the three finished statuses end it and nothing else does"

# The scope shape, held against the constraint the database carries.
for good in smp limbo smp,limbo hunger-games steward; do
    update_scope_ok "$good" || bad "$good was refused"
done
for wrong in "" " " SMP "smp," ",smp" "smp,,limbo" "smp limbo" "../smp" "smp;"; do
    update_scope_ok "$wrong" && bad "'$wrong' was accepted as a scope"
done
ok "the scope check is steward_inbox_services_check's service name, joined by commas"


case_begin "the release is the newest tag, without its v, and the script comes from that tag"
[[ "$(release_of_tag v0.11.0)" == "0.11.0" ]] || bad "a tag kept its v"
[[ "$(release_of_tag 0.11.0)" == "0.11.0" ]]  || bad "a bare version changed"
answer='{"url":"x","tag_name": "v0.11.0","name":"v0.11.0","target_commitish":"main"}'
[[ "$(tag_name_of <<<"$answer")" == "v0.11.0" ]] || bad "tag_name was not read"
[[ "$(tag_name_of <<<'{"message":"Not Found"}')" == "" ]] || bad "an answer without a tag gave one"
[[ "$(self_url_for 0.11.0)" == "https://raw.githubusercontent.com/nordtal/season-2/v0.11.0/deploy/nordtal.sh" ]] \
    || bad "the script is not fetched from the release's tag"
[[ "$(self_url_for 0.11.0)" != */main/* ]] || bad "the script is fetched from main"
ok "tag_name, v stripped, raw file at the tag"

case_begin "a running, healthy agent gets a request; everything else gets up"
[[ "$(deploy_by false true)" == request ]] || bad "a healthy agent was bypassed"
[[ "$(deploy_by false false)" == up ]]     || bad "an install or a repair asked an agent that is not there"
[[ "$(deploy_by true true)" == up ]]       || bad "--build did not deploy the agent it built"
ok "request, up, --build"

case_begin "update: the copy that asks for a run is renewed to the newest release's first"
# A host that is only ever updated runs the release's script, not the one it was installed with.
printf '{"tag_name": "v9.9.9"}\n' > "$WORK/latest.json"
{ cat "$SETUP"; printf '# release 9.9.9\n'; } > "$WORK/release.sh"
cp "$SETUP" "$WORK/installed.sh"
renewed() { RELEASES_API="$1" NORDTAL_SH_URL="$2" renew_file "$WORK/installed.sh" >/dev/null 2>&1; }
renewed "file://$WORK/latest.json" "file://$WORK/release.sh" || bad "an older copy was not replaced"
cmp -s "$WORK/installed.sh" "$WORK/release.sh" || bad "the file is not the release's copy"
[[ -x "$WORK/installed.sh" ]] || bad "the renewed file cannot be run"
renewed "file://$WORK/latest.json" "file://$WORK/release.sh" && bad "the release's own copy was replaced again"
ok "an older copy becomes the release's, and the release's copy stays"

cp "$SETUP" "$WORK/installed.sh"
renewed "file://$WORK/nothing.json" "file://$WORK/release.sh" && bad "a copy was replaced without a release"
renewed "file://$WORK/latest.json" "file://$WORK/page.html" && bad "an error page replaced the script"
cmp -s "$WORK/installed.sh" "$SETUP" || bad "a failed renewal changed the file"
ok "without GitHub or with a broken download, the copy that is here runs"

case_begin "update: the watch rides out steward-agent being recreated and gives up when it stays gone"
# The stand-in docker answers the nth look with the nth line of answers; a dash is an exec that fails.
looks_at() { cat "$WORK/looks"; }
docker() {
    local looked
    looked=$(( $(cat "$WORK/looks") + 1 ))
    printf '%s\n' "$looked" > "$WORK/looks"
    local line
    line="$(sed -n "${looked}p" "$WORK/answers")"
    [[ "$line" == - ]] && return 1
    printf '%s\n' "$line"
}
sleep() { :; }
watched() { printf '0\n' > "$WORK/looks"; printf '%b' "$1" > "$WORK/answers"; update_wait 62 steward-agent >/dev/null 2>&1; }
UPDATE_TIMEOUT=1800 UPDATE_POLL=10 UPDATE_GAP=30

( watched 'RUNNING\t\n-\n-\n-\nDONE\t\n' ) || bad "a gap of three looks ended the watch"
[[ "$(looks_at)" == 5 ]] || bad "the watch stopped at look $(looks_at), not after DONE at look 5"
ok "three silent looks in the middle of a run do not end the watch"

( watched 'RUNNING\t\n-\n-\n-\n-\nDONE\t\n' ) && bad "a gap longer than the limit was ridden out"
[[ "$(looks_at)" == 5 ]] || bad "the watch gave up at look $(looks_at), not at the fifth"
ok "a gap past the limit ends the watch"

( watched '-\n' ) && bad "a request that is not there was waited for"
( watched 'RUNNING\t\n-\n-\n-\nRUNNING\t\n-\n-\n-\nFAILED\t\n' ) && bad "a failed run ended in success"
[[ "$(looks_at)" == 9 ]] || bad "silent looks were counted across answers, look $(looks_at)"
ok "an answer resets the gap, and a failed run still fails"
unset -f docker sleep

case_begin "the registry prefix is the environment's, else the file's, else ghcr.io"
printf 'NORDTAL_IMAGES=registry.example/fork\n' > "$WORK/images.env"
printf 'PLAIN=value\n' > "$WORK/no-images.env"
printf 'NORDTAL_IMAGES="registry.example/quoted"\n' > "$WORK/quoted-images.env"
[[ "$(unset NORDTAL_IMAGES; images_prefix "$WORK/images.env")" == registry.example/fork ]] || bad "the file's prefix was ignored"
[[ "$(unset NORDTAL_IMAGES; images_prefix "$WORK/quoted-images.env")" == registry.example/quoted ]] || bad "the file's quoted prefix"
[[ "$(NORDTAL_IMAGES=one.off/prefix images_prefix "$WORK/images.env")" == one.off/prefix ]] || bad "a one-off in the environment did not win"
[[ "$(NORDTAL_IMAGES= images_prefix "$WORK/images.env")" == registry.example/fork ]] || bad "an empty environment value hid the file's"
[[ "$(unset NORDTAL_IMAGES; images_prefix "$WORK/no-images.env")" == ghcr.io/nordtal ]] || bad "no value anywhere is not ghcr.io"
[[ "$(unset NORDTAL_IMAGES; images_prefix "$WORK/nope.env")" == ghcr.io/nordtal ]] || bad "a missing file is not ghcr.io"
ok "environment, file, default, in that order"


if (( failed > 0 )); then
    printf '\n%d case(s) failed\n' "$failed" >&2
    exit 1
fi
echo "all cases passed"
