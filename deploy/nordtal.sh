#!/usr/bin/env bash
#
# Installs the season 2 stack into the current directory, on a host that has only Docker.
#
#   curl -fsSL https://raw.githubusercontent.com/nordtal/season-2/main/deploy/nordtal.sh | bash
#
# Every run asks GitHub for the newest release and runs the copy of this file that belongs to it.
# That copy installs the images of that release. NORDTAL_RELEASE names another release, for an
# install only.
#
# The script stays in the installation directory as ./nordtal.sh and changes a setting later:
#
#   ./nordtal.sh                       the menu: show the settings, change one, then deploy
#   ./nordtal.sh --deploy              no menu; ask only for what is missing, then deploy
#   ./nordtal.sh --build               build the agent from a checkout first (needs a JDK)
#   ./nordtal.sh --check               run every check and stop before anything changes
#   ./nordtal.sh --from PATH           read the answers from this file instead of asking
#   ./nordtal.sh --env-file PATH       the path of the environment file on this host
#                                      (default: /etc/nordtal/season-2.env)
#   ./nordtal.sh --address IP          the public address of this host, for a host behind NAT
#   ./nordtal.sh --no-self-update      run this file as it is, without fetching the copy of the release
#
# When steward-agent runs and is healthy, a deploy is an update run like any other: the script
# writes the request and waits for the report. An install, or a stack whose agent is down or
# unhealthy, gets the `up` command of the agent instead. That command repairs the stack.
#
# `update` asks steward-agent for a run, as the buttons of Steward do, and waits for its report:
#
#   ./nordtal.sh update                update the whole network: install what is new, restart what needs it
#   ./nordtal.sh update --restart      restart everything, install nothing
#   ./nordtal.sh update --backup       run one backup now
#   ./nordtal.sh update --down smp     stop one service and keep it down
#   ./nordtal.sh update --start [svc]  release every hold, or the hold of one service
#   ./nordtal.sh update --in 10        count down ten minutes first
#   ./nordtal.sh update --no-wait      print the request id and return instead of waiting
#   ./nordtal.sh update --replace-local  replace builds made on this host without asking
#
# Every run fetches the copy of the release first, runs it and prints its version and source.
# Without a network it runs the local copy, but it never deploys without knowing the release. An
# update that ends DONE leaves the copy of the release that it installed. Steward's update button
# also updates a running stack.
#
# The script asks for the values that only a person knows and generates the other secrets. It writes
# them to /etc/nordtal/season-2.env with mode 600, outside the installation directory. A secret that
# only one service reads goes to the own file of that service instead, in
# /etc/nordtal-secrets/season-2/<service>/. That file belongs to the uid of the service, and
# steward-agent never mounts it. Every run moves such a line there. The script never prints a secret
# and never puts one on a command line.
set -Eeuo pipefail

# The installation is the current directory. Under `curl ... | bash`, BASH_SOURCE[0] is empty.
INSTALL_DIR="$PWD"
SELF_NAME="nordtal.sh"
INSTALLED="$INSTALL_DIR/$SELF_NAME"
# A run updates itself from the tag of the newest release, never from a branch.
RELEASES_API="${NORDTAL_RELEASES_API:-https://api.github.com/repos/nordtal/season-2/releases/latest}"
# This file at the tag of the release, and steward-agent at its version. Set once the release is known.
SELF_URL=""
AGENT_IMAGE=""
IMAGES=""
DEFAULT_IMAGES="ghcr.io/nordtal"

DEFAULT_ENV_FILE="/etc/nordtal/season-2.env"
DEFAULT_PROJECT="nordtal-s2"

# Seconds to wait for GitHub before the run uses the copy that is already here.
SELF_UPDATE_TIMEOUT=10

# Seconds between two lookups of the name, and the interval of the message that nothing has changed.
DNS_INTERVAL=15
DNS_HEARTBEAT=8

log()  { printf '\033[36m[nordtal]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[nordtal]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m[nordtal]\033[0m %s\n' "$*" >&2; exit 1; }

# The question table needs associative arrays (bash 4). macOS ships bash 3.2.
if [[ "${BASH_VERSINFO[0]:-0}" -lt 4 ]]; then
    die "this needs bash 4 or newer and found ${BASH_VERSION:-an unknown version} at ${BASH:-bash}.
       macOS ships bash 3.2 and cannot be talked out of it. Install a current one and make sure it
       comes first in PATH:  brew install bash"
fi

# The variables that the environment file must hold before the stack starts. A variable that is
# absent, empty or REPLACE_ME is missing. An unset role or channel disables its feature.
REQUIRED=(
    COMPOSE_PROFILES
    POSTGRES_DB
    POSTGRES_USER
    POSTGRES_PASSWORD
    POSTGRES_DISCORD_BOT_PASSWORD
    POSTGRES_PROXY_PASSWORD
    POSTGRES_LIMBO_PASSWORD
    POSTGRES_HUNGER_GAMES_PASSWORD
    POSTGRES_SMP_PASSWORD
    POSTGRES_STEWARD_PASSWORD
    VELOCITY_FORWARDING_SECRET
    EULA
    NORDTAL_BOT_TOKEN
    NORDTAL_ACCESS_GUILD_ID
    STEWARD_HOST
    STEWARD_ACME_EMAIL
    STEWARD_ENV_FILE
    STEWARD_ENV_DIR
    STEWARD_ENV_FILE_NAME
    NORDTAL_SECRETS_DIR
    NORDTAL_DIR
    STEWARD_DISCORD_CLIENT_ID
    STEWARD_DISCORD_CLIENT_SECRET
)

# One directory per volume of compose.yml under NORDTAL_DIR, named like the volume without its prefix.
# The copy of compose.yml, because the host does not hold the file yet.
DATA_DIRS=(
    "postgres-data"
    "steward-backups"
    "caddy-data"
    "caddy-config"
    "bunq-context"
    "mc-proxy"
    "mc-limbo"
    "mc-hunger-games"
    "mc-smp"
    "mc-proxy-plugins"
    "mc-limbo-plugins"
    "mc-hunger-games-plugins"
    "mc-smp-plugins"
)

# Pure decisions. deploy/nordtal-test.sh runs them without Docker and without a network.

