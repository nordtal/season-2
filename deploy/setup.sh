#!/usr/bin/env bash
#
# The season 2 stack, brought up on a host from nothing but this checkout and a Docker daemon.
#
# It is the one thing in this deployment that lives OUTSIDE the deployment. Everything else is a
# container that steward-deployer can recreate; steward-deployer itself cannot, because a service
# that replaces its own container never gets to report how that went. So renewing it is this
# script's job (§9c) - and since it has to exist and be re-runnable anyway, it is also what puts the
# environment file in place and what refuses to continue while the certificate cannot be issued.
#
#   deploy/setup.sh                    ask for what is missing, then deploy the whole stack
#   deploy/setup.sh --build            build the deployer from this checkout first (needs a JDK)
#   deploy/setup.sh --check            every check, and stop before anything is changed
#   deploy/setup.sh --from PATH        take the answers from this file instead of asking
#   deploy/setup.sh --env-file PATH    where the environment file belongs on this host
#                                      (default: /etc/nordtal/season-2.env)
#   deploy/setup.sh --address IP       this host's public address, for a host behind NAT
#
# NOBODY EDITS A .env. This script asks for the nine things only a person can know - the name the
# interface answers on, the address Let's Encrypt writes to, the EULA, the bot's token, the two
# halves of the Discord application, the guild, the admin role, and bunq if there is a bunq - and it
# writes them itself, into a file it creates with mode 600. Everything else is either generated here
# (the database password, the proxy's forwarding secret, the two Steward tokens) or has a default in
# the service's own configuration, which the interface can then edit. Running it again asks only for
# what is still missing, so it is safe to run twice.
#
# RUN IT AGAIN AFTER EVERY RELEASE. That is not a nicety: a new compose.yml reaches this host only
# inside a new steward-deployer image, so "deploy the new version" is this script, and everything
# else in the stack then follows from the deployer.
#
# IT NEVER PRINTS A SECRET AND NEVER PUTS ONE ON A COMMAND LINE. A secret is read with the terminal
# echo off, handed to `awk` through the environment rather than through `-v` (an argument is visible
# in `ps` to every user on the host), and written into a file created 600 beside its destination.
# The report says which variable is missing, never what is in it.
#
# Everything here runs from the repository root whatever directory it is called from.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DEFAULT_ENV_FILE="/etc/nordtal/season-2.env"
DEPLOYER_IMAGE="ghcr.io/nordtal/steward-deployer:latest"
DEFAULT_PROJECT="nordtal-s2"

# How long between two attempts at the name, and how often to say so out loud while nothing changes.
DNS_INTERVAL=15
DNS_HEARTBEAT=8

log()  { printf '\033[36m[setup]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[setup]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m[setup]\033[0m %s\n' "$*" >&2; exit 1; }

# What has to be in the environment file before the stack can start.
#
# Every one of them is either asked for below or generated here, so this list is a last check rather
# than a demand on the reader: if it ever fires, something in this script failed to write what it
# said it wrote. It is checked by NAME - absent, empty or still REPLACE_ME all count as missing, and
# the report names the variable and never the value.
#
# WHAT IS DELIBERATELY NOT HERE is as much of the point as what is: the roles the bot hands out, the
# channels it posts in, the languages, the tiers. None of those stops a deployment - a feature whose
# channel is unset is simply not served, and the interface can set it afterwards. The old list
# demanded all six, which is how a host ended up needing a hand-written .env before it could start.
REQUIRED=(
    COMPOSE_PROFILES
    POSTGRES_DB
    POSTGRES_USER
    POSTGRES_PASSWORD
    VELOCITY_FORWARDING_SECRET
    EULA
    NORDTAL_BOT_TOKEN
    NORDTAL_ACCESS_GUILD_ID
    NORDTAL_ACCESS_ROLES_ADMIN
    STEWARD_HOST
    STEWARD_ACME_EMAIL
    STEWARD_ENV_FILE
    STEWARD_ENV_DIR
    STEWARD_ENV_FILE_NAME
    STEWARD_UI_DISCORD_CLIENT_ID
    STEWARD_UI_DISCORD_CLIENT_SECRET
)

# --- decisions, kept apart so they can be tested ---------------------------------------------------
# Everything in this block is a question with an answer and no side effect, which is what lets
# deploy/setup-test.sh drive it without a Docker daemon, without a network and without an
# environment file that has anything real in it. The same arrangement as deploy/dev and
# deploy/minecraft/entrypoint.sh, and for the same reason: the parts that decide are the parts worth
# pinning, and two of these decide whether a host gets a certificate or waits forever.

