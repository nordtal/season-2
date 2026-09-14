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
#   deploy/setup.sh                    pull the deployer image, then deploy the whole stack
#   deploy/setup.sh --build            build the deployer from this checkout first (needs a JDK)
#   deploy/setup.sh --check            every check, and stop before anything is changed
#   deploy/setup.sh --from PATH        where to take the environment file from (default: ./.env)
#   deploy/setup.sh --env-file PATH    where it belongs on this host (default: /etc/nordtal/season-2.env)
#   deploy/setup.sh --address IP       this host's public address, for a host behind NAT
#
# RUN IT AGAIN AFTER EVERY RELEASE. That is not a nicety: a new compose.yml reaches this host only
# inside a new steward-deployer image, so "deploy the new version" is this script, and everything
# else in the stack then follows from the deployer.
#
# IT NEVER PRINTS A SECRET. The environment file holds the Discord token, the bunq key, the database
# password and the two Steward tokens; this script copies it as a file and reads exactly four values
# out of it by name - STEWARD_HOST, STEWARD_ENV_FILE, COMPOSE_PROFILES and COMPOSE_PROJECT_NAME,
# none of them secret. Everything else is checked by NAME only: the report says which variable is
# missing, never what is in it.
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

# The variables the stack cannot start without, and every one of them is something only a person
# knows. They are checked by name: absent, empty or still REPLACE_ME all count as missing, and the
# report names the variable and never the value.
#
# The two Steward tokens are deliberately NOT here - this script generates those, because a secret a
# machine can invent is one a person should not have to.
REQUIRED=(
    COMPOSE_PROFILES
    POSTGRES_PASSWORD
    VELOCITY_FORWARDING_SECRET
    NORDTAL_BOT_TOKEN
    NORDTAL_BOT_BUNQ_API_KEY
    NORDTAL_BOT_BUNQ_ACCOUNT_ID
    NORDTAL_ACCESS_GUILD_ID
    NORDTAL_ACCESS_ROLES_ACCESS
    NORDTAL_ACCESS_ROLES_DONOR
    NORDTAL_ACCESS_ROLES_ADMIN
    NORDTAL_ACCESS_ROLES_ADMIN_PING
    NORDTAL_ACCESS_CHANNELS_ADMIN
    STEWARD_HOST
    STEWARD_ACME_EMAIL
    STEWARD_ENV_FILE
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
# The value is never printed and never passed on a command line: it goes into awk through -v and
# into a file created with mode 600 beside the destination, so it is not in `ps` and not in a
# world-readable place, not even for the moment between writing and renaming.
set_assignment() {
    local file="$1" name="$2" value="$3" tmp
    tmp="$(mktemp "$(dirname "$file")/.env.XXXXXX")"
    chmod 600 "$tmp"
    if grep -qE "^[[:space:]]*(export[[:space:]]+)?${name}[[:space:]]*=" "$file"; then
        awk -v name="$name" -v value="$value" '
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
        -h|--help)   sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
    [[ -n "$FROM_FILE" ]] || die "there is no environment file at $ENV_FILE and none to take.
       Either copy .env.example to .env in this checkout and fill it in, or point --from at the one
       that already exists (a host that has been deployed by something else has one - it is the file
       that thing interpolated compose.yml from)."
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
    $CHECK_ONLY || {
        chmod 600 "$ENV_FILE"
        log "environment file already at $ENV_FILE"
    }
fi
[[ -f "$ENV_FILE" ]] || die "no environment file at $ENV_FILE - and --check changes nothing, so it
       was not going to appear. Run without --check, or put it there yourself."

# --- 3 · what it has to contain ------------------------------------------------------------------
missing="$(env_missing "$ENV_FILE" "${REQUIRED[@]}")"
if [[ -n "$missing" ]]; then
    warn "these are missing from $ENV_FILE, or still say REPLACE_ME:"
    printf '         %s\n' $missing >&2
    die "fill them in and run this again. .env.example in this checkout documents every one of them."
fi

leftovers="$(env_replace_me_lines "$ENV_FILE")"
if [[ -n "$leftovers" ]]; then
    warn "REPLACE_ME is still in $ENV_FILE at line(s): $(tr '\n' ' ' <<<"$leftovers")"
    die "those are inside a value this script does not read one key at a time - the language table,
       most likely. Every id in it is validated at start-up and REPLACE_ME fails that check by name."
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

STEWARD_NAME="$(env_value "$ENV_FILE" STEWARD_HOST)"
PROJECT="$(env_value "$ENV_FILE" COMPOSE_PROJECT_NAME)"
PROJECT="${PROJECT:-$DEFAULT_PROJECT}"
log "every required value is set; the interface will answer on $STEWARD_NAME"

# --- 4 · the two Steward secrets --------------------------------------------------------------------
# Generated here rather than asked for, because they are shared between two of our own processes and
# nobody ever has to read them. They are two different values on purpose: one lets steward-ui READ
# containers through steward-worker, the other lets it CREATE them through steward-deployer.
set_secret() {
    local name="$1" value
    value="$(env_value "$ENV_FILE" "$name")"
    if [[ -n "${value//[[:space:]]/}" ]]; then
        log "$name is already set (left alone)"
        return
    fi
    if $CHECK_ONLY; then
        warn "$name is empty; a real run would generate one"
        return
    fi
    value="$(openssl rand -hex 32)"
    set_assignment "$ENV_FILE" "$name" "$value"
    log "$name generated (32 random bytes, hex)"
}
command -v openssl >/dev/null 2>&1 || die "no openssl on this host, and two secrets have to come
       from somewhere. Install it, or put STEWARD_API_TOKEN and STEWARD_DEPLOYER_TOKEN into
       $ENV_FILE yourself - 64 hex characters each, and NOT the same value twice."
set_secret STEWARD_API_TOKEN
set_secret STEWARD_DEPLOYER_TOKEN

if [[ "$(env_value "$ENV_FILE" STEWARD_API_TOKEN)" == "$(env_value "$ENV_FILE" STEWARD_DEPLOYER_TOKEN)" ]]; then
    $CHECK_ONLY || die "STEWARD_API_TOKEN and STEWARD_DEPLOYER_TOKEN are the same value. Reading
       containers and creating containers are different privileges - that is why they are two
       services on two ports - and one token for both makes the boundary a comment."
fi

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

# --- 6 · which deployment this is ------------------------------------------------------------------
# The project name decides which volumes the stack finds. Deploying under a different one does not
# fail: it brings up a second, empty stack beside the first - its own database, its own worlds - and
# the first sign of that is a fresh spawn. So a project that already has volumes here has to be the
# project this run is about to use.
existing="$(docker volume ls --format '{{.Name}}' | sed -n 's/_postgres-data$//p' | sort -u || true)"
if [[ -n "$existing" ]] && ! grep -qxF "$PROJECT" <<<"$existing"; then
    die "this host already carries a deployment under the project name(s)
       '$(tr '\n' ' ' <<<"$existing")', and this run would deploy '$PROJECT'. That would not fail -
       it would create a SECOND stack with empty volumes beside the one holding the worlds. Set
       COMPOSE_PROJECT_NAME in $ENV_FILE to the existing name, or remove the old deployment first."
fi
if grep -qxF "$PROJECT" <<<"$existing"; then
    log "adopting the existing deployment '$PROJECT' - its volumes are kept"
else
    log "this is a first deployment; project '$PROJECT'"
fi

if $CHECK_ONLY; then
    log "--check: everything that can be checked without changing anything is in order."
    exit 0
fi

# --- 7 · renew steward-deployer ---------------------------------------------------------------------
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

# --- 8 · the deployment itself -----------------------------------------------------------------------
# A one-off container of the image just pulled, running `up`: it pulls every other image FIRST and
# only then takes anything down, and it exits with the deployment's own code. The long-running
# steward-deployer service is one of the containers it creates - this one is gone by then.
#
# COMPOSE_PROFILES is not passed in: compose reads it out of the file given to --env-file, which is
# the file mounted below (measured on this host 2026-09-13, compose v5.5.1).
log "deploying - this pulls every image before it stops anything"
docker run --rm \
    --name "${PROJECT}-setup" \
    -e "COMPOSE_PROJECT_NAME=$PROJECT" \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$ENV_FILE:/app/env/.env:ro" \
    "$DEPLOYER_IMAGE" up \
    || die "the deployment failed, above. Nothing was stopped if the failure was a pull; if it was
       an up, 'docker compose -p $PROJECT ps' says what is running now."

log "done. The interface is at https://$STEWARD_NAME - the first certificate takes a few seconds."
log "If it does not answer: \`docker logs ${PROJECT}-caddy-1\` says whether Let's Encrypt did."