# Reads one value from an environment file without sourcing the file.
env_value() {
    local file="$1" name="$2" line
    [[ -f "$file" ]] || return 0
    line="$(grep -m1 -E "^[[:space:]]*(export[[:space:]]+)?${name}=" "$file" || true)"
    line="${line#*=}"
    # Removes one layer of quotes, as compose does.
    if [[ "$line" == \"*\" && ${#line} -ge 2 ]]; then
        line="${line:1:${#line}-2}"
    elif [[ "$line" == \'*\' && ${#line} -ge 2 ]]; then
        line="${line:1:${#line}-2}"
    fi
    printf '%s' "$line"
}

# The registry prefix of our images. NORDTAL_IMAGES in the environment wins, then the environment
# file of the deployment, then ghcr.io.
images_prefix() {
    local file="$1" value="${NORDTAL_IMAGES:-}"
    [[ -n "${value//[[:space:]]/}" ]] || value="$(env_value "$file" NORDTAL_IMAGES)"
    value="${value//[[:space:]]/}"
    printf '%s' "${value:-$DEFAULT_IMAGES}"
}

has_assignment() {
    local file="$1" name="$2"
    [[ -f "$file" ]] && grep -qE "^[[:space:]]*(export[[:space:]]+)?${name}[[:space:]]*=" "$file"
}

# Rewrites the file so that every assignment of the name reads `name=value`, or drops the
# assignments when $4 is `drop`. The value reaches awk through its environment, never through a
# command line. The result is a temporary file with the mode of the original, renamed over it.
rewrite_assignment() {
    local file="$1" name="$2" value="$3" mode="${4:-set}" tmp
    tmp="$(mktemp "$(dirname "$file")/.env.XXXXXX")"
    chmod 600 "$tmp"
    SET_ASSIGNMENT_NAME="$name" SET_ASSIGNMENT_VALUE="$value" SET_ASSIGNMENT_MODE="$mode" awk '
        BEGIN {
            name = ENVIRON["SET_ASSIGNMENT_NAME"]; value = ENVIRON["SET_ASSIGNMENT_VALUE"]
            mode = ENVIRON["SET_ASSIGNMENT_MODE"]
        }
        {
            key = $0
            sub(/=.*$/, "", key)
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
            sub(/^export[[:space:]]+/, "", key)
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
            if (key == name) {
                if (mode != "drop") print name "=" value
                next
            }
            print
        }' "$file" > "$tmp"
    mv "$tmp" "$file"
}

# Writes `name=value` into the file. An existing assignment (also with leading whitespace or
# `export`) is replaced, otherwise a line is appended. The result is renamed over the file with mode 600.
set_assignment() {
    local file="$1" name="$2" value="$3" tmp
    if has_assignment "$file" "$name"; then
        rewrite_assignment "$file" "$name" "$value"
        return 0
    fi
    tmp="$(mktemp "$(dirname "$file")/.env.XXXXXX")"
    chmod 600 "$tmp"
    cat "$file" > "$tmp"
    printf '%s=%s\n' "$name" "$value" >> "$tmp"
    mv "$tmp" "$file"
}

remove_assignment() {
    local file="$1" name="$2"
    has_assignment "$file" "$name" || return 0
    rewrite_assignment "$file" "$name" "" drop
}

# The secrets that only one service reads, one per line: `<name> <service> [<name in the service>]`.
# The name is the one that this script asks for and keeps in the shared file. The optional last
# field is the variable that the service reads, where it differs. A value that two services share
# stays in the shared file. nordtal-test.sh compares the services with the mounts of compose.yml.
SERVICE_SECRETS=(
    "NORDTAL_STEWARD_BUNQ_API_KEY steward-bunq"
    "NORDTAL_STEWARD_BUNQ_ACCOUNT_ID steward-bunq"
    "NORDTAL_BOT_TOKEN discord-bot"
    "STEWARD_DISCORD_CLIENT_SECRET steward NORDTAL_STEWARD_WEB_DISCORD_CLIENT_SECRET"
    "STEWARD_WEB_PUSH_PRIVATE_KEY steward NORDTAL_STEWARD_WEB_WEB_PUSH_PRIVATE_KEY"
    "STEWARD_TOTP_KEY steward NORDTAL_STEWARD_WEB_TOTP_KEY"
    "STEWARD_OFFSITE_PASSWORD steward-agent NORDTAL_STEWARD_AGENT_OFFSITE_PASSWORD"
)

# The uid of each of those services. It owns the file of the service. nordtal-test.sh compares it
# with the `user:` of compose.yml. steward-agent has no entry and runs as root.
declare -A SERVICE_UID=(
    [steward-bunq]=10003
    [discord-bot]=10001
    [steward]=10002
    [steward-agent]=0
)

# The directory of the own files of the services, when the shared file names none. It lies beside
# the directory of the shared file and never inside it, because steward-agent mounts that directory.
# /etc/nordtal/season-2.env gives /etc/nordtal-secrets/season-2.
default_secrets_dir() {
    local env_file="$1" name
    name="$(basename "$env_file")"
    printf '%s-secrets/%s' "$(dirname "$env_file")" "${name%.env}"
}

secrets_dir_ok() {
    local env_file="$1" dir="$2" env_dir
    env_dir="$(dirname "$env_file")"
    is_absolute "$dir" || return 1
    [[ "${dir%/}/" != "${env_dir%/}/"* ]]
}

secret_service() {
    local wanted="$1" entry name service own
    for entry in "${SERVICE_SECRETS[@]}"; do
        read -r name service own <<<"$entry"
        [[ "$name" == "$wanted" ]] && { printf '%s' "$service"; return 0; }
    done
    return 0
}

service_name() {
    local wanted="$1" entry name service own
    for entry in "${SERVICE_SECRETS[@]}"; do
        read -r name service own <<<"$entry"
        [[ "$name" == "$wanted" ]] && { printf '%s' "${own:-$name}"; return 0; }
    done
    printf '%s' "$wanted"
}

secret_services() {
    local entry name service own
    for entry in "${SERVICE_SECRETS[@]}"; do
        read -r name service own <<<"$entry"
        printf '%s\n' "$service"
    done | awk '!seen[$0]++'
}

# The variable of the shared file that compose.yml passes to a service as SECRETS_REVISION, for
# example SECRETS_REVISION_STEWARD_BUNQ. A changed secret changes it, so the next run recreates the service.
revision_name() {
    local service="$1"
    service="${service^^}"
    printf 'SECRETS_REVISION_%s' "${service//-/_}"
}

service_secrets_file() {
    printf '%s/%s/secrets.env' "$1" "$2"
}

# The file to read a name from and to write it to: the own file of its service when only that service
# reads it and the shared file names a secrets directory, otherwise the shared file.
home_of() {
    local env_file="$1" name="$2" service dir
    service="$(secret_service "$name")"
    dir="$(env_value "$env_file" NORDTAL_SECRETS_DIR)"
    if [[ -n "$service" && -n "${dir//[[:space:]]/}" ]]; then
        service_secrets_file "$dir" "$service"
    else
        printf '%s' "$env_file"
    fi
}

stale_service_secrets() {
    local env_file="$1" entry name service own
    for entry in "${SERVICE_SECRETS[@]}"; do
        read -r name service own <<<"$entry"
        has_assignment "$env_file" "$name" && printf '%s\n' "$name"
    done
    return 0
}

# Copies every single-reader secret from the shared file into the file of its service. The
# directory (mode 700) and the file (mode 600) belong to the uid of the service. The secrets
# directory itself belongs to root with mode 700. A line that is still in the shared file is newer
# than the file of the service, because a move empties it, so its value wins. An empty line is only
# dropped. With `keep`, the shared file keeps its lines, for a running steward-agent whose
# compose.yml still reads them there. Without `keep`, each line goes after its copy is written, so an
# interrupted run leaves a copy and never nothing. MOVED_SECRETS lists each name that was copied or
# dropped, never a value.
MOVED_SECRETS=()
move_service_secrets() {
    local env_file="$1" dir="$2" keep="${3:-}" entry name service own value file
    install -d -m 700 "$dir"
    while IFS= read -r service; do
        install -d -m 700 "$dir/$service"
        file="$(service_secrets_file "$dir" "$service")"
        [[ -f "$file" ]] || install -m 600 /dev/null "$file"
    done < <(secret_services)
    for entry in "${SERVICE_SECRETS[@]}"; do
        read -r name service own <<<"$entry"
        has_assignment "$env_file" "$name" || continue
        value="$(env_value "$env_file" "$name")"
        [[ -z "${value//[[:space:]]/}" ]] || write_service_secret "$env_file" "$dir" "$service" "${own:-$name}" "$value"
        [[ "$keep" == keep ]] || remove_assignment "$env_file" "$name"
        MOVED_SECRETS+=("$name")
    done
    while IFS= read -r service; do
        hand_secrets_to "$dir" "$service"
    done < <(secret_services)
}

# Writes one value into the file of a service. When the file changes, the service gets a new
# SECRETS_REVISION, because compose does not see the file.
write_service_secret() {
    local env_file="$1" dir="$2" service="$3" name="$4" value="$5" file
    file="$(service_secrets_file "$dir" "$service")"
    if has_assignment "$file" "$name" && [[ "$(env_value "$file" "$name")" == "$value" ]]; then
        return 0
    fi
    set_assignment "$file" "$name" "$value"
    set_assignment "$env_file" "$(revision_name "$service")" "$(openssl rand -hex 8)"
}

hand_secrets_to() {
    local dir="$1" service="$2" uid
    uid="${SERVICE_UID[$service]}"
    chown -R "$uid:$uid" "$dir/$service"
    chmod 700 "$dir/$service"
    chmod 600 "$(service_secrets_file "$dir" "$service")"
}

# Checks that the shared file names a secrets directory outside the directory that steward-agent
# mounts. Then it moves every single-reader secret into the file of its service, or with `keep`
# only copies it. It needs root, because each file belongs to the uid of its service. It dies
# instead of leaving a secret where the agent reads it.
settle_service_secrets() {
    local env_file="$1" keep="${2:-}" dir
    dir="$(env_value "$env_file" NORDTAL_SECRETS_DIR)"
    if [[ -z "${dir//[[:space:]]/}" ]]; then
        dir="$(default_secrets_dir "$env_file")"
        set_assignment "$env_file" NORDTAL_SECRETS_DIR "$dir"
        log "NORDTAL_SECRETS_DIR = $dir (default)"
    fi
    secrets_dir_ok "$env_file" "$dir" || die "NORDTAL_SECRETS_DIR in $env_file is '$dir', which is not an
       absolute path outside $(dirname "$env_file"). steward-agent mounts that directory whole, so a
       secret kept there is a secret the agent reads."
    [[ "${EUID:-$(id -u)}" -eq 0 ]] || die "each service's own secrets file under $dir belongs to the uid
       that service runs as, and only root can hand it over. Run this as root. Nothing was moved."
    MOVED_SECRETS=()
    move_service_secrets "$env_file" "$dir" "$keep"
    if (( ${#MOVED_SECRETS[@]} > 0 )) && [[ "$keep" == keep ]]; then
        log "copied ${MOVED_SECRETS[*]} into each service's own file under $dir; $env_file keeps them
       until the running steward-agent reads them there"
    elif (( ${#MOVED_SECRETS[@]} > 0 )); then
        log "moved ${MOVED_SECRETS[*]} out of $env_file into each service's own file under $dir"
    fi
    local copy
    while IFS= read -r copy; do
        warn "$copy holds $(tr '\n' ' ' <<<"$(stale_service_secrets "$copy")")as well, and steward-agent
       reads every file in $(dirname "$env_file"). Move that copy somewhere the agent does not mount."
    done < <(stale_copies "$env_file")
}

# Moves the single-reader secrets when the compose.yml of the running agent mounts the file of each
# service. While the agent still reads them from the shared file, it only copies them.
settle_for_agent() {
    local env_file="$1" container="$2"
    if docker exec "$container" grep -q NORDTAL_SECRETS_DIR /app/compose.yml 2>/dev/null; then
        settle_service_secrets "$env_file"
    else
        settle_service_secrets "$env_file" keep
    fi
}

stale_copies() {
    local env_file="$1" other
    for other in "$(dirname "$env_file")"/* "$(dirname "$env_file")"/.[!.]*; do
        [[ -f "$other" && "$other" != "$env_file" ]] || continue
        [[ -z "$(stale_service_secrets "$other")" ]] || printf '%s\n' "$other"
    done
    return 0
}

value_of() {
    local env_file="$1" name="$2" home
    home="$(home_of "$env_file" "$name")"
    if [[ "$home" == "$env_file" ]]; then
        env_value "$env_file" "$name"
    else
        env_value "$home" "$(service_name "$name")"
    fi
}

assign() {
    local env_file="$1" name="$2" value="$3" service dir
    if [[ "$(home_of "$env_file" "$name")" == "$env_file" ]]; then
        set_assignment "$env_file" "$name" "$value"
        return 0
    fi
    service="$(secret_service "$name")"
    dir="$(env_value "$env_file" NORDTAL_SECRETS_DIR)"
    install -d -m 700 "$dir/$service"
    write_service_secret "$env_file" "$dir" "$service" "$(service_name "$name")" "$value"
    hand_secrets_to "$dir" "$service"
}

env_missing() {
    local file="$1" name value
    shift
    for name in "$@"; do
        value="$(value_of "$file" "$name")"
        if [[ -z "${value//[[:space:]]/}" || "$value" == *REPLACE_ME* ]]; then
            printf '%s\n' "$name"
        fi
    done
}

env_replace_me_lines() {
    local file="$1"
    [[ -f "$file" ]] || return 0
    grep -n 'REPLACE_ME' "$file" | grep -v '^[0-9]*:[[:space:]]*#' | cut -d: -f1 || true
}

# COMPOSE_PROFILES must include `steward`. Without it, steward-bunq never starts, and Caddy starts only with `mc`.
profiles_include() {
    local profiles="$1" wanted="$2" profile
    local IFS=','
    for profile in $profiles; do
        [[ "${profile//[[:space:]]/}" == "$wanted" ]] && return 0
    done
    return 1
}

is_absolute() { [[ "$1" == /* ]]; }

# Answer shapes. The prompt checks them and refuses only what cannot be right.

# A Discord snowflake: digits only, with a wide lower bound for older ids.
looks_like_snowflake() { [[ "$1" =~ ^[0-9]{15,21}$ ]]; }

looks_like_host() {
    [[ "$1" =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$ ]]
}

looks_like_email() { [[ "$1" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; }

looks_like_public_address() {
    local host="${1%:*}" port="${1##*:}"
    [[ "$1" == *:* ]] || return 1
    looks_like_host "$host" || return 1
    [[ "$port" =~ ^[0-9]{1,5}$ ]] && (( port >= 1 && port <= 65535 ))
}

looks_like_profiles() {
    [[ "$1" =~ ^[[:space:]]*[a-z0-9-]+([[:space:]]*,[[:space:]]*[a-z0-9-]+)*[[:space:]]*$ ]]
}

# The profiles of compose.yml that a selection may name. `standby` starts by name only.
# nordtal-test.sh compares this list with the one of compose.yml.
SELECTABLE_PROFILES=(db bot mc steward devpack)

unknown_profiles() {
    local profiles="$1" profile known
    local IFS=','
    for profile in $profiles; do
        profile="${profile//[[:space:]]/}"
        [[ -n "$profile" ]] || continue
        for known in "${SELECTABLE_PROFILES[@]}"; do
            [[ "$profile" == "$known" ]] && continue 2
        done
        printf '%s\n' "$profile"
    done
}

warn_unknown_profiles() {
    local profiles unknown
    profiles="$(env_value "$1" COMPOSE_PROFILES)"
    unknown="$(unknown_profiles "$profiles")"
    [[ -z "$unknown" ]] || warn "COMPOSE_PROFILES in $1 names $(tr '\n' ' ' <<<"$unknown")which compose.yml does
       not define as a profile to select, so it selects nothing. The profiles are: ${SELECTABLE_PROFILES[*]}."
}

mojang_assets_answered() {
    case "$(env_value "$1" NORDTAL_MOJANG_ASSETS)" in
        true|false) return 0 ;;
        *) return 1 ;;
    esac
}

answer_is_yes() {
    case "${1,,}" in
        y|yes|true) return 0 ;;
        *) return 1 ;;
    esac
}

QUESTIONS=(
    STEWARD_HOST
    STEWARD_ACME_EMAIL
    NETWORK_PUBLIC_ADDRESS
    EULA
    NORDTAL_BOT_TOKEN
    STEWARD_DISCORD_CLIENT_ID
    STEWARD_DISCORD_CLIENT_SECRET
    NORDTAL_ACCESS_GUILD_ID
    STEWARD_ROOT_DISCORD_ID
    NORDTAL_STEWARD_BUNQ_API_KEY
    NORDTAL_STEWARD_BUNQ_ACCOUNT_ID
    COMPOSE_PROFILES
)

declare -A QUESTION_KIND=(
    [STEWARD_HOST]=plain
    [STEWARD_ACME_EMAIL]=plain
    [NETWORK_PUBLIC_ADDRESS]=plain
    [EULA]=licence
    [NORDTAL_BOT_TOKEN]=secret
    [STEWARD_DISCORD_CLIENT_ID]=plain
    [STEWARD_DISCORD_CLIENT_SECRET]=secret
    [NORDTAL_ACCESS_GUILD_ID]=plain
    [STEWARD_ROOT_DISCORD_ID]=optional-plain
    [NORDTAL_STEWARD_BUNQ_API_KEY]=optional-secret
    [NORDTAL_STEWARD_BUNQ_ACCOUNT_ID]=plain
    [COMPOSE_PROFILES]=plain
)

declare -A QUESTION_CHECK=(
    [STEWARD_HOST]=looks_like_host
    [STEWARD_ACME_EMAIL]=looks_like_email
    [NETWORK_PUBLIC_ADDRESS]=looks_like_public_address
    [EULA]=-
    [NORDTAL_BOT_TOKEN]=-
    [STEWARD_DISCORD_CLIENT_ID]=looks_like_snowflake
    [STEWARD_DISCORD_CLIENT_SECRET]=-
    [NORDTAL_ACCESS_GUILD_ID]=looks_like_snowflake
    [STEWARD_ROOT_DISCORD_ID]=looks_like_snowflake
    [NORDTAL_STEWARD_BUNQ_API_KEY]=-
    [NORDTAL_STEWARD_BUNQ_ACCOUNT_ID]=-
    [COMPOSE_PROFILES]=looks_like_profiles
)

declare -A QUESTION_PROMPT=(
    [STEWARD_HOST]="What name will the interface answer on?"
    [STEWARD_ACME_EMAIL]="Where should Let's Encrypt send certificate warnings?"
    [NETWORK_PUBLIC_ADDRESS]="What do players type into Minecraft to reach this network?"
    [EULA]="Do you accept the Minecraft EULA? (https://aka.ms/MinecraftEULA)"
    [NORDTAL_BOT_TOKEN]="The Discord bot token."
    [STEWARD_DISCORD_CLIENT_ID]="The Discord application's Client ID - this is what the interface signs you in with."
    [STEWARD_DISCORD_CLIENT_SECRET]="The same application's Client Secret."
    [NORDTAL_ACCESS_GUILD_ID]="The id of the guild this deployment belongs to."
    [STEWARD_ROOT_DISCORD_ID]="Your own Discord id: the one account that may become root of the interface."
    [NORDTAL_STEWARD_BUNQ_API_KEY]="The bunq API key, if payments should work. Press Enter to skip."
    [NORDTAL_STEWARD_BUNQ_ACCOUNT_ID]="The bunq monetary account id the payments arrive in."
    [COMPOSE_PROFILES]="Which parts of the stack come up?"
)

declare -A QUESTION_HINT=(
    [STEWARD_HOST]="A host name, not a URL - e.g. steward.dev.nordtal.eu. Its A/AAAA records have to point here;
        this script waits for that further down rather than deploying half a stack."
    [STEWARD_ACME_EMAIL]="One address, seen by Let's Encrypt only. It is what gets a mail if a renewal ever stops working."
    [NETWORK_PUBLIC_ADDRESS]="Host AND port, e.g. play.example.com:25565. The port is not optional even if a SRV record
        lets players leave it out: this is the address the proxy hands to a client when it moves it
        during an update, and nothing resolves a SRV record on that client's behalf."
    [EULA]="Four Minecraft servers are about to start, and none of them may without this. [y/N]"
    [NORDTAL_BOT_TOKEN]="Discord Developer Portal -> your application -> Bot -> Reset Token. Nothing is echoed while you
        type, and this script never prints it back."
    [STEWARD_DISCORD_CLIENT_ID]="Same application, OAuth2 page. Its redirect URI has to be
        https://<the name above>/auth/callback, or the sign-in comes back with an error from
        Discord rather than from here."
    [STEWARD_DISCORD_CLIENT_SECRET]="OAuth2 -> Reset Secret. Discord shows it once; if you have lost it, reset it and paste the new
        one - nothing else in this deployment holds a copy."
    [NORDTAL_ACCESS_GUILD_ID]="Discord -> Developer Mode -> right-click the server -> Copy Server ID."
    [STEWARD_ROOT_DISCORD_ID]="Discord -> Developer Mode -> right-click your own name -> Copy User ID. It counts only
        while nobody is an admin, as on a fresh install or after restoring an early dump; the root then
        grants everyone else. Press Enter to skip: then nobody can become root, and the sign-in page says so."
    [NORDTAL_STEWARD_BUNQ_API_KEY]="Without it the whole stack starts and runs; steward just never polls bunq, and access
        can only be granted by hand - through the interface or through /access in Discord."
    [NORDTAL_STEWARD_BUNQ_ACCOUNT_ID]="A number. steward-bunq refuses to start with a key and no account, because a poll
        loop with nowhere to look would be a silent one."
    [COMPOSE_PROFILES]="A comma-separated list. The production selection is db,bot,mc,steward and there is
        rarely a reason to type anything else; 'steward' has to be in it, or steward-bunq never
        starts and Caddy only with 'mc'."
)

# The generated secrets. The menu lists them and never edits them, because a new POSTGRES_PASSWORD
# breaks an existing database.
GENERATED=(
    POSTGRES_PASSWORD
    POSTGRES_DISCORD_BOT_PASSWORD
    POSTGRES_PROXY_PASSWORD
    POSTGRES_LIMBO_PASSWORD
    POSTGRES_HUNGER_GAMES_PASSWORD
    POSTGRES_SMP_PASSWORD
    POSTGRES_STEWARD_PASSWORD
    VELOCITY_FORWARDING_SECRET
    STEWARD_AGENT_TOKEN
    STEWARD_BUNQ_TOKEN
    STEWARD_WEB_PUSH_PUBLIC_KEY
    STEWARD_WEB_PUSH_PRIVATE_KEY
    STEWARD_TOTP_KEY
)

shown_value() {
    local kind="$1" value="$2"
    if [[ -z "${value//[[:space:]]/}" ]]; then
        printf '(not set)'
        return
    fi
    case "$kind" in
        # The three bullets as UTF-8 bytes, because \u expands only in a UTF-8 locale.
        secret|optional-secret) printf '\342\200\242\342\200\242\342\200\242' ;;
        *)                      printf '%s' "$value" ;;
    esac
}

menu_choice() {
    local typed="$1" count="$2"
    case "${typed,,}" in
        q|quit)   printf 'quit';   return ;;
        d|deploy) printf 'deploy'; return ;;
    esac
    if [[ "$typed" =~ ^[0-9]+$ ]] && (( 10#$typed >= 1 && 10#$typed <= count )); then
        printf 'edit %s' "$(( 10#$typed ))"
    fi
}

addresses_not_ours() {
    local resolved="$1" ours="$2" address
    if [[ -z "${resolved//[[:space:]]/}" ]]; then
        # A name without an address does not match.
        printf '(nothing - the name does not resolve)\n'
        return
    fi
    for address in $resolved; do
        grep -qxF "$address" <<<"$ours" || printf '%s\n' "$address"
    done
}

# The images on this host that a pull before `up` would replace. The function is pure, so the test
# feeds it fixtures. A RepoDigest alone proves nothing, because the containerd store also gives
# local builds one.
#
#   pairs             "<image><TAB><service>" per service under the active profiles
#   local_digests     "<image><TAB><RepoDigests as JSON>" per image on this host
#   registry_digests  "<image><TAB><manifest digest>" per image that the registry answered for
#
# Prints "<RISK|UNKNOWN><TAB><image><TAB><services>" per local image. RISK means the image has no
# RepoDigests or lacks the digest of the registry. UNKNOWN means the registry could not be asked.
at_risk_images() {
    local pairs="$1" local_digests="$2" registry_digests="$3"
    [[ -n "$pairs" ]] || return 0
    awk -F'\t' -v local_digests="$local_digests" -v registry_digests="$registry_digests" '
        BEGIN {
            n = split(local_digests, llines, "\n")
            for (i = 1; i <= n; i++) {
                if (llines[i] == "") continue
                split(llines[i], d, "\t")
                local_repo[d[1]] = d[2]
            }
            n = split(registry_digests, rlines, "\n")
            for (i = 1; i <= n; i++) {
                if (rlines[i] == "") continue
                split(rlines[i], d, "\t")
                registry_digest[d[1]] = d[2]
            }
        }
        {
            if (!($1 in seen)) { order[++count] = $1 }
            seen[$1] = 1
            # mawk creates services[$1] before it evaluates the right side, so the test cannot be inline.
            if ($1 in services) { services[$1] = services[$1] "," $2 } else { services[$1] = $2 }
        }
        END {
            for (i = 1; i <= count; i++) {
                image = order[i]
                if (!(image in local_repo)) continue
                loc = local_repo[image]
                if (loc == "[]") {
                    print "RISK\t" image "\t" services[image]
                    continue
                }
                if (!(image in registry_digest)) {
                    print "UNKNOWN\t" image "\t" services[image]
                    continue
                }
                if (index(loc, registry_digest[image]) == 0) {
                    print "RISK\t" image "\t" services[image]
                }
            }
        }
    ' <<<"$pairs"
}

fingerprint() {
    local file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | cut -c1-12
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$file" | awk '{ print $NF }' | cut -c1-12
    else
        printf 'unknown'
    fi
}

running_from_a_file() { [[ -n "${BASH_SOURCE[0]:-}" && -f "${BASH_SOURCE[0]}" ]]; }

self_path() { printf '%s/%s' "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)" "$(basename "${BASH_SOURCE[0]}")"; }

# A copy in a checkout is never replaced.
from_a_checkout() {
    running_from_a_file || return 1
    local here; here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    [[ "$(basename "$here")" == "deploy" && -f "$here/../compose.yml" ]]
}

fetch_url() {
    local url="$1" into="$2"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --max-time "$SELF_UPDATE_TIMEOUT" "$url" -o "$into" 2>/dev/null
    elif command -v wget >/dev/null 2>&1; then
        wget -q --timeout="$SELF_UPDATE_TIMEOUT" -O "$into" "$url" 2>/dev/null
    else
        return 1
    fi
}

looks_like_this_script() {
    local file="$1"
    [[ -s "$file" ]] || return 1
    # `sed -n 1p` instead of a pipe into `head`: see deploy/pipe-safety-test.sh.
    [[ "$(sed -n '1p' "$file")" == '#!/usr/bin/env bash' ]] || return 1
    grep -q '^SELF_NAME=' "$file" || return 1
    bash -n "$file" 2>/dev/null
}

# Asking and writing answers. The functions read $ENV_FILE and $CHECK_ONLY, which the caller sets.
# `dev init` asks some of QUESTIONS in the same words, and NordtalQuestionsTest compares the two.

# Asks once for one variable and writes it. `kind` is one of:
#   plain            required, echoed while typing
#   secret           required, echo off
#   optional-plain   optional, Enter leaves it empty
#   optional-secret  optional, echo off
#   licence          y/N; a no writes nothing, and the check for required values then stops the run
# `check` is the name of a shape function, or "-" for any non-empty value.
# `force` asks again for a variable that is already set. Without it, a set value stays.
ask_for() {
    local name="$1" kind="$2" check="$3" prompt="$4" hint="${5:-}" force="${6:-}" value existing

    existing="$(value_of "$ENV_FILE" "$name")"
    if [[ -z "$force" && -n "${existing//[[:space:]]/}" && "$existing" != *REPLACE_ME* ]]; then
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
        printf '\n\033[36m[nordtal]\033[0m %s\n' "$prompt" >&2
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

        if [[ "$kind" == licence ]]; then
            answer_is_yes "$value" || return 1
            assign "$ENV_FILE" "$name" true
            log "$name accepted and recorded"
            return 0
        fi
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
            # The prompt does not repeat the value, which may be a secret.
            warn "that does not look like it can be right. Try again."
            continue
        fi
        assign "$ENV_FILE" "$name" "$value"
        log "$name written to $(home_of "$ENV_FILE" "$name")"
        return 0
    done
}

default_for() {
    local name="$1" value="$2" existing
    existing="$(env_value "$ENV_FILE" "$name")"
    [[ -n "${existing//[[:space:]]/}" ]] && return 0
    $CHECK_ONLY && { warn "$name is not set; a real run would write the default"; return 0; }
    set_assignment "$ENV_FILE" "$name" "$value"
    log "$name = $value (default)"
}

ask_question() {
    local name="$1" again="${2:-}"
    ask_for "$name" "${QUESTION_KIND[$name]}" "${QUESTION_CHECK[$name]}" \
        "${QUESTION_PROMPT[$name]}" "${QUESTION_HINT[$name]}" "$again"
}

# Generates one secret when there is none where it lives. A single-reader secret lives in the file of
# its service. REPLACE_ME counts as none.
set_secret() {
    local name="$1" bytes="${2:-32}" value
    value="$(value_of "$ENV_FILE" "$name")"
    if [[ -n "${value//[[:space:]]/}" && "$value" != *REPLACE_ME* ]]; then
        log "$name is already set (left alone)"
        return
    fi
    if $CHECK_ONLY; then
        warn "$name is empty; a real run would generate one"
        return
    fi
    value="$(openssl rand -hex "$bytes")"
    assign "$ENV_FILE" "$name" "$value"
    log "$name generated ($bytes random bytes, hex)"
}

# Generates every missing secret except POSTGRES_PASSWORD, on a deploy and on an update. A release
# that adds a secret therefore brings it whichever way it is installed. SECRETS_MADE records that this run generated one.
SECRETS_MADE=false
generate_secrets() {
    # steward-agent sets the passwords of the database roles at every start, so a new one needs only
    # a restart. A new POSTGRES_PASSWORD would need more.
    set_secret POSTGRES_DISCORD_BOT_PASSWORD 24
    set_secret POSTGRES_PROXY_PASSWORD 24
    set_secret POSTGRES_LIMBO_PASSWORD 24
    set_secret POSTGRES_HUNGER_GAMES_PASSWORD 24
    set_secret POSTGRES_SMP_PASSWORD 24
    set_secret POSTGRES_STEWARD_PASSWORD 24
    set_secret VELOCITY_FORWARDING_SECRET 24
    set_secret STEWARD_AGENT_TOKEN
    set_secret STEWARD_BUNQ_TOKEN
    # The key that steward seals the secret of each authenticator app with. A new key makes every
    # enrolled app unreadable, so each admin adds theirs again. This script never rotates it.
    set_secret STEWARD_TOTP_KEY
    SECRETS_MADE=true
}

# The compose projects that already have a deployment on this host, one per line: each project with a
# steward-agent container, running or not, and each `<project>_postgres-data` volume of an older installation.
deployed_projects() {
    local volumes="$1" agent_projects="$2"
    { sed -n 's/_postgres-data$//p' <<<"$volumes"; printf '%s\n' "$agent_projects"; } | sed '/^$/d' | sort -u
}

# What this run is to the host: `adopt` the deployment of its project (a database in the bind-mounted
# data directory of the installation counts too), `first`, or `second` beside the deployment of another project.
deployment_kind() {
    local project="$1" projects="$2" install_dir="$3"
    if [[ -n "$projects" ]] && ! grep -qxF "$project" <<<"$projects"; then
        printf 'second'
    elif [[ -n "$projects" || -e "$install_dir/postgres-data/PG_VERSION" ]]; then
        printf 'adopt'
    else
        printf 'first'
    fi
}

# An update run requested from the host. It works while the stack itself is broken.
# The functions here decide only. `cmd_update` below the seam reaches for Docker.

UPDATE_KINDS=(UPDATE RESTART BACKUP DOWN START)

# Seconds that the command waits. The run itself continues after the command gives up.
UPDATE_TIMEOUT_DEFAULT=1800

# Seconds between two looks at a request. Every look starts a JVM inside the agent.
UPDATE_POLL=10

# Seconds that steward-agent may stay silent before the watch gives up. This is the start period of
# the health check of the agent, which covers a run that recreates the agent itself.
UPDATE_GAP=300

update_scope_ok() {
    [[ "$1" =~ ^[a-z0-9-]+(,[a-z0-9-]+)*$ ]]
}

parse_update_args() {
    UPDATE_KIND=UPDATE
    UPDATE_SCOPE=""
    UPDATE_DELAY=0
    UPDATE_WAIT=true
    UPDATE_TIMEOUT="$UPDATE_TIMEOUT_DEFAULT"
    UPDATE_ENV_FILE="$DEFAULT_ENV_FILE"
    UPDATE_REPLACE_LOCAL=false
    local kinds=0
    while (( $# > 0 )); do
        case "$1" in
            --restart)  UPDATE_KIND=RESTART; kinds=$(( kinds + 1 )); shift ;;
            --backup)   UPDATE_KIND=BACKUP;  kinds=$(( kinds + 1 )); shift ;;
            --down)     UPDATE_KIND=DOWN;    kinds=$(( kinds + 1 ))
                        UPDATE_SCOPE="${2:-}"
                        [[ -n "$UPDATE_SCOPE" ]] || die "update --down needs a service to stop"
                        shift 2 ;;
            # A bare `--start` releases every hold. A flag after it is not a service.
            --start)    UPDATE_KIND=START;   kinds=$(( kinds + 1 ))
                        if [[ -n "${2:-}" && "${2:0:1}" != "-" ]]; then
                            UPDATE_SCOPE="$2"; shift 2
                        else
                            shift
                        fi ;;
            --in)       UPDATE_DELAY="${2:-}"; shift 2 || die "update --in needs a number of minutes" ;;
            --no-wait)  UPDATE_WAIT=false; shift ;;
            --timeout)  UPDATE_TIMEOUT="${2:-}"; shift 2 || die "update --timeout needs seconds" ;;
            --env-file) UPDATE_ENV_FILE="${2:-}"; shift 2 || die "update --env-file needs a path" ;;
            --replace-local) UPDATE_REPLACE_LOCAL=true; shift ;;
            *)          die "unknown argument to \`update\`: $1" ;;
        esac
    done

    (( kinds <= 1 )) || die "update takes one of --restart, --backup, --down or --start, not several"
    [[ "$UPDATE_REPLACE_LOCAL" == false || "$UPDATE_KIND" == UPDATE ]] \
        || die "update --replace-local is for an update only, the one run that replaces a build"
    [[ "$UPDATE_DELAY" =~ ^[0-9]+$ ]] || die "update --in takes whole minutes, got: $UPDATE_DELAY"
    (( UPDATE_DELAY <= 1440 )) || die "update --in is capped at a day (1440 minutes)"
    [[ "$UPDATE_TIMEOUT" =~ ^[0-9]+$ ]] || die "update --timeout takes seconds, got: $UPDATE_TIMEOUT"
    [[ -n "$UPDATE_ENV_FILE" ]] || die "update --env-file needs a path"
    if [[ -n "$UPDATE_SCOPE" ]]; then
        update_scope_ok "$UPDATE_SCOPE" \
            || die "'$UPDATE_SCOPE' is not a service name: lowercase, digits and dashes, commas
       between several. That is the shape the database itself enforces."
    fi
}

# The command that runs inside steward-agent, one word per line. The agent writes the row with the
# same refusals as the buttons of Steward, so the open run in the inbox is the one lock, and this
# script writes no SQL. An empty scope is the whole network. $4 true lets the run replace builds
# made on this host.
update_request_words() {
    local kind="$1" scope="$2" minutes="$3" replace_local="${4:-false}"
    printf '%s\n' steward-agent request "$kind" "$scope" "$minutes"
    [[ "$replace_local" == true ]] && printf '%s\n' --replace-local
    return 0
}

UPDATE_KEPT_LOCAL=3

update_is_over() {
    case "$1" in
        DONE|FAILED|CANCELLED) return 0 ;;
        *) return 1 ;;
    esac
}

release_of_tag() {
    local tag="$1"
    printf '%s' "${tag#v}"
}

tag_name_of() {
    sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | sed -n '1p'
}

self_url_for() {
    printf '%s' "${NORDTAL_SH_URL:-https://raw.githubusercontent.com/nordtal/season-2/v$1/deploy/nordtal.sh}"
}

newest_release() {
    local answer tag=""
    answer="$(mktemp "${TMPDIR:-/tmp}/nordtal-release.XXXXXX")"
    if fetch_url "$RELEASES_API" "$answer"; then
        tag="$(tag_name_of <"$answer")"
    fi
    rm -f "$answer"
    [[ -n "$tag" ]] && release_of_tag "$tag"
    return 0
}

# Replaces the file $1 with the copy of release $2, or of the newest release without $2. It returns
# true only when it replaced the file. When GitHub is unreachable or the download is incomplete, the
# file stays as it is.
renew_file() {
    local file="$1" release="${2:-}" candidate
    [[ -n "$release" ]] || release="$(newest_release)"
    if [[ -z "$release" ]]; then
        warn "GitHub did not say which release is the newest, so $file stays as it is"
        return 1
    fi
    candidate="$(mktemp "${TMPDIR:-/tmp}/nordtal.sh.XXXXXX")"
    if ! fetch_url "$(self_url_for "$release")" "$candidate" || ! looks_like_this_script "$candidate"; then
        rm -f "$candidate"
        warn "release $release's copy of this script could not be fetched, so $file stays as it is"
        return 1
    fi
    if [[ "$(fingerprint "$candidate")" == "$(fingerprint "$file")" ]]; then
        rm -f "$candidate"
        return 1
    fi
    # A rename leaves no half-written script after an interrupted copy.
    if ! install -m 755 "$candidate" "$file.new" || ! mv -f "$file.new" "$file"; then
        rm -f "$candidate" "$file.new"
        warn "could not write $file, so it stays as it is"
        return 1
    fi
    rm -f "$candidate"
    log "$file is now release $release's copy ($(fingerprint "$file"))"
}

# container does not run or its image has no version tag.
agent_release() {
    local container="$1" image tag
    image="$(awk -F '\t' -v name="$container" '$1 == name { print $2 }' <<<"$(docker ps --format '{{.Names}}\t{{.Image}}')")"
    tag="${image##*:}"
    [[ "$image" == *:* && "$tag" =~ ^v?[0-9][0-9A-Za-z.+-]*$ ]] && release_of_tag "$tag"
    return 0
}

# run, that is the release the run installed. The renewal before the run asks GitHub for the newest
# release, which can still be the previous one just after a tag, or can fail to answer.
renew_to_installed() {
    local file="$1" container="$2" release
    release="$(agent_release "$container")"
    if [[ -z "$release" ]]; then
        warn "$container does not say which release it runs, so $file stays as it is"
        return 1
    fi
    renew_file "$file" "$release"
}

# How a deploy reaches the stack. `request` when a healthy steward-agent runs: the agent carries out
# the update like any run. `up` for an install, for a repair of an agent that is down, or with --build.
# $1 is whether --build was given. $2 is whether the agent of the project runs and is healthy.
deploy_by() {
    local build="$1" agent_healthy="$2"
    if [[ "$build" == true || "$agent_healthy" != true ]]; then
        printf 'up'
    else
        printf 'request'
    fi
}

update_wait() {
    local id="$1" container="$2"
    local waited=0 silent=0 answer status steps said="" code

    log "waiting; Ctrl-C stops WATCHING and never the run itself"
    while :; do
        # The status and a tab before the stage, then one line per step. No output means the row is gone.
        code=0
        answer="$(docker exec "$container" steward-agent status "$id" 2>/dev/null)" || code=$?
        steps="$(sed '1d' <<<"$answer")"
        answer="$(sed -n '1p' <<<"$answer")"
        status="${answer%%$'\t'*}"

        if [[ -z "$status" ]]; then
            (( silent > 0 )) || warn "steward-agent does not answer, which a run that recreates it also causes; asking
       again for up to ${UPDATE_GAP}s"
            (( silent += UPDATE_POLL ))
            (( silent <= UPDATE_GAP )) \
                || die "request $id is no longer in steward_inbox, or steward-agent has not answered for ${UPDATE_GAP}s."
        else
            (( silent == 0 )) || log "steward-agent answers again"
            silent=0
        fi
        if [[ -n "$status" && "$status" != "$said" ]]; then
            log "request $id is $status"
            said="$status"
        fi
        if update_is_over "$status"; then
            [[ -z "$steps" ]] || printf '%s\n' "$steps"
            [[ "$status" == DONE ]] && return 0
            (( code == UPDATE_KEPT_LOCAL )) && return "$UPDATE_KEPT_LOCAL"
            return 1
        fi

        (( waited += UPDATE_POLL ))
        if (( waited > UPDATE_TIMEOUT )); then
            # Only the waiting stops. The run continues.
            warn "request $id is still $status after ${UPDATE_TIMEOUT}s. The run continues without
       this command watching it; ./nordtal.sh update --no-wait prints ids, and the interface shows
       the run under /operations."
            return 2
        fi
        sleep "$UPDATE_POLL"
    done
}

# The definitions end here. Only a genuine `source` returns. Under `curl ... | bash` there is no BASH_SOURCE.
if [[ -n "${BASH_SOURCE[0]:-}" && "${BASH_SOURCE[0]}" != "$0" ]]; then
    return 0
fi

# The `update` subcommand. It runs before the self-update and the questions of the deploy. It renews
# the file it runs from first, so a host that is only updated also runs the copy of the newest release.

cmd_update() {
    parse_update_args "$@"

    [[ -f "$UPDATE_ENV_FILE" ]] \
        || die "$UPDATE_ENV_FILE is not there, so this host has no deployment to update.
       If the environment file is somewhere else: ./nordtal.sh update --env-file PATH"

    warn_unknown_profiles "$UPDATE_ENV_FILE"

    # One name only, because the file also holds secrets.
    local project container
    project="$(env_value "$UPDATE_ENV_FILE" COMPOSE_PROJECT_NAME)"
    project="${project:-$DEFAULT_PROJECT}"
    container="${project}-steward-agent-1"

    # `docker ps` and never `docker inspect` on a container with secrets. A here-string, not a pipe.
    grep -qxF "$container" <<<"$(docker ps --format '{{.Names}}')" \
        || die "$container is not running, so there is nobody to ask for a run.
       \`docker compose -p $project ps\` says what is up."

    # The file of each service is written before the run, because the release of the run mounts it.
    # The shared file keeps the lines while the compose.yml of the running agent still reads them
    # there, because compose refuses a file that lacks a variable it requires.
    settle_for_agent "$UPDATE_ENV_FILE" "$container"
    # This copy belongs to the release, so a secret that the release adds is made before the run installs it.
    $SECRETS_MADE || ENV_FILE="$UPDATE_ENV_FILE" CHECK_ONLY=false generate_secrets

    local id
    # A subshell: its die ends only the subshell, and the exit here ends the command.
    id="$(update_submit "$container")" || exit 1
    if [[ "$UPDATE_WAIT" != true ]]; then
        printf '%s\n' "$id"
        return 0
    fi

    local code=0
    update_wait "$id" "$container" || code=$?
    (( code != 0 )) || update_done "$container"
    (( code == UPDATE_KEPT_LOCAL )) || return "$code"

    # The run stopped before it moved anything, because it would have replaced a build made on this host.
    if [[ ! -t 0 ]]; then
        die "request $id stopped before replacing the builds made on this host that its report names.
       Nothing was stopped. There is no terminal here to ask, so once they may go:
       ./nordtal.sh update --replace-local"
    fi
    printf '\n\033[36m[nordtal]\033[0m %s\n' "Ask again, and let the run replace the builds named above?" >&2
    printf '        %s\n        > ' "Whatever they carry that no release has is lost. [y/N]" >&2
    local replace_answer
    read -r replace_answer
    answer_is_yes "$replace_answer" || die "not asking again. Nothing was stopped and the local builds stay."
    UPDATE_REPLACE_LOCAL=true
    id="$(update_submit "$container")" || exit 1
    update_wait "$id" "$container" || return $?
    update_done "$container"
}

# What follows a run that ended DONE: the secrets that the new agent reads from the file of each
# service and, after an update, this file as the copy of the release that the run installed.
update_done() {
    local container="$1"
    settle_for_agent "$UPDATE_ENV_FILE" "$container"
    if [[ "$UPDATE_KIND" == UPDATE ]] && running_from_a_file && ! from_a_checkout; then
        renew_to_installed "$(self_path)" "$container" || true
    fi
}

update_submit() {
    local container="$1" id words
    mapfile -t words < <(update_request_words "$UPDATE_KIND" "$UPDATE_SCOPE" "$UPDATE_DELAY" "$UPDATE_REPLACE_LOCAL")
    # The agent prints the id on stdout and a refusal, such as an open run, on stderr.
    id="$(docker exec "$container" "${words[@]}" | sed -n '1p' | tr -d '[:space:]')" \
        || die "steward-agent refused the request; its reason is above"
    [[ "$id" =~ ^[0-9]+$ ]] || die "steward-agent did not answer with a request id (got: '$id')"

    local when=""
    (( UPDATE_DELAY > 0 )) && when=", not before $UPDATE_DELAY minute(s) from now"
    log "request $id: $UPDATE_KIND${UPDATE_SCOPE:+ $UPDATE_SCOPE}$when" >&2
    printf '%s\n' "$id"
}

if [[ "${1:-}" == update ]]; then
    shift
    if [[ -z "${NORDTAL_SH_RENEWED:-}" ]] && running_from_a_file && ! from_a_checkout; then
        self="$(self_path)"
        if renew_file "$self"; then
            export NORDTAL_SH_RENEWED=true
            exec bash "$self" update "$@"
        fi
    fi
    cmd_update "$@"
    exit $?
fi

# Arguments
# Kept whole, because the self-update may pass them to a newer copy.
ARGV=("$@")

FROM_FILE=""
ENV_FILE=""
ADDRESSES_GIVEN=""
CHECK_ONLY=false
BUILD_AGENT=false
SELF_UPDATE=true
# A plain run on a terminal shows the menu. `--deploy`, `--check` and `--build` turn it off.
MENU=true

# The help text is the comment block at the top of this file.
usage() {
    local file="${BASH_SOURCE[0]:-}"
    [[ -f "$file" ]] || file="$INSTALLED"
    [[ -f "$file" ]] || { printf '%s\n' "https://github.com/nordtal/season-2/releases/latest" ; return 0; }
    awk 'NR > 1 { if ($0 !~ /^#/) exit; print }' "$file" | sed 's/^# \{0,1\}//'
}

while (( $# > 0 )); do
    case "$1" in
        --from)             FROM_FILE="${2:?--from needs a path}"; shift 2 ;;
        --env-file)         ENV_FILE="${2:?--env-file needs a path}"; shift 2 ;;
        --address)          ADDRESSES_GIVEN+="${2:?--address needs an address}"$'\n'; shift 2 ;;
        --check)            CHECK_ONLY=true; MENU=false; shift ;;
        --build)            BUILD_AGENT=true; MENU=false; shift ;;
        --deploy)           MENU=false; shift ;;
        --no-self-update)   SELF_UPDATE=false; shift ;;
        -h|--help)          usage; exit 0 ;;
        *)                  die "unknown argument: $1 (try --help)" ;;
    esac
done

# Without a terminal the script asks nothing. The run has what it needs, or it names what is missing.
[[ -t 0 ]] || MENU=false

# 0 · The release and the running copy
# The release decides everything below, so nothing runs without one. A copy renewed from the tag
# gets it through NORDTAL_RELEASE and does not ask again.
resolve_release() {
    if [[ -n "${NORDTAL_RELEASE:-}" ]]; then
        RELEASE="$(release_of_tag "$NORDTAL_RELEASE")"
        RELEASE_NAMED="${NORDTAL_RELEASE_NAMED:-true}"
        return 0
    fi
    RELEASE="$(newest_release)"
    [[ -n "$RELEASE" ]] || die "GitHub did not say which release is the newest ($RELEASES_API), and
       nothing else may decide what this host runs. Try again once it answers, or name the release
       yourself: NORDTAL_RELEASE=0.11.0 ./nordtal.sh"
    RELEASE_NAMED=false
}
RELEASE=""
RELEASE_NAMED=false
resolve_release
# For the renewed copy. It takes GitHub's answer for no one's choice and does not ask again.
export NORDTAL_RELEASE="$RELEASE" NORDTAL_RELEASE_NAMED="$RELEASE_NAMED"
SELF_URL="$(self_url_for "$RELEASE")"
log "release $RELEASE"

ORIGIN="${NORDTAL_SH_ORIGIN:-}"
SELF_TEMP=""

renew_self() {
    if ! $SELF_UPDATE; then
        ORIGIN="${ORIGIN:-this copy, as it is (--no-self-update)}"
        return 0
    fi
    if from_a_checkout; then
        ORIGIN="a checkout, which is never replaced by the published version"
        return 0
    fi

    local candidate
    candidate="$(mktemp "${TMPDIR:-/tmp}/nordtal.sh.XXXXXX")"
    if ! fetch_url "$SELF_URL" "$candidate" || ! looks_like_this_script "$candidate"; then
        rm -f "$candidate"
        # Without a network the run continues, unless the script is piped: then there is no copy to run.
        running_from_a_file || die "this script was piped into bash and $SELF_URL could not be
       fetched, so there is no file to run and nothing has been changed. Download it by hand and
       run that: the installation is whatever directory you run it in."
        warn "$SELF_URL could not be fetched, so nothing was renewed - this run uses the copy that
       is already here. That copy can be older than the deployment it is about to make."
        ORIGIN="the copy that was already here - GitHub could not be reached"
        return 0
    fi

    if running_from_a_file \
        && [[ "$(fingerprint "$candidate")" == "$(fingerprint "${BASH_SOURCE[0]}")" ]]; then
        rm -f "$candidate"
        ORIGIN="this copy, which is release $RELEASE's"
        return 0
    fi

    # The copy runs from a temporary file and installs itself after the directory question.
    chmod 755 "$candidate"
    export NORDTAL_SH_ORIGIN="$SELF_URL, fetched for this run"
    export NORDTAL_SH_TEMP="$candidate"
    if running_from_a_file; then
        log "release $RELEASE carries another version of this file ($(fingerprint "$candidate"));
       running that one instead of this one ($(fingerprint "${BASH_SOURCE[0]}"))"
    else
        log "fetched release $RELEASE's copy; running that"
    fi
    if [[ ! -t 0 ]] && (exec </dev/tty) 2>/dev/null; then
        # Piped: stdin is the script, so the new copy asks on the terminal.
        exec bash "$candidate" --no-self-update "${ARGV[@]}" </dev/tty
    fi
    exec bash "$candidate" --no-self-update "${ARGV[@]}"
}
renew_self
SELF_TEMP="${NORDTAL_SH_TEMP:-}"
if running_from_a_file; then
    log "running $SELF_NAME $(fingerprint "${BASH_SOURCE[0]}") - ${ORIGIN:-this copy}"
else
    log "running from a pipe - ${ORIGIN:-no file to fingerprint}"
fi

install_self() {
    running_from_a_file || return 0
    local source; source="$(self_path)"
    if [[ "$source" != "$INSTALLED" ]]; then
        install -m 755 "$source" "$INSTALLED" \
            || die "could not write $INSTALLED. The installation directory has to be writable - it
       is where every world and every database in this deployment is about to live."
        log "this script is now $INSTALLED - run it again to change a setting or to deploy"
    fi
    # Deleting the file that bash reads is safe: the open file survives its name.
    [[ -n "$SELF_TEMP" && -f "$SELF_TEMP" && "$SELF_TEMP" != "$INSTALLED" ]] && rm -f "$SELF_TEMP"
    return 0
}

# 0a · The directory
# Asked once per installation. An environment file that names another NORDTAL_DIR stops the run,
# because two installations would share a password, a project name and volumes.
if [[ -z "$ENV_FILE" ]]; then
    ENV_FILE="${STEWARD_ENV_FILE:-$DEFAULT_ENV_FILE}"
fi

declared_dir="$(env_value "$ENV_FILE" NORDTAL_DIR)"
if [[ -n "${declared_dir//[[:space:]]/}" && "$declared_dir" != "$INSTALL_DIR" ]]; then
    die "$ENV_FILE already describes an installation, and it is at
       '$declared_dir' - not here ('$INSTALL_DIR'). Run ./nordtal.sh in that directory, or pass
       --env-file to point this one at an environment file of its own. Nothing has been changed."
fi

if [[ -z "${declared_dir//[[:space:]]/}" ]]; then
    $CHECK_ONLY || {
        [[ -t 0 ]] || die "there is nothing at $ENV_FILE that says this directory is an
       installation, and there is no terminal to ask on. A first install is a question somebody has
       to answer; run this from a shell. Nothing has been changed."
        printf '\n\033[36m[nordtal]\033[0m %s\n' "Install nordtal season 2 into this directory?" >&2
        printf '        %s\n' "$INSTALL_DIR" >&2
        printf '        %s\n' "Everything this deployment keeps becomes a directory in there: four worlds," >&2
        printf '        %s\n' "the database, every plugin's configuration, and the nightly backups. The" >&2
        printf '        %s\n' "secrets do not - they go to $ENV_FILE, mode 600." >&2
        printf '        %s\n        > ' "[y/N]" >&2
        read -r here_answer
        answer_is_yes "$here_answer" || die "not installing here, and nothing has been changed. Run
       this again from the directory the installation should live in - that directory is the only
       thing this question decides."
    }
fi

$CHECK_ONLY || install_self

command -v docker >/dev/null 2>&1 \
    || die "no docker on this host. Everything below needs a daemon; nothing here installs one."
docker compose version >/dev/null 2>&1 \
    || die "docker is here but \`docker compose\` is not. The compose plugin is a separate package."
docker info >/dev/null 2>&1 \
    || die "docker is installed and this user cannot talk to the daemon. Run this as root, or as a
       member of the docker group - which is root with extra steps, and is the trade this host has
       already made by running a stack at all."
log "docker $(docker version --format '{{.Server.Version}}'), compose $(docker compose version --short)"

# 2 · The environment file
# It holds every secret. Compose interpolates it and steward-agent mounts it read-only.
is_absolute "$ENV_FILE" || die "--env-file has to be absolute, and '$ENV_FILE' is not. compose
       resolves a relative path against steward-agent's project directory, which is inside its
       image - so a relative path here points at a file that does not exist."

if [[ ! -f "$ENV_FILE" ]]; then
    if [[ -z "$FROM_FILE" && -f "$INSTALL_DIR/.env" ]]; then
        FROM_FILE="$INSTALL_DIR/.env"
    fi
    if [[ -n "$FROM_FILE" ]]; then
        [[ -f "$FROM_FILE" ]] || die "--from $FROM_FILE does not exist."
        $CHECK_ONLY || {
            install -D -m 600 "$FROM_FILE" "$ENV_FILE"
            # The old file stays as the way back.
            cmp -s "$FROM_FILE" "$ENV_FILE" || die "the copy of the environment file does not match its
       source. Nothing further has been done."
            log "environment file copied to $ENV_FILE (mode 600); $FROM_FILE is left alone"
        }
    else
        # A new host starts with an empty file, which the questions below fill.
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
IMAGES="$(images_prefix "$ENV_FILE")"
AGENT_IMAGE="$IMAGES/steward-agent:$RELEASE"

# 2a · The questions
# The script asks once for each missing value. It reads secrets with echo off and never shows them.
# The prompt refuses a malformed answer.

default_for COMPOSE_PROFILES     "db,bot,mc,steward"
default_for COMPOSE_PROJECT_NAME "$DEFAULT_PROJECT"
default_for POSTGRES_DB          "nordtal"
default_for POSTGRES_USER        "nordtal"
# steward-agent mounts the file that the deployment is configured from. Section 3 checks it.
default_for STEWARD_ENV_FILE     "$ENV_FILE"
# Absolute, because compose resolves a relative bind inside the image of steward-agent.
default_for NORDTAL_DIR           "$INSTALL_DIR"
# steward-agent mounts the directory, because a bind of the file would keep the old inode of a rotated file.
default_for STEWARD_ENV_DIR       "$(dirname "$ENV_FILE")"
default_for STEWARD_ENV_FILE_NAME "$(basename "$ENV_FILE")"

# The single-reader secrets move into the own file of their service, outside what steward-agent
# mounts, before anything is asked. --check only reports which are still in the shared file.
if $CHECK_ONLY; then
    stale="$(stale_service_secrets "$ENV_FILE")"
    [[ -z "$stale" ]] || die "$ENV_FILE still holds $(tr '\n' ' ' <<<"$stale")which only one service
       reads and steward-agent must not. A real run moves them into each service's own file."
    [[ -n "$(env_value "$ENV_FILE" NORDTAL_SECRETS_DIR)" ]] \
        || warn "NORDTAL_SECRETS_DIR is not set; a real run would write the default"
else
    # A running agent may still read them from the shared file. Without a running agent, the compose.yml of this release deploys.
    agent="$(env_value "$ENV_FILE" COMPOSE_PROJECT_NAME)"
    agent="${agent:-$DEFAULT_PROJECT}-steward-agent-1"
    if grep -qxF "$agent" <<<"$(docker ps --format '{{.Names}}' 2>/dev/null)"; then
        settle_for_agent "$ENV_FILE" "$agent"
    else
        settle_service_secrets "$ENV_FILE"
    fi
fi

# The menu asks for the bunq pair and the root id. COMPOSE_PROFILES has a default and changes in the menu.
for question in "${QUESTIONS[@]}"; do
    case "$question" in
        NORDTAL_STEWARD_BUNQ_*|STEWARD_ROOT_DISCORD_ID|COMPOSE_PROFILES) continue ;;
    esac
    if [[ "$question" == EULA ]]; then
        # A refused licence ends the run.
        ask_question EULA || die "the EULA was not accepted, so there is nothing to deploy.
       Nothing has been changed beyond the environment file this script has been filling in."
        continue
    fi
    ask_question "$question"
done

# The root id is optional. A tree that already has admins never reads it.
ask_question STEWARD_ROOT_DISCORD_ID || true

# bunq is optional. Without it nothing polls for payments. The key lives in the own file of steward-bunq.
if ask_question NORDTAL_STEWARD_BUNQ_API_KEY; then
    ask_question NORDTAL_STEWARD_BUNQ_ACCOUNT_ID
fi

# Warns about the old bunq names, which nothing reads. The script copies no values, because a bunq
# key is bound to the context of the container that registered it.
for stale in NORDTAL_BOT_BUNQ_API_KEY NORDTAL_BOT_BUNQ_ACCOUNT_ID; do
    if [[ -n "$(env_value "$ENV_FILE" "$stale")" ]]; then
        warn "$ENV_FILE still has $stale. Nothing reads it - bunq lives in
       steward-bunq and the names are NORDTAL_STEWARD_BUNQ_API_KEY and
       NORDTAL_STEWARD_BUNQ_ACCOUNT_ID. Delete the old line once the new one is in, and read
       steward-bunq's first log line after the next deploy: it says 'bunq is ON' or 'bunq is
       OFF' in one sentence, and that sentence is the only confirmation there is."
    fi
done

# 2b · The menu
# The menu shows every value, lets the user change one and deploys at the end. It shows secrets as
# three dots. Only a run without arguments on a terminal gets it.
show_menu() {
    local index=1 name value
    printf '\n'
    log "the installation in $INSTALL_DIR"
    printf '        %s\n\n' "$ENV_FILE"
    for name in "${QUESTIONS[@]}"; do
        value="$(value_of "$ENV_FILE" "$name")"
        printf '   %2d  %-34s %s\n' "$index" "$name" \
            "$(shown_value "${QUESTION_KIND[$name]}" "$value")"
        index=$(( index + 1 ))
    done
    printf '\n'
    for name in "${GENERATED[@]}"; do
        value="$(value_of "$ENV_FILE" "$name")"
        printf '       %-34s %s\n' "$name" "$(shown_value secret "$value")"
    done
    printf '\n'
    printf '    d  %s\n' "deploy" >&2
    printf '    q  %s\n' "quit, changing nothing further" >&2
}

if $MENU; then
    while true; do
        show_menu
        printf '\n        > ' >&2
        read -r typed
        case "$(menu_choice "$typed" "${#QUESTIONS[@]}")" in
            quit)
                log "nothing was deployed. Run ./nordtal.sh again when you want to."
                exit 0
                ;;
            deploy)
                break
                ;;
            "edit "*)
                choice="$(menu_choice "$typed" "${#QUESTIONS[@]}")"
                picked="${QUESTIONS[$(( ${choice#edit } - 1 ))]}"
                ask_question "$picked" again || true
                # steward-bunq refuses a bunq key without an account, so the script asks for both.
                if [[ "$picked" == NORDTAL_STEWARD_BUNQ_API_KEY \
                    && -n "$(value_of "$ENV_FILE" NORDTAL_STEWARD_BUNQ_API_KEY)" \
                    && -z "$(value_of "$ENV_FILE" NORDTAL_STEWARD_BUNQ_ACCOUNT_ID)" ]]; then
                    ask_question NORDTAL_STEWARD_BUNQ_ACCOUNT_ID || true
                fi
                ;;
            *)
                warn "that is not one of them: a number from the list, d to deploy, q to stop."
                ;;
        esac
    done
fi

# 3 · The deployment
# Another project name would start a second, empty stack. This section runs before section 4, which
# must not invent a password for an existing database.
STEWARD_NAME="$(env_value "$ENV_FILE" STEWARD_HOST)"
PROJECT="$(env_value "$ENV_FILE" COMPOSE_PROJECT_NAME)"
PROJECT="${PROJECT:-$DEFAULT_PROJECT}"

existing="$(deployed_projects "$(docker volume ls --format '{{.Name}}')" \
    "$(docker ps -a --filter label=com.docker.compose.service=steward-agent --format '{{.Label "com.docker.compose.project"}}')")"
case "$(deployment_kind "$PROJECT" "$existing" "$INSTALL_DIR")" in
    second)
        die "this host already carries a deployment under the project name(s)
       '$(tr '\n' ' ' <<<"$existing")', and this run would deploy '$PROJECT'. That would not fail -
       it would start a SECOND stack beside the one holding the worlds. Set COMPOSE_PROJECT_NAME in
       $ENV_FILE to the existing name, or remove the old deployment first."
        ;;
    adopt)
        ADOPTING=true
        log "adopting the existing deployment '$PROJECT' - its data is kept"
        ;;
    *)
        ADOPTING=false
        log "this is a first deployment; project '$PROJECT'"
        ;;
esac

# Docker no longer mounts the named volumes of an older installation, so a start on empty
# directories needs consent. The script copies and deletes nothing.
if $ADOPTING && ! $CHECK_ONLY; then
    if docker volume inspect "${PROJECT}_postgres-data" >/dev/null 2>&1 \
        && [[ ! -d "$INSTALL_DIR/postgres-data" ]]; then
        warn "this host carries the volumes of an earlier installation (${PROJECT}_postgres-data
       among them), and this deployment does not mount them any more: every volume is a directory
       under $INSTALL_DIR now. Nothing here deletes them - they stay on the disk and
       `docker volume ls` still lists them - but the stack will come up on empty directories: a new
       database, new worlds, no plugin configuration."
        if [[ -t 0 ]]; then
            printf '\n\033[36m[nordtal]\033[0m %s\n' "Continue, and start this deployment on empty directories?" >&2
            printf '        %s\n        > ' "The old volumes are left alone; nothing here copies them across. [y/N]" >&2
            read -r empty_answer
            answer_is_yes "$empty_answer" || die "not continuing. Nothing has been stopped and
       nothing has been deleted."
        else
            die "there is no terminal to ask on, and this would start the stack on empty
       directories beside volumes that hold the previous installation. Run this from a shell.
       Nothing has been changed."
        fi
    fi
fi

# 4 · The generated secrets
# Postgres reads POSTGRES_PASSWORD only on an empty data directory. An adopted host is asked for it.
command -v openssl >/dev/null 2>&1 || die "no openssl on this host, and the generated secrets have to
       come from somewhere. Install it, or put every one GENERATED lists into $ENV_FILE yourself -
       and not the same value twice."

if [[ -z "$(env_value "$ENV_FILE" POSTGRES_PASSWORD)" ]] && $ADOPTING && ! $CHECK_ONLY; then
    ask_for POSTGRES_PASSWORD secret - \
        "The password of the database that is already on this host." \
        "'$PROJECT' already has a database here, so postgres will not take a new password: it reads
        POSTGRES_PASSWORD only when it initialises an empty data directory. Generating one here
        would leave every service unable to log in to a database that is working fine."
else
    set_secret POSTGRES_PASSWORD 24
fi
generate_secrets

# 4b · The Web Push keypair
# `steward generate-vapid-keys` creates the pair and needs neither a database nor a config. A pair
# with one half set is replaced whole. The script pulls the image only when it is absent, so a local build stays.
generate_vapid_keys() {
    local image="${STEWARD_IMAGE:-$IMAGES/steward:$RELEASE}"
    local pub priv output
    pub="$(env_value "$ENV_FILE" STEWARD_WEB_PUSH_PUBLIC_KEY)"
    priv="$(value_of "$ENV_FILE" STEWARD_WEB_PUSH_PRIVATE_KEY)"

    if [[ -n "${pub//[[:space:]]/}" && -n "${priv//[[:space:]]/}" ]]; then
        log "STEWARD_WEB_PUSH_PUBLIC_KEY / _PRIVATE_KEY are already set (left alone)"
        return
    fi
    if $CHECK_ONLY; then
        warn "the Web Push VAPID keypair is not set; a real run would generate one"
        return
    fi
    if [[ -n "${pub//[[:space:]]/}" || -n "${priv//[[:space:]]/}" ]]; then
        warn "exactly one half of the Web Push VAPID keypair is set - WebPushSpec
       reads that as broken, not as 'not configured', so a fresh pair replaces both halves."
    fi

    local fallback="web-push stays unconfigured for now - the subscribe button is simply not
       drawn. Generate a pair once the stack is up with \`docker exec ${PROJECT}-steward-1
       steward generate-vapid-keys\`, paste the two lines into STEWARD_WEB_PUSH_PUBLIC_KEY
       and STEWARD_WEB_PUSH_PRIVATE_KEY in $ENV_FILE, and run this again: it moves the private
       key into steward's own file, and the deploy recreates steward so it reads both."

    if ! docker image inspect "$image" >/dev/null 2>&1; then
        log "pulling $image to mint a Web Push VAPID keypair (no database and no config needed for
       that one command - see steward's Steward.java)"
        if ! docker pull "$image" >/dev/null 2>&1; then
            warn "could not pull $image, so no VAPID keypair was generated. $fallback"
            return
        fi
    fi

    output="$(docker run --rm "$image" generate-vapid-keys 2>/dev/null)" || {
        warn "$image did not answer 'generate-vapid-keys' as expected, so no VAPID keypair was
       generated. $fallback"
        return
    }
    pub="$(sed -n '1p' <<<"$output")"
    priv="$(sed -n '2p' <<<"$output")"
    if [[ -z "$pub" || -z "$priv" ]]; then
        warn "generate-vapid-keys did not print two lines, so no VAPID keypair was generated. $fallback"
        return
    fi

    set_assignment "$ENV_FILE" STEWARD_WEB_PUSH_PUBLIC_KEY "$pub"
    assign "$ENV_FILE" STEWARD_WEB_PUSH_PRIVATE_KEY "$priv"
    log "STEWARD_WEB_PUSH_PUBLIC_KEY / _PRIVATE_KEY generated (a fresh VAPID keypair)"
}
generate_vapid_keys

# 4a · Everything required is set
# This check fires only when the script failed to write a value that it asked for or generated.
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
       not include 'steward'. steward-bunq would never start, and Caddy, the interface's only way
       in, only while 'mc' is selected."
warn_unknown_profiles "$ENV_FILE"

declared_env_file="$(env_value "$ENV_FILE" STEWARD_ENV_FILE)"
[[ "$declared_env_file" == "$ENV_FILE" ]] || die "STEWARD_ENV_FILE inside the file says
       '$declared_env_file', and the file is at '$ENV_FILE'. That value is what compose.yml mounts
       into steward-agent, so the agent would mount a different file than the one this
       deployment is configured from - or nothing at all."

# The derived directory and name must match the file. Otherwise steward-agent mounts the wrong one.
declared_env_dir="$(env_value "$ENV_FILE" STEWARD_ENV_DIR)"
[[ "$declared_env_dir" == "$(dirname "$ENV_FILE")" ]] || die "STEWARD_ENV_DIR inside the file says
       '$declared_env_dir', and the file is at '$ENV_FILE' (directory '$(dirname "$ENV_FILE")').
       That value is what compose.yml mounts into steward-agent as a directory, so a stale
       STEWARD_ENV_DIR would mount the wrong directory entirely."
declared_dir="$(env_value "$ENV_FILE" NORDTAL_DIR)"
[[ "$declared_dir" == "$INSTALL_DIR" ]] || die "NORDTAL_DIR inside the file says '$declared_dir',
       and this run is in '$INSTALL_DIR'. That value is what every volume in compose.yml hangs off,
       so the deployment would read its worlds and its database from somewhere other than the
       directory this run is installing into."
is_absolute "$declared_dir" || die "NORDTAL_DIR inside the file is '$declared_dir', which is not an
       absolute path. steward-agent runs compose from /app inside its own image, so a relative
       bind resolves against a directory that exists only in there."

declared_env_file_name="$(env_value "$ENV_FILE" STEWARD_ENV_FILE_NAME)"
[[ "$declared_env_file_name" == "$(basename "$ENV_FILE")" ]] || die "STEWARD_ENV_FILE_NAME inside
       the file says '$declared_env_file_name', and the file is named '$(basename "$ENV_FILE")'.
       steward-agent looks for exactly this name inside the mounted directory."

log "every required value is set; the interface will answer on $STEWARD_NAME"

# 4c · The data directories
# Docker would create a missing directory as well. Here the run ends before anything starts when a
# directory cannot be created. Existing directories stay as they are.
log "the installation directory: $INSTALL_DIR"
for entry in "${DATA_DIRS[@]}"; do
    directory="$INSTALL_DIR/$entry"
    if [[ -d "$directory" ]]; then
        continue
    fi
    $CHECK_ONLY && { warn "$directory does not exist; a real run would create it"; continue; }
    mkdir -p "$directory" || die "could not create $directory. Every world, every database and
       every backup this deployment has is about to live under $INSTALL_DIR, so a directory that
       cannot be created there is the end of the run."
done

# 5 · The name
# Caddy requests a certificate for STEWARD_HOST on start, so the name must point to this host first.
this_hosts_addresses() {
    if [[ -n "$ADDRESSES_GIVEN" ]]; then
        printf '%s' "$ADDRESSES_GIVEN"
        return
    fi
    # Behind NAT this host has no public address. --address supplies it.
    ip -o addr show scope global 2>/dev/null | awk '{ print $4 }' | cut -d/ -f1
}

resolve() {
    # getent asks the same resolver as everything else on this host.
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

# 5a · Local builds that a pull would replace
# The agent pulls every service before `up` and replaces a local build whose tag the registry has.
# compose.yml comes from the local agent image. Without that image there is nothing to warn about.
COMPOSE_CACHE=""
compose_file() {
    [[ -n "$COMPOSE_CACHE" ]] && { printf '%s' "$COMPOSE_CACHE"; return 0; }
    docker image inspect "$AGENT_IMAGE" >/dev/null 2>&1 || return 1
    local container cache
    cache="$(mktemp -d)/compose.yml"
    container="$(docker create "$AGENT_IMAGE" 2>/dev/null)" || return 1
    if docker cp "$container:/app/compose.yml" "$cache" >/dev/null 2>&1; then
        docker rm -f "$container" >/dev/null 2>&1 || true
        COMPOSE_CACHE="$cache"
        printf '%s' "$COMPOSE_CACHE"
        return 0
    fi
    docker rm -f "$container" >/dev/null 2>&1 || true
    return 1
}

compose_config_json() {
    local file
    file="$(compose_file)" || return 1
    docker compose -f "$file" --env-file "$ENV_FILE" config --format json 2>/dev/null
}

# Gathers the input of at_risk_images: the service images, their local RepoDigests and the digests of
# the registry. Without jq or buildx it warns and skips. Neither is a prerequisite.
images_at_risk() {
    local json pairs image
    command -v jq >/dev/null 2>&1 || {
        warn "no jq here, so §5a cannot tell a locally built image from a pulled one. If anything on"
        warn "this host was shipped with \`compose build\` and no release, \`up\` below replaces it silently."
        return 0
    }
    docker buildx version >/dev/null 2>&1 || {
        warn "no docker buildx here, so §5a cannot ask the registry what it currently serves under each"
        warn "tag. A locally built image on this host's image store may carry a RepoDigest regardless"
        warn "of whether anything was ever pushed - without buildx that alone cannot be told apart from"
        warn "a real one, so §5a stays silent rather than guess. If anything here was shipped with"
        warn "\`compose build\` and no release, \`up\` below replaces it silently."
        return 0
    }
    json="$(compose_config_json)" || {
        warn "no copy of $AGENT_IMAGE on this host yet, so there is no compose.yml of release"
        warn "$RELEASE to read the image list out of. Its images are pinned to $RELEASE, so a local"
        warn "build is at risk only if it carries that tag."
        return 0
    }
    [[ -n "$json" ]] || return 0
    pairs="$(jq -r '.services | to_entries[] | select(.value.image != null and .value.image != "")
            | [.value.image, .key] | @tsv' <<<"$json")"
    [[ -n "$pairs" ]] || return 0

    local local_digests="" registry_digests="" line reg
    while IFS= read -r image; do
        [[ -n "$image" ]] || continue
        docker image inspect "$image" >/dev/null 2>&1 || continue
        line="$(docker image inspect "$image" --format '{{json .RepoDigests}}' 2>/dev/null)"
        [[ -n "$line" ]] || continue
        local_digests+="$image"$'\t'"$line"$'\n'

        # A failed lookup counts as unknown, not as safe. `|| reg=""` keeps set -e from exiting.
        reg="$(docker buildx imagetools inspect "$image" --format '{{.Manifest.Digest}}' 2>/dev/null)" \
            || reg=""
        [[ -n "$reg" ]] && registry_digests+="$image"$'\t'"$reg"$'\n'
    done < <(cut -f1 <<<"$pairs" | sort -u)

    at_risk_images "$pairs" "$local_digests" "$registry_digests"
}

at_risk="$(images_at_risk)"
if [[ -n "$at_risk" ]]; then
    risk="" unknown=""
    while IFS=$'\t' read -r kind image services; do
        case "$kind" in
            RISK)    risk+="$image"$'\t'"$services"$'\n' ;;
            UNKNOWN) unknown+="$image"$'\t'"$services"$'\n' ;;
        esac
    done <<<"$at_risk"

    if [[ -n "$unknown" ]]; then
        warn "the registry could not be asked about these - neither cleared nor flagged, just unknown:"
        while IFS=$'\t' read -r image services; do
            [[ -n "$image" ]] || continue
            warn "  $image  (used by: $services)"
        done <<<"$unknown"
    fi

    if [[ -n "$risk" ]]; then
        warn "these images are on this host, and \`up\` below would replace them with whatever the"
        warn "registry currently serves under the same tag:"
        while IFS=$'\t' read -r image services; do
            [[ -n "$image" ]] || continue
            warn "  $image  (used by: $services)"
        done <<<"$risk"
        if $CHECK_ONLY; then
            warn "a real run would ask before continuing (or refuse without a terminal); --check stops here."
        elif [[ -t 0 ]]; then
            printf '\n\033[36m[nordtal]\033[0m %s\n' "Continue, and let the registry overwrite the images listed above?" >&2
            printf '        %s\n        > ' "Anything shipped on them without a release is lost the moment this pulls. [y/N]" >&2
            read -r keep_local_answer
            answer_is_yes "$keep_local_answer" || die "not continuing. Nothing has been pulled and nothing
       has been stopped. Retag or remove the images above first if they should not be asked about
       again, or answer yes here once you mean to let the registry replace them."
            log "continuing - the images listed above will be replaced by whatever the registry answers with"
        else
            die "the images above are built on this host, and \`up\` would silently replace them with
       whatever the registry currently serves - there is no terminal here to ask first. Run this from
       a shell, or retag/remove the images above if the registry copy is meant to win. Nothing has
       been pulled or stopped."
        fi
    fi
fi

if $CHECK_ONLY; then
    log "--check: everything that can be checked without changing anything is in order."
    exit 0
fi

# 5b · Mojang's assets
# The script asks once per installation and keeps the answer in the env file. On yes, steward-agent
# fetches the client jar of each Minecraft version from Mojang, draws the item icons that the pickers
# of Steward show and deletes the jar. Only the icons stay, in the database. Without an answer, the
# pickers show names only.
if ! mojang_assets_answered "$ENV_FILE"; then
    if [[ -t 0 ]]; then
        printf '\n\033[36m[nordtal]\033[0m %s\n' "May steward-agent download Minecraft's client from Mojang to draw item icons?" >&2
        printf '        %s\n        > ' "The jar is Mojang's and is deleted once drawn; only the icons stay, for signed-in admins. [y/N]" >&2
        read -r mojang_answer
        if answer_is_yes "$mojang_answer"; then
            set_assignment "$ENV_FILE" NORDTAL_MOJANG_ASSETS true
            log "NORDTAL_MOJANG_ASSETS=true - steward-agent draws the icons of each version a server runs"
        else
            set_assignment "$ENV_FILE" NORDTAL_MOJANG_ASSETS false
            log "NORDTAL_MOJANG_ASSETS=false - Steward shows names without icons; set it to true in $ENV_FILE to change that"
        fi
    else
        log "NORDTAL_MOJANG_ASSETS is not in $ENV_FILE and there is no terminal to ask on, so Steward shows names without icons"
    fi
fi

# 6 · steward-agent
# compose.yml is part of the image of steward-agent, which no other service can replace.
if $BUILD_AGENT; then
    # It needs a JDK and a checkout: the installation directory or the directory of this script.
    checkout=""
    if [[ -f "$INSTALL_DIR/gradlew" && -d "$INSTALL_DIR/steward-agent" ]]; then
        checkout="$INSTALL_DIR"
    elif running_from_a_file && from_a_checkout; then
        checkout="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    fi
    [[ -n "$checkout" ]] || die "--build builds $AGENT_IMAGE from a checkout of nordtal/season-2,
       and this run is not in one. Run it from a checkout, or drop --build and let it pull the
       published image."
    log "building $AGENT_IMAGE from $checkout"
    sh "$checkout/gradlew" :steward-agent:build
    docker build -f "$checkout/deploy/jvm/Dockerfile" --build-arg MODULE=steward-agent -t "$AGENT_IMAGE" "$checkout"
else
    log "pulling $AGENT_IMAGE"
    docker pull "$AGENT_IMAGE" || die "could not pull $AGENT_IMAGE. A registry that answers
       'denied' means the same thing as one that answers 'not found': either the release that
       carries this image has not been published yet, or the package is still private. --build
       builds it from this checkout instead, and needs a JDK."
fi

# 7 · The deployment itself
# A healthy agent gets a request, and the update run installs the newest release as the button of
# Steward does: a temporary container takes over, the migration runs while the servers are down,
# and the report lands in the inbox.
agent_healthy=false
grep -qxF "${PROJECT}-steward-agent-1" \
    <<<"$(docker ps --filter health=healthy --format '{{.Names}}')" && agent_healthy=true
if [[ "$(deploy_by "$BUILD_AGENT" "$agent_healthy")" == request ]]; then
    if [[ "$RELEASE_NAMED" == true ]]; then
        warn "NORDTAL_RELEASE names $RELEASE, and an update run always installs the newest release."
    fi
    log "steward-agent runs here, so this deploy is an update run; Steward shows it as well"
    if cmd_update --env-file "$ENV_FILE"; then
        log "done. The interface is at https://$STEWARD_NAME"
        exit 0
    else
        exit $?
    fi
fi

# Otherwise a temporary agent container runs `up`, which pulls every image before it stops anything.
# On a stack whose agent is down, this repairs the stack. The container mounts the env directory, and
# NORDTAL_STEWARD_AGENT_ENV_FILE names the file inside it.
# An explicit NORDTAL_IMAGES is a one-off, and the container receives it. Otherwise the agent reads
# the file itself, because a value in its environment would outrank the file.
images_env=()
[[ -z "${NORDTAL_IMAGES:-}" ]] || images_env=(-e "NORDTAL_IMAGES=$IMAGES")
log "deploying release $RELEASE - this pulls every image before it stops anything"
docker run --rm \
    --name "${PROJECT}-setup" \
    -e "COMPOSE_PROJECT_NAME=$PROJECT" \
    -e "NORDTAL_RELEASE=$RELEASE" \
    ${images_env[@]+"${images_env[@]}"} \
    -e "NORDTAL_STEWARD_AGENT_ENV_FILE=/app/env/$(basename "$ENV_FILE")" \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$(dirname "$ENV_FILE"):/app/env:ro" \
    "$AGENT_IMAGE" up \
    || die "the deployment failed, above. Nothing was stopped if the failure was a pull; if it was
       an up, 'docker compose -p $PROJECT ps' says what is running now."

log "done. The interface is at https://$STEWARD_NAME - the first certificate takes a few seconds."
log "If it does not answer: \`docker logs ${PROJECT}-caddy-1\` says whether Let's Encrypt did."
log "The installation is $INSTALL_DIR - the worlds, the database and the backups are directories"
log "in there, and \`./nordtal.sh\` is how a setting is changed or a new release deployed."