# One value out of an environment file, without sourcing it. Sourcing would execute it, and an
# environment file is not a script - `POSTGRES_PASSWORD=a(b` is a perfectly good password and a
# syntax error. Only the first line of an assignment is read, which is all any caller here needs.
env_value() {
    local file="$1" name="$2" line
    [[ -f "$file" ]] || return 0
    line="$(grep -m1 -E "^[[:space:]]*(export[[:space:]]+)?${name}=" "$file" || true)"
    line="${line#*=}"
    # One layer of quotes, the way compose reads them.
    if [[ "$line" == \"*\" && ${#line} -ge 2 ]]; then
        line="${line:1:${#line}-2}"
    elif [[ "$line" == \'*\' && ${#line} -ge 2 ]]; then
        line="${line:1:${#line}-2}"
    fi
    printf '%s' "$line"
}

# Writes `name=value` into the file, replacing the assignment that is there or appending one.
#
# THE SPELLING OF AN ASSIGNMENT IS NOT ONE THING, and this used to look for it one way and replace
# it another: the search accepted leading whitespace and the replacement compared the whole first
# field, so `  NAME=` was found and left alone; and neither of them knew about `export`, so
# `export NAME=` was not found at all and a SECOND assignment was appended below the first - which
# `env_value` then never reads, because it takes the first match. Both ways the caller was told a
# value had been written and compose got an empty one.
#
# THE VALUE IS NEVER PRINTED AND NEVER PUT ON A COMMAND LINE. It used to go into awk through `-v`,
# with a comment here claiming that kept it out of `ps` - it does not: an argument of a running
# process is in /proc/<pid>/cmdline, which is world-readable, and this function now writes the
# Discord token and the database password. So the value travels in the ENVIRONMENT of that one awk
# (readable by root and the caller, not by everybody) and into a file created with mode 600 beside
# the destination, never through a world-readable place, not even for the moment before the rename.
set_assignment() {
    local file="$1" name="$2" value="$3" tmp
    tmp="$(mktemp "$(dirname "$file")/.env.XXXXXX")"
    chmod 600 "$tmp"
    if grep -qE "^[[:space:]]*(export[[:space:]]+)?${name}[[:space:]]*=" "$file"; then
        SET_ASSIGNMENT_NAME="$name" SET_ASSIGNMENT_VALUE="$value" awk '
            BEGIN { name = ENVIRON["SET_ASSIGNMENT_NAME"]; value = ENVIRON["SET_ASSIGNMENT_VALUE"] }
            {
                key = $0
                sub(/=.*$/, "", key)
                gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
                sub(/^export[[:space:]]+/, "", key)
                gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
                if (key == name) { print name "=" value; next }
                print
            }' "$file" > "$tmp"
    else
        cat "$file" > "$tmp"
        printf '%s=%s\n' "$name" "$value" >> "$tmp"
    fi
    mv "$tmp" "$file"
}

# Which of the given names have no usable value. Prints names, one per line, and never a value.
env_missing() {
    local file="$1" name value
    shift
    for name in "$@"; do
        value="$(env_value "$file" "$name")"
        if [[ -z "${value//[[:space:]]/}" || "$value" == *REPLACE_ME* ]]; then
            printf '%s\n' "$name"
        fi
    done
}

# The line numbers of every REPLACE_ME left in the file, comments excluded. The numbers are printed
# and the lines are not: .env.example's own header explains REPLACE_ME, so a bare grep would report
# the documentation, and the values beside a real one are secrets.
env_replace_me_lines() {
    local file="$1"
    [[ -f "$file" ]] || return 0
    grep -n 'REPLACE_ME' "$file" | grep -v '^[0-9]*:[[:space:]]*#' | cut -d: -f1 || true
}

# COMPOSE_PROFILES has to include `steward`, or caddy, steward-ui and steward-deployer are defined
# and never started - and the stack then comes up looking healthy with no interface on it at all.
profiles_include() {
    local profiles="$1" wanted="$2" profile
    local IFS=','
    for profile in $profiles; do
        [[ "${profile//[[:space:]]/}" == "$wanted" ]] && return 0
    done
    return 1
}

is_absolute() { [[ "$1" == /* ]]; }

# --- what an answer has to look like ---------------------------------------------------------------
# Checked at the prompt, not three steps later. A Discord id pasted with its surrounding angle
# brackets, a host name pasted as an https:// URL and an e-mail address with a stray space in it are
# all things somebody does once; catching them here costs one repeated question, and catching them
# in Caddy's log costs a deployment that waits for a name that will never resolve.
#
# None of these is a validator in the strict sense and none of them tries to be: a value that looks
# right can still be the wrong guild. They refuse the shapes that CANNOT be right.

# A Discord snowflake: digits only, and long enough to be a real one. Discord's ids are 17-19 digits
# today and were shorter in 2015, so the window is deliberately wide at the bottom.
looks_like_snowflake() { [[ "$1" =~ ^[0-9]{15,21}$ ]]; }

# A host name, not a URL: letters, digits, hyphens and at least one dot. `https://x.y` and `x.y/path`
# are the two ways this is pasted wrong, and both would be written into compose as the name Caddy
# asks Let's Encrypt for.
looks_like_host() {
    [[ "$1" =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$ ]]
}

# An e-mail address, to the extent that anything can be. One @, something either side, a dot in the
# domain, no whitespace. Let's Encrypt sends the expiry warnings here and rejects an address it
# cannot parse, which fails the certificate rather than the address.
looks_like_email() { [[ "$1" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; }

# Yes, in the three spellings somebody actually types. Anything else - including silence - is no,
# because the one question asked this way is a licence agreement. English only, so `ja` is not one
# of them: this script speaks the language the rest of Steward speaks, and a German word accepted
# here is a German word in the codebase.
answer_is_yes() {
    case "${1,,}" in
        y|yes|true) return 0 ;;
        *) return 1 ;;
    esac
}

# Which of the addresses the name resolves to are NOT this host's.
#
# EVERY resolved address has to be ours, not merely one of them. A name with an A record here and a
# stale AAAA record somewhere else resolves "correctly" for anyone on IPv4 and hands Let's Encrypt a
# host that answers the challenge with somebody else's server - the certificate then fails for a
# reason that has nothing to do with this host.
addresses_not_ours() {
    local resolved="$1" ours="$2" address
    if [[ -z "${resolved//[[:space:]]/}" ]]; then
        # Failing closed: a name that resolves to nothing must not read as a name that matches.
        printf '(nothing - the name does not resolve)\n'
        return
    fi
    for address in $resolved; do
        grep -qxF "$address" <<<"$ours" || printf '%s\n' "$address"
    done
}

# --- sourced rather than executed ----------------------------------------------------------------
# Everything above this line is definitions; everything below reaches for Docker, the resolver and
# the filesystem. deploy/setup-test.sh sources this file to exercise the decisions above, the same
# way dev-test.sh sources deploy/dev - see the long comment there.
[[ "${BASH_SOURCE[0]}" == "${0}" ]] || return 0

# --- arguments -------------------------------------------------------------------------------------
FROM_FILE=""
ENV_FILE=""
ADDRESSES_GIVEN=""
CHECK_ONLY=false
BUILD_DEPLOYER=false

while (( $# > 0 )); do
    case "$1" in
        --from)      FROM_FILE="${2:?--from needs a path}"; shift 2 ;;
        --env-file)  ENV_FILE="${2:?--env-file needs a path}"; shift 2 ;;
        --address)   ADDRESSES_GIVEN+="${2:?--address needs an address}"$'\n'; shift 2 ;;
        --check)     CHECK_ONLY=true; shift ;;
        --build)     BUILD_DEPLOYER=true; shift ;;
        -h|--help)   sed -n '2,35p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)           die "unknown argument: $1 (try --help)" ;;
    esac
done

cd "$ROOT"

# --- 1 · what has to be on the host already ---------------------------------------------------------
command -v docker >/dev/null 2>&1 \
    || die "no docker on this host. Everything below needs a daemon; nothing here installs one."
docker compose version >/dev/null 2>&1 \
    || die "docker is here but \`docker compose\` is not. The compose plugin is a separate package."
docker info >/dev/null 2>&1 \
    || die "docker is installed and this user cannot talk to the daemon. Run this as root, or as a
       member of the docker group - which is root with extra steps, and is the trade this host has
       already made by running a stack at all."
log "docker $(docker version --format '{{.Server.Version}}'), compose $(docker compose version --short)"

# --- 2 · the environment file ------------------------------------------------------------------------
# One file, one place, owned by this host. It is in no image and in no repository: every secret the
# deployment has is in it, compose interpolates it, and steward-deployer mounts it read-only.
if [[ -z "$ENV_FILE" ]]; then
    ENV_FILE="${STEWARD_ENV_FILE:-$DEFAULT_ENV_FILE}"
fi
is_absolute "$ENV_FILE" || die "--env-file has to be absolute, and '$ENV_FILE' is not. compose
       resolves a relative path against steward-deployer's project directory, which is INSIDE its
       image - so a relative path here points at a file that does not exist."

if [[ ! -f "$ENV_FILE" ]]; then
    if [[ -z "$FROM_FILE" && -f "$ROOT/.env" ]]; then
        FROM_FILE="$ROOT/.env"
    fi
    if [[ -n "$FROM_FILE" ]]; then
        [[ -f "$FROM_FILE" ]] || die "--from $FROM_FILE does not exist."
        $CHECK_ONLY || {
            install -D -m 600 "$FROM_FILE" "$ENV_FILE"
            # Copied and compared, never moved. The old file stays where it is on purpose: until a
            # deployment through this script has been seen to work, whatever put that file there is
            # still the way back, and a way back that needs a secrets file nobody kept is not one.
            cmp -s "$FROM_FILE" "$ENV_FILE" || die "the copy of the environment file does not match its
       source. Nothing further has been done."
            log "environment file copied to $ENV_FILE (mode 600); $FROM_FILE is left alone"
        }
    else
        # THE FIRST RUN ON A NEW HOST, and this is where it used to stop: "copy .env.example and
        # fill it in" is a sentence that costs an evening and a private file full of secrets that
        # nobody can check. An empty file is created instead and the questions below fill it.
        $CHECK_ONLY || {
            install -D -m 600 /dev/null "$ENV_FILE"
            log "no environment file yet - created $ENV_FILE (mode 600)"
        }
    fi
else
    $CHECK_ONLY || {
        chmod 600 "$ENV_FILE"
        log "environment file already at $ENV_FILE"
    }
fi
[[ -f "$ENV_FILE" ]] || die "no environment file at $ENV_FILE - and --check changes nothing, so it
       was not going to appear. Run without --check, or put it there yourself."

# --- 2a · the questions ------------------------------------------------------------------------------
# THE POINT OF THIS BLOCK: nobody writes a .env by hand, ever. Everything a person knows and a
# machine cannot work out is asked for here, once, and written into the file above - which is also
# why a second run is quiet: a value that is already there is never asked for again.
#
# Three rules the prompts keep:
#   a secret is read with the echo off and never redisplayed, not even to confirm it;
#   an answer whose SHAPE cannot be right is refused at the prompt, where it can still be corrected;
#   without a terminal nothing is asked at all - the run stops and names what is missing, because a
#   setup script reading a secret from a pipe is a setup script writing one into a CI log.

# Asks once for one variable and writes it. `kind` is one of:
#   plain            required, echoed while typing
#   secret           required, echo off
#   optional-plain   may be left empty by pressing Enter
#   optional-secret  the same, with the echo off
# `check` is the name of a shape function or "-" for anything non-empty.
ask_for() {
    local name="$1" kind="$2" check="$3" prompt="$4" hint="${5:-}" value existing

    existing="$(env_value "$ENV_FILE" "$name")"
    if [[ -n "${existing//[[:space:]]/}" && "$existing" != *REPLACE_ME* ]]; then
        log "$name is already set (left alone)"
        return 0
    fi

    if $CHECK_ONLY; then
        warn "$name is not set; a real run would ask for it"
        return 0
    fi

    [[ -t 0 ]] || die "$name is not in $ENV_FILE and there is no terminal to ask on. Run this from a
       shell, or pass --from with a file that already carries it. Nothing has been deployed."

    while true; do
        printf '\n\033[36m[setup]\033[0m %s\n' "$prompt" >&2
        [[ -n "$hint" ]] && printf '        %s\n' "$hint" >&2
        case "$kind" in
            secret|optional-secret)
                printf '        > ' >&2
                read -rs value
                printf '\n' >&2
                ;;
            *)
                printf '        > ' >&2
                read -r value
                ;;
        esac
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"

        if [[ -z "$value" ]]; then
            case "$kind" in
                optional-plain|optional-secret)
                    log "$name left empty - the feature that needs it is simply not served"
                    return 1
                    ;;
                *)
                    warn "that one cannot be left empty."
                    continue
                    ;;
            esac
        fi
        if [[ "$check" != "-" ]] && ! "$check" "$value"; then
            # The value is not repeated back: half of these are secrets, and the half that is not
            # is on the screen anyway, two lines up.
            warn "that does not look like it can be right. Try again."
            continue
        fi
        set_assignment "$ENV_FILE" "$name" "$value"
        log "$name written to $ENV_FILE"
        return 0
    done
}

# Writes a value only if there is none, without asking. The defaults nobody has an opinion about.
default_for() {
    local name="$1" value="$2" existing
    existing="$(env_value "$ENV_FILE" "$name")"
    [[ -n "${existing//[[:space:]]/}" ]] && return 0
    $CHECK_ONLY && { warn "$name is not set; a real run would write the default"; return 0; }
    set_assignment "$ENV_FILE" "$name" "$value"
    log "$name = $value (default)"
}

default_for COMPOSE_PROFILES     "db,bot,mc,backup,steward"
default_for COMPOSE_PROJECT_NAME "$DEFAULT_PROJECT"
default_for POSTGRES_DB          "nordtal"
default_for POSTGRES_USER        "nordtal"
# The path this very file is at, so that steward-deployer mounts the file this deployment is
# configured from. §3 below refuses to continue if the two ever disagree.
default_for STEWARD_ENV_FILE     "$ENV_FILE"
# steward/102: steward-deployer mounts the DIRECTORY holding STEWARD_ENV_FILE, not the file itself -
# a file bind follows the inode, so a rotation after the container started kept serving the deleted
# file forever, silently, for the lifetime of the container. A directory bind re-resolves the path on
# every access, so a replaced file is visible without recreating steward-deployer. Both of these are
# derived from STEWARD_ENV_FILE, never asked for, and §3 below refuses to continue if either one has
# drifted from what STEWARD_ENV_FILE actually says - the same shape of check as STEWARD_ENV_FILE's
# own agreement check three lines above.
default_for STEWARD_ENV_DIR       "$(dirname "$ENV_FILE")"
default_for STEWARD_ENV_FILE_NAME "$(basename "$ENV_FILE")"

ask_for STEWARD_HOST plain looks_like_host \
    "What name will the interface answer on?" \
    "A host name, not a URL - e.g. steward.dev.nordtal.eu. Its A/AAAA records have to point here;
        this script waits for that further down rather than deploying half a stack."

ask_for STEWARD_ACME_EMAIL plain looks_like_email \
    "Where should Let's Encrypt send certificate warnings?" \
    "One address, seen by Let's Encrypt only. It is what gets a mail if a renewal ever stops working."

if [[ -z "$(env_value "$ENV_FILE" EULA)" ]] && ! $CHECK_ONLY; then
    if [[ -t 0 ]]; then
        printf '\n\033[36m[setup]\033[0m %s\n' "Do you accept the Minecraft EULA? (https://aka.ms/MinecraftEULA)" >&2
        printf '        %s\n        > ' "Four Minecraft servers are about to start, and none of them may without this. [y/N]" >&2
        read -r eula_answer
        answer_is_yes "$eula_answer" || die "the EULA was not accepted, so there is nothing to deploy.
       Nothing has been changed beyond the environment file this script has been filling in."
        set_assignment "$ENV_FILE" EULA true
        log "EULA accepted and recorded"
    else
        die "EULA is not in $ENV_FILE and there is no terminal to ask on. It is a licence somebody
       has to accept, so it cannot be defaulted: set EULA=true in the file yourself, or run this
       from a shell."
    fi
fi

ask_for NORDTAL_BOT_TOKEN secret - \
    "The Discord bot token." \
    "Discord Developer Portal -> your application -> Bot -> Reset Token. Nothing is echoed while you
        type, and this script never prints it back."

ask_for STEWARD_UI_DISCORD_CLIENT_ID plain looks_like_snowflake \
    "The Discord application's Client ID - this is what the interface signs you in with." \
    "Same application, OAuth2 page. Its redirect URI has to be
        https://<the name above>/api/auth/callback, or the sign-in comes back with an error from
        Discord rather than from here."

ask_for STEWARD_UI_DISCORD_CLIENT_SECRET secret - \
    "The same application's Client Secret." \
    "OAuth2 -> Reset Secret. Discord shows it once; if you have lost it, reset it and paste the new
        one - nothing else in this deployment holds a copy."

ask_for NORDTAL_ACCESS_GUILD_ID plain looks_like_snowflake \
    "The id of the guild this deployment belongs to." \
    "Discord -> Developer Mode -> right-click the server -> Copy Server ID."

ask_for NORDTAL_ACCESS_ROLES_ADMIN plain looks_like_snowflake \
    "The id of the admin role." \
    "This is the one role that is not optional: it is what the bot mirrors into the database, and it
        is what decides who may sign in to the interface at all. Right-click the role -> Copy Role ID,
        and make sure your own account has it."

# bunq is the one answer with a real "no". Without it the bot simply does not poll for payments and
# nobody can buy access; every other part of the deployment is unaffected. Saying so at the prompt is
# cheaper than a person inventing a key to get past a question.
if ask_for NORDTAL_BOT_BUNQ_API_KEY optional-secret - \
    "The bunq API key, if payments should work. Press Enter to skip." \
    "Without it the bot starts and runs; it just never polls bunq, and access can only be granted by
        hand - through the interface or through /access in Discord."; then
    ask_for NORDTAL_BOT_BUNQ_ACCOUNT_ID plain "-" \
        "The bunq monetary account id the payments arrive in." \
        "A number. The bot refuses to start with a key and no account, because a poll loop with
        nowhere to look would be a silent one."
fi

# --- 3 · which deployment this is --------------------------------------------------------------------
# The project name decides which volumes the stack finds. Deploying under a different one does not
# fail: it brings up a second, empty stack beside the first - its own database, its own worlds - and
# the first sign of that is a fresh spawn. So a project that already has volumes here has to be the
# project this run is about to use.
#
# This used to sit after the secrets were generated, and that order was wrong in one specific and
# expensive way: §4 now generates the DATABASE PASSWORD, and whether it may do that depends entirely
# on whether this host already carries a postgres-data volume.
STEWARD_NAME="$(env_value "$ENV_FILE" STEWARD_HOST)"
PROJECT="$(env_value "$ENV_FILE" COMPOSE_PROJECT_NAME)"
PROJECT="${PROJECT:-$DEFAULT_PROJECT}"

existing="$(docker volume ls --format '{{.Name}}' | sed -n 's/_postgres-data$//p' | sort -u || true)"
if [[ -n "$existing" ]] && ! grep -qxF "$PROJECT" <<<"$existing"; then
    die "this host already carries a deployment under the project name(s)
       '$(tr '\n' ' ' <<<"$existing")', and this run would deploy '$PROJECT'. That would not fail -
       it would create a SECOND stack with empty volumes beside the one holding the worlds. Set
       COMPOSE_PROJECT_NAME in $ENV_FILE to the existing name, or remove the old deployment first."
fi
if grep -qxF "$PROJECT" <<<"$existing"; then
    ADOPTING=true
    log "adopting the existing deployment '$PROJECT' - its volumes are kept"
else
    ADOPTING=false
    log "this is a first deployment; project '$PROJECT'"
fi

# --- 4 · the secrets nobody should have to invent ------------------------------------------------------
# Four values that are shared between our own processes and that no person ever has to read: the
# database password, the proxy's forwarding secret, and the two Steward tokens. Asking for them would
# only teach somebody to type `hunter2` into a prompt.
#
# THE ONE THING THIS MUST NOT DO IS INVENT A PASSWORD FOR A DATABASE THAT ALREADY EXISTS. Postgres
# takes POSTGRES_PASSWORD from the environment on the first start of an empty data directory and
# never again: against an existing `postgres-data` a freshly generated password is not applied, it
# is simply wrong, and every service then fails to authenticate against a database that is
# perfectly healthy. So on a host that is being adopted, a missing password is a question for a
# person - the one they wrote down when the volume was created - and not a `rand`.
set_secret() {
    local name="$1" bytes="${2:-32}" value
    value="$(env_value "$ENV_FILE" "$name")"
    if [[ -n "${value//[[:space:]]/}" ]]; then
        log "$name is already set (left alone)"
        return
    fi
    if $CHECK_ONLY; then
        warn "$name is empty; a real run would generate one"
        return
    fi
    value="$(openssl rand -hex "$bytes")"
    set_assignment "$ENV_FILE" "$name" "$value"
    log "$name generated ($bytes random bytes, hex)"
}
command -v openssl >/dev/null 2>&1 || die "no openssl on this host, and four secrets have to come
       from somewhere. Install it, or put POSTGRES_PASSWORD, VELOCITY_FORWARDING_SECRET,
       STEWARD_API_TOKEN and STEWARD_DEPLOYER_TOKEN into $ENV_FILE yourself - and not the same
       value twice."

if [[ -z "$(env_value "$ENV_FILE" POSTGRES_PASSWORD)" ]] && $ADOPTING && ! $CHECK_ONLY; then
    ask_for POSTGRES_PASSWORD secret - \
        "The password of the database that is already on this host." \
        "'${PROJECT}_postgres-data' exists, so postgres will not take a new password: it reads
        POSTGRES_PASSWORD only when it initialises an empty data directory. Generating one here
        would leave every service unable to log in to a database that is working fine."
else
    set_secret POSTGRES_PASSWORD 24
fi

set_secret VELOCITY_FORWARDING_SECRET 24
set_secret STEWARD_API_TOKEN
set_secret STEWARD_DEPLOYER_TOKEN

if [[ "$(env_value "$ENV_FILE" STEWARD_API_TOKEN)" == "$(env_value "$ENV_FILE" STEWARD_DEPLOYER_TOKEN)" ]]; then
    $CHECK_ONLY || die "STEWARD_API_TOKEN and STEWARD_DEPLOYER_TOKEN are the same value. Reading
       containers and creating containers are different privileges - that is why they are two
       services on two ports - and one token for both makes the boundary a comment."
fi

# --- 4a · and now everything is there ------------------------------------------------------------
# The last check rather than the first demand: everything in REQUIRED has either been asked for or
# generated above, so this firing means this script failed to write something it said it wrote.
missing="$(env_missing "$ENV_FILE" "${REQUIRED[@]}")"
if [[ -n "$missing" ]]; then
    warn "these are still missing from $ENV_FILE, or still say REPLACE_ME:"
    printf '         %s\n' $missing >&2
    $CHECK_ONLY && die "--check does not ask and does not generate, so this is the list a real run
       would work through."
    die "that should not be possible - everything above is either asked for or generated. Nothing
       has been deployed."
fi

leftovers="$(env_replace_me_lines "$ENV_FILE")"
if [[ -n "$leftovers" ]]; then
    warn "REPLACE_ME is still in $ENV_FILE at line(s): $(tr '\n' ' ' <<<"$leftovers")"
    die "those are inside a value this script does not read one key at a time - a language table
       carried over from an older deployment, most likely. Every id in it is validated at start-up
       and REPLACE_ME fails that check by name."
fi

profiles="$(env_value "$ENV_FILE" COMPOSE_PROFILES)"
profiles_include "$profiles" steward || die "COMPOSE_PROFILES in $ENV_FILE is '$profiles', which does
       not include 'steward'. caddy, steward-ui and steward-deployer would be defined and never
       started, and the stack would come up healthy with no interface on it."

declared_env_file="$(env_value "$ENV_FILE" STEWARD_ENV_FILE)"
[[ "$declared_env_file" == "$ENV_FILE" ]] || die "STEWARD_ENV_FILE inside the file says
       '$declared_env_file', and the file is at '$ENV_FILE'. That value is what compose.yml mounts
       into steward-deployer, so the deployer would mount a different file than the one this
       deployment is configured from - or nothing at all."

# steward/102: STEWARD_ENV_DIR and STEWARD_ENV_FILE_NAME are never asked for, only derived from
# STEWARD_ENV_FILE above - so if either disagrees with what dirname/basename of the actual file say
# right now, something edited them by hand or the file moved after they were written, and
# steward-deployer would mount the wrong directory or look for the wrong name inside it.
declared_env_dir="$(env_value "$ENV_FILE" STEWARD_ENV_DIR)"
[[ "$declared_env_dir" == "$(dirname "$ENV_FILE")" ]] || die "STEWARD_ENV_DIR inside the file says
       '$declared_env_dir', and the file is at '$ENV_FILE' (directory '$(dirname "$ENV_FILE")').
       That value is what compose.yml mounts into steward-deployer as a directory - the fix for
       steward/102 - so a stale STEWARD_ENV_DIR would mount the wrong directory entirely."
declared_env_file_name="$(env_value "$ENV_FILE" STEWARD_ENV_FILE_NAME)"
[[ "$declared_env_file_name" == "$(basename "$ENV_FILE")" ]] || die "STEWARD_ENV_FILE_NAME inside
       the file says '$declared_env_file_name', and the file is named '$(basename "$ENV_FILE")'.
       steward-deployer looks for exactly this name inside the mounted directory."

log "every required value is set; the interface will answer on $STEWARD_NAME"

# --- 5 · the name, and the wait -------------------------------------------------------------------
# §10: a finished setup means everything works. There is no half state where the interface is up and
# the certificate is missing, because this is where it stops. Caddy asks Let's Encrypt for a
# certificate for STEWARD_HOST the moment it starts, and the HTTP-01 challenge is answered by
# whatever that name points at - so if it does not point here, the first thing the deployment does
# is fail at a third party, and it says so in a log nobody is watching yet.
this_hosts_addresses() {
    if [[ -n "$ADDRESSES_GIVEN" ]]; then
        printf '%s' "$ADDRESSES_GIVEN"
        return
    fi
    # Every globally scoped address on this machine. A host behind NAT has none that a name can
    # point at, which is what --address is for: it does not skip the comparison, it supplies the
    # missing side of it.
    ip -o addr show scope global 2>/dev/null | awk '{ print $4 }' | cut -d/ -f1
}

resolve() {
    # getent rather than dig: it is in every base image and on every host, and it asks the same
    # resolver everything else on this machine asks - which is the resolver whose answer matters.
    getent ahosts "$1" 2>/dev/null | awk '{ print $1 }' | sort -u
}

ours="$(this_hosts_addresses)"
[[ -n "${ours//[[:space:]]/}" ]] || die "this host has no globally scoped address, so there is
       nothing for $STEWARD_NAME to point at. If it is behind NAT, pass --address with the public
       address that reaches it."

attempt=0
while true; do
    resolved="$(resolve "$STEWARD_NAME")"
    strays="$(addresses_not_ours "$resolved" "$ours")"
    [[ -z "$strays" ]] && break

    if (( attempt == 0 )) || [[ "$strays" != "${last_strays:-}" ]]; then
        warn "$STEWARD_NAME does not point at this host yet."
        warn "  it resolves to:   $(tr '\n' ' ' <<<"${resolved:-nothing}")"
        warn "  this host is:     $(tr '\n' ' ' <<<"$ours")"
        warn "  not ours:         $(tr '\n' ' ' <<<"$strays")"
        warn "  waiting. Add or correct the record; this checks again every ${DNS_INTERVAL}s."
        last_strays="$strays"
    elif (( attempt % DNS_HEARTBEAT == 0 )); then
        warn "still waiting for $STEWARD_NAME ($(( attempt * DNS_INTERVAL / 60 )) min)"
    fi
    if $CHECK_ONLY; then
        die "the name does not point here, and --check does not wait."
    fi
    attempt=$(( attempt + 1 ))
    sleep "$DNS_INTERVAL"
done
log "$STEWARD_NAME resolves to this host ($(tr '\n' ' ' <<<"$resolved"))"

if $CHECK_ONLY; then
    log "--check: everything that can be checked without changing anything is in order."
    exit 0
fi

# --- 6 · renew steward-deployer ---------------------------------------------------------------------
# The one image nothing inside the stack can replace. compose.yml is baked into it, so this step is
# also how a changed deployment reaches this host at all.
if $BUILD_DEPLOYER; then
    # The alpha's way in, and it needs a JDK. It is also the only way while the image is not
    # published - which it is, from the first release that carries it.
    log "building $DEPLOYER_IMAGE from this checkout"
    sh "$ROOT/gradlew" :steward-deployer:build
    docker build -t "$DEPLOYER_IMAGE" "$ROOT/steward-deployer"
else
    log "pulling $DEPLOYER_IMAGE"
    docker pull "$DEPLOYER_IMAGE" || die "could not pull $DEPLOYER_IMAGE. A registry that answers
       'denied' means the same thing as one that answers 'not found': either the release that
       carries this image has not been published yet, or the package is still private. --build
       builds it from this checkout instead, and needs a JDK."
fi

# --- 7 · the deployment itself -----------------------------------------------------------------------
# A one-off container of the image just pulled, running `up`: it pulls every other image FIRST and
# only then takes anything down, and it exits with the deployment's own code. The long-running
# steward-deployer service is one of the containers it creates - this one is gone by then.
#
# COMPOSE_PROFILES is not passed in: compose reads it out of the file given to --env-file, which is
# the file mounted below (measured on this host 2026-09-13, compose v5.5.1).
#
# THE DIRECTORY IS MOUNTED, NOT THE FILE (steward/102), same as compose.yml's own steward-deployer
# service below - this is the other place that used to bind the file itself. A file bind follows the
# inode, not the path, so a rotation between this container starting and the file being read would
# have gone unnoticed exactly like it did for the long-running service; here the window is one `up`
# rather than the container's whole lifetime, but the mechanism is identical. NORDTAL_STEWARD_ENV_FILE
# tells the image which name to open under the mounted directory - it has no other way to know, since
# the directory is what it can see, not this variable's value.
log "deploying - this pulls every image before it stops anything"
docker run --rm \
    --name "${PROJECT}-setup" \
    -e "COMPOSE_PROJECT_NAME=$PROJECT" \
    -e "NORDTAL_STEWARD_ENV_FILE=/app/env/$(basename "$ENV_FILE")" \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$(dirname "$ENV_FILE"):/app/env:ro" \
    "$DEPLOYER_IMAGE" up \
    || die "the deployment failed, above. Nothing was stopped if the failure was a pull; if it was
       an up, 'docker compose -p $PROJECT ps' says what is running now."

log "done. The interface is at https://$STEWARD_NAME - the first certificate takes a few seconds."
log "If it does not answer: \`docker logs ${PROJECT}-caddy-1\` says whether Let's Encrypt did."
