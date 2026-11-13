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
#   ./nordtal.sh --no-self-update      run this file as it is, without fetching the copy of the release
#
# When steward-agent runs and is healthy, a deploy is an update run like any other: the script
# writes the request and waits for the run to end. An install, or a stack whose agent is down or
# unhealthy, gets the `up` command of the agent instead. That command repairs the stack.
#
# `update` asks steward-agent for a run, as the buttons of Steward do, and waits for it to end:
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

log()  { printf '\033[36m[nordtal]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[nordtal]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m[nordtal]\033[0m %s\n' "$*" >&2; exit 1; }

# The lines of $1 as one line, a space between two, for a field value.
joined() { awk 'NF { printf "%s%s", sep, $0; sep = " " }' <<<"$1"; }

# The question table needs associative arrays (bash 4). macOS ships bash 3.2.
if [[ "${BASH_VERSINFO[0]:-0}" -lt 4 ]]; then
    die "This needs bash 4 or newer and found ${BASH_VERSION:-an unknown version} at ${BASH:-bash}. Install a current bash (brew install bash) and put it first in PATH."
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
        log "default written: key=NORDTAL_SECRETS_DIR value=$dir"
    fi
    secrets_dir_ok "$env_file" "$dir" || die "NORDTAL_SECRETS_DIR in $env_file is $dir, which steward-agent can read. Set it to an absolute path outside $(dirname "$env_file") and run this again."
    [[ "${EUID:-$(id -u)}" -eq 0 ]] || die "Secrets not moved: only root can hand each service's file under $dir to its uid. Run this again as root."
    MOVED_SECRETS=()
    move_service_secrets "$env_file" "$dir" "$keep"
    if (( ${#MOVED_SECRETS[@]} > 0 )) && [[ "$keep" == keep ]]; then
        log "secrets copied, shared file kept: keys=\"${MOVED_SECRETS[*]}\" file=$env_file dir=$dir"
    elif (( ${#MOVED_SECRETS[@]} > 0 )); then
        log "secrets moved: keys=\"${MOVED_SECRETS[*]}\" file=$env_file dir=$dir"
    fi
    local copy
    while IFS= read -r copy; do
        warn "secrets in a file steward-agent reads: file=$copy keys=\"$(joined "$(stale_service_secrets "$copy")")\""
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
    [[ -z "$unknown" ]] || warn "unknown compose profiles ignored: file=$1 profiles=\"$(joined "$unknown")\" known=\"${SELECTABLE_PROFILES[*]}\""
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
    [STEWARD_HOST]="A host name, not a URL - e.g. steward.nordtal.eu. The Cloudflare tunnel of this host sends it to
        http://127.0.0.1:80, where Caddy serves it."
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
        log "value kept: key=$name"
        return 0
    fi

    if $CHECK_ONLY; then
        warn "value missing, not asked: key=$name"
        return 0
    fi

    [[ -t 0 ]] || die "Not deployed: $name is not in $ENV_FILE, and there is no terminal to ask on. Run this from a shell, or pass --from with a file that holds it."

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
            log "licence accepted: key=$name"
            return 0
        fi
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"

        if [[ -z "$value" ]]; then
            case "$kind" in
                optional-plain|optional-secret)
                    log "optional value left empty: key=$name"
                    return 1
                    ;;
                *)
                    warn "An answer is required."
                    continue
                    ;;
            esac
        fi
        if [[ "$check" != "-" ]] && ! "$check" "$value"; then
            # The prompt does not repeat the value, which may be a secret.
            warn "Not a valid answer for $name. Try again."
            continue
        fi
        assign "$ENV_FILE" "$name" "$value"
        log "value written: key=$name file=$(home_of "$ENV_FILE" "$name")"
        return 0
    done
}

default_for() {
    local name="$1" value="$2" existing
    existing="$(env_value "$ENV_FILE" "$name")"
    [[ -n "${existing//[[:space:]]/}" ]] && return 0
    $CHECK_ONLY && { warn "default missing, not written: key=$name"; return 0; }
    set_assignment "$ENV_FILE" "$name" "$value"
    log "default written: key=$name value=$value"
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
        log "value kept: key=$name"
        return
    fi
    if $CHECK_ONLY; then
        warn "secret missing, not generated: key=$name"
        return
    fi
    value="$(openssl rand -hex "$bytes")"
    assign "$ENV_FILE" "$name" "$value"
    log "secret generated: key=$name bytes=$bytes"
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
                        [[ -n "$UPDATE_SCOPE" ]] || die "update --down needs a service, as in: ./nordtal.sh update --down smp"
                        shift 2 ;;
            # A bare `--start` releases every hold. A flag after it is not a service.
            --start)    UPDATE_KIND=START;   kinds=$(( kinds + 1 ))
                        if [[ -n "${2:-}" && "${2:0:1}" != "-" ]]; then
                            UPDATE_SCOPE="$2"; shift 2
                        else
                            shift
                        fi ;;
            --in)       UPDATE_DELAY="${2:-}"; shift 2 || die "update --in needs a number of minutes, as in: --in 10" ;;
            --no-wait)  UPDATE_WAIT=false; shift ;;
            --timeout)  UPDATE_TIMEOUT="${2:-}"; shift 2 || die "update --timeout needs a number of seconds, as in: --timeout 3600" ;;
            --env-file) UPDATE_ENV_FILE="${2:-}"; shift 2 || die "update --env-file needs a path, as in: --env-file /etc/nordtal/season-2.env" ;;
            --replace-local) UPDATE_REPLACE_LOCAL=true; shift ;;
            *)          die "Unknown argument to update: $1. ./nordtal.sh --help lists them." ;;
        esac
    done

    (( kinds <= 1 )) || die "update takes one of --restart, --backup, --down or --start. Name one."
    [[ "$UPDATE_REPLACE_LOCAL" == false || "$UPDATE_KIND" == UPDATE ]] \
        || die "update --replace-local goes with a plain update only. Run it without --restart, --backup, --down or --start."
    [[ "$UPDATE_DELAY" =~ ^[0-9]+$ ]] || die "update --in takes whole minutes, not $UPDATE_DELAY."
    (( UPDATE_DELAY <= 1440 )) || die "update --in takes at most 1440 minutes (a day)."
    [[ "$UPDATE_TIMEOUT" =~ ^[0-9]+$ ]] || die "update --timeout takes whole seconds, not $UPDATE_TIMEOUT."
    [[ -n "$UPDATE_ENV_FILE" ]] || die "update --env-file needs a path, as in: --env-file /etc/nordtal/season-2.env"
    if [[ -n "$UPDATE_SCOPE" ]]; then
        update_scope_ok "$UPDATE_SCOPE" \
            || die "$UPDATE_SCOPE is not a service name. Use lowercase letters, digits and dashes, with commas between several."
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
        warn "newest release unknown, file not renewed: file=$file"
        return 1
    fi
    candidate="$(mktemp "${TMPDIR:-/tmp}/nordtal.sh.XXXXXX")"
    if ! fetch_url "$(self_url_for "$release")" "$candidate" || ! looks_like_this_script "$candidate"; then
        rm -f "$candidate"
        warn "release copy not fetched, file not renewed: file=$file release=$release"
        return 1
    fi
    if [[ "$(fingerprint "$candidate")" == "$(fingerprint "$file")" ]]; then
        rm -f "$candidate"
        return 1
    fi
    # A rename leaves no half-written script after an interrupted copy.
    if ! install -m 755 "$candidate" "$file.new" || ! mv -f "$file.new" "$file"; then
        rm -f "$candidate" "$file.new"
        warn "file not writable, not renewed: file=$file"
        return 1
    fi
    rm -f "$candidate"
    log "file renewed: file=$file release=$release fingerprint=$(fingerprint "$file")"
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
        warn "agent release unknown, file not renewed: container=$container file=$file"
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

    log "run watched: request=$id"
    while :; do
        # The status and a tab before the stage, then one line per step. No output means the row is gone.
        code=0
        answer="$(docker exec "$container" steward-agent status "$id" 2>/dev/null)" || code=$?
        steps="$(sed '1d' <<<"$answer")"
        answer="$(sed -n '1p' <<<"$answer")"
        status="${answer%%$'\t'*}"

        if [[ -z "$status" ]]; then
            (( silent > 0 )) || warn "steward-agent not answering, asking again: request=$id seconds=${UPDATE_GAP}"
            (( silent += UPDATE_POLL ))
            (( silent <= UPDATE_GAP )) \
                || die "Run status unknown: request $id is gone from steward_inbox, or steward-agent has not answered for ${UPDATE_GAP}s. Steward shows the run under /operations."
        else
            (( silent == 0 )) || log "steward-agent answering again: request=$id"
            silent=0
        fi
        if [[ -n "$status" && "$status" != "$said" ]]; then
            log "request status changed: request=$id status=$status"
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
            warn "Watch ended: request $id is still $status after ${UPDATE_TIMEOUT}s, and the run goes on. Steward shows it under /operations."
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
        || die "No deployment to update: $UPDATE_ENV_FILE is missing. If the environment file lives elsewhere, name it with --env-file PATH."

    warn_unknown_profiles "$UPDATE_ENV_FILE"

    # One name only, because the file also holds secrets.
    local project container
    project="$(env_value "$UPDATE_ENV_FILE" COMPOSE_PROJECT_NAME)"
    project="${project:-$DEFAULT_PROJECT}"
    container="${project}-steward-agent-1"

    # `docker ps` and never `docker inspect` on a container with secrets. A here-string, not a pipe.
    grep -qxF "$container" <<<"$(docker ps --format '{{.Names}}')" \
        || die "No run requested: $container is not running. ./nordtal.sh --deploy brings the stack up again."

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
        die "Request $id stopped before replacing builds made on this host, and nothing was stopped. To let a run replace them: ./nordtal.sh update --replace-local"
    fi
    printf '\n\033[36m[nordtal]\033[0m %s\n' "Ask again, and let the run replace the builds named above?" >&2
    printf '        %s\n        > ' "Whatever they carry that no release has is lost. [y/N]" >&2
    local replace_answer
    read -r replace_answer
    answer_is_yes "$replace_answer" || die "Not asked again: nothing was stopped, and the local builds stay."
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
        || die "steward-agent refused the request. Its reason is above."
    [[ "$id" =~ ^[0-9]+$ ]] || die "steward-agent answered '$id' instead of a request id. docker logs $container says why."

    local when=""
    (( UPDATE_DELAY > 0 )) && when=" delay=${UPDATE_DELAY}m"
    log "run requested: request=$id kind=$UPDATE_KIND${UPDATE_SCOPE:+ services=$UPDATE_SCOPE}$when" >&2
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
        --check)            CHECK_ONLY=true; MENU=false; shift ;;
        --build)            BUILD_AGENT=true; MENU=false; shift ;;
        --deploy)           MENU=false; shift ;;
        --no-self-update)   SELF_UPDATE=false; shift ;;
        -h|--help)          usage; exit 0 ;;
        *)                  die "Unknown argument: $1. ./nordtal.sh --help lists them." ;;
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
    [[ -n "$RELEASE" ]] || die "No release to install: GitHub did not name the newest release ($RELEASES_API). Run this again once it answers, or name one: NORDTAL_RELEASE=0.11.0 ./nordtal.sh"
    RELEASE_NAMED=false
}
RELEASE=""
RELEASE_NAMED=false
resolve_release
# For the renewed copy. It takes GitHub's answer for no one's choice and does not ask again.
export NORDTAL_RELEASE="$RELEASE" NORDTAL_RELEASE_NAMED="$RELEASE_NAMED"
SELF_URL="$(self_url_for "$RELEASE")"
log "release resolved: release=$RELEASE"

ORIGIN="${NORDTAL_SH_ORIGIN:-}"
SELF_TEMP=""

renew_self() {
    if ! $SELF_UPDATE; then
        ORIGIN="${ORIGIN:-local copy, --no-self-update}"
        return 0
    fi
    if from_a_checkout; then
        ORIGIN="checkout"
        return 0
    fi

    local candidate
    candidate="$(mktemp "${TMPDIR:-/tmp}/nordtal.sh.XXXXXX")"
    if ! fetch_url "$SELF_URL" "$candidate" || ! looks_like_this_script "$candidate"; then
        rm -f "$candidate"
        # Without a network the run continues, unless the script is piped: then there is no copy to run.
        running_from_a_file || die "Nothing was changed: this script was piped into bash, and $SELF_URL could not be fetched. Download nordtal.sh by hand and run it in the installation directory."
        warn "release copy not fetched, local copy used: url=$SELF_URL"
        ORIGIN="local copy, github unreachable"
        return 0
    fi

    if running_from_a_file \
        && [[ "$(fingerprint "$candidate")" == "$(fingerprint "${BASH_SOURCE[0]}")" ]]; then
        rm -f "$candidate"
        ORIGIN="release copy"
        return 0
    fi

    # The copy runs from a temporary file and installs itself after the directory question.
    chmod 755 "$candidate"
    export NORDTAL_SH_ORIGIN="$SELF_URL"
    export NORDTAL_SH_TEMP="$candidate"
    if running_from_a_file; then
        log "release copy differs, running it: release=$RELEASE from=$(fingerprint "${BASH_SOURCE[0]}") to=$(fingerprint "$candidate")"
    else
        log "release copy fetched, running it: release=$RELEASE"
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
    log "script running: file=$SELF_NAME fingerprint=$(fingerprint "${BASH_SOURCE[0]}") origin=\"${ORIGIN:-local copy}\""
else
    log "script running from pipe: origin=\"${ORIGIN:-pipe}\""
fi

install_self() {
    running_from_a_file || return 0
    local source; source="$(self_path)"
    if [[ "$source" != "$INSTALLED" ]]; then
        install -m 755 "$source" "$INSTALLED" \
            || die "Could not write $INSTALLED. Make the installation directory writable and run this again."
        log "script installed: file=$INSTALLED"
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
    die "$ENV_FILE belongs to the installation in $declared_dir, not to $INSTALL_DIR, and nothing was changed. Run ./nordtal.sh there, or name an environment file of its own with --env-file."
fi

if [[ -z "${declared_dir//[[:space:]]/}" ]]; then
    $CHECK_ONLY || {
        [[ -t 0 ]] || die "Not installed: $ENV_FILE names no installation here, and there is no terminal to ask on. Run this from a shell. Nothing was changed."
        printf '\n\033[36m[nordtal]\033[0m %s\n' "Install nordtal season 2 into this directory?" >&2
        printf '        %s\n' "$INSTALL_DIR" >&2
        printf '        %s\n' "Everything this deployment keeps becomes a directory in there: four worlds," >&2
        printf '        %s\n' "the database, every plugin's configuration, and the nightly backups. The" >&2
        printf '        %s\n' "secrets do not - they go to $ENV_FILE, mode 600." >&2
        printf '        %s\n        > ' "[y/N]" >&2
        read -r here_answer
        answer_is_yes "$here_answer" || die "Not installed, and nothing was changed. Run this again from the directory the installation should live in."
    }
fi

$CHECK_ONLY || install_self

command -v docker >/dev/null 2>&1 \
    || die "No docker on this host. Install Docker and run this again."
docker compose version >/dev/null 2>&1 \
    || die "docker compose is missing. Install the Docker Compose plugin and run this again."
docker info >/dev/null 2>&1 \
    || die "This user cannot reach the docker daemon. Run this as root or as a member of the docker group."
log "docker found: version=$(docker version --format '{{.Server.Version}}') compose=$(docker compose version --short)"

# 2 · The environment file
# It holds every secret. Compose interpolates it and steward-agent mounts it read-only.
is_absolute "$ENV_FILE" || die "--env-file needs an absolute path, and $ENV_FILE is not one. Pass the full path."

if [[ ! -f "$ENV_FILE" ]]; then
    if [[ -z "$FROM_FILE" && -f "$INSTALL_DIR/.env" ]]; then
        FROM_FILE="$INSTALL_DIR/.env"
    fi
    if [[ -n "$FROM_FILE" ]]; then
        [[ -f "$FROM_FILE" ]] || die "There is no $FROM_FILE. Name an existing file with --from."
        $CHECK_ONLY || {
            install -D -m 600 "$FROM_FILE" "$ENV_FILE"
            # The old file stays as the way back.
            cmp -s "$FROM_FILE" "$ENV_FILE" || die "The copy at $ENV_FILE does not match $FROM_FILE, and nothing further was done. Check the disk and run this again."
            log "environment file copied: from=$FROM_FILE to=$ENV_FILE"
        }
    else
        # A new host starts with an empty file, which the questions below fill.
        $CHECK_ONLY || {
            install -D -m 600 /dev/null "$ENV_FILE"
            log "environment file created: file=$ENV_FILE"
        }
    fi
else
    $CHECK_ONLY || {
        chmod 600 "$ENV_FILE"
        log "environment file found: file=$ENV_FILE"
    }
fi
[[ -f "$ENV_FILE" ]] || die "No environment file at $ENV_FILE, and --check creates none. Run without --check, or put the file there."
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
    [[ -z "$stale" ]] || die "--check stopped: $ENV_FILE still holds $(joined "$stale"). Run without --check to move them into each service's own file."
    [[ -n "$(env_value "$ENV_FILE" NORDTAL_SECRETS_DIR)" ]] \
        || warn "default missing, not written: key=NORDTAL_SECRETS_DIR"
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
        ask_question EULA || die "Not deployed: the Minecraft EULA was not accepted. Run this again to accept it."
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
        warn "outdated bunq key ignored: file=$ENV_FILE key=$stale to=${stale/NORDTAL_BOT_/NORDTAL_STEWARD_}"
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
    printf '    q  %s\n' "quit without changes" >&2
}

if $MENU; then
    while true; do
        show_menu
        printf '\n        > ' >&2
        read -r typed
        case "$(menu_choice "$typed" "${#QUESTIONS[@]}")" in
            quit)
                log "menu left, nothing deployed"
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
                warn "Type a number from the list, d or q."
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
        die "Not deployed: this host already runs the deployment $(joined "$existing"), and this run would start a second one, $PROJECT. Set COMPOSE_PROJECT_NAME in $ENV_FILE to the existing name, or remove the old deployment first."
        ;;
    adopt)
        ADOPTING=true
        log "existing deployment adopted, data kept: project=$PROJECT"
        ;;
    *)
        ADOPTING=false
        log "first deployment started: project=$PROJECT"
        ;;
esac

# Docker no longer mounts the named volumes of an older installation, so a start on empty
# directories needs consent. The script copies and deletes nothing.
if $ADOPTING && ! $CHECK_ONLY; then
    if docker volume inspect "${PROJECT}_postgres-data" >/dev/null 2>&1 \
        && [[ ! -d "$INSTALL_DIR/postgres-data" ]]; then
        warn "old volumes found, not mounted: volume=${PROJECT}_postgres-data dir=$INSTALL_DIR"
        if [[ -t 0 ]]; then
            printf '\n\033[36m[nordtal]\033[0m %s\n' "Continue, and start this deployment on empty directories?" >&2
            printf '        %s\n        > ' "The old volumes are left alone; nothing here copies them across. [y/N]" >&2
            read -r empty_answer
            answer_is_yes "$empty_answer" || die "Not continued: nothing was stopped or deleted."
        else
            die "Not deployed: the stack would start on empty directories beside the old volumes, and there is no terminal to ask on. Run this from a shell. Nothing was changed."
        fi
    fi
fi

# 4 · The generated secrets
# Postgres reads POSTGRES_PASSWORD only on an empty data directory. An adopted host is asked for it.
command -v openssl >/dev/null 2>&1 || die "No openssl on this host. Install it, or write every secret that GENERATED lists into $ENV_FILE yourself, each one different."

if [[ -z "$(env_value "$ENV_FILE" POSTGRES_PASSWORD)" ]] && $ADOPTING && ! $CHECK_ONLY; then
    ask_for POSTGRES_PASSWORD secret - \
        "The password of the database that is already on this host." \
        "'$PROJECT' already has a database here, and it keeps the password it was created with."
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
        log "vapid keypair kept"
        return
    fi
    if $CHECK_ONLY; then
        warn "vapid keypair missing, not generated"
        return
    fi
    if [[ -n "${pub//[[:space:]]/}" || -n "${priv//[[:space:]]/}" ]]; then
        warn "vapid keypair incomplete"
    fi

    if ! docker image inspect "$image" >/dev/null 2>&1; then
        log "vapid image pull started: image=$image"
        if ! docker pull "$image" >/dev/null 2>&1; then
            warn "vapid keypair not generated, web push off: image=$image cause=pull failed"
            return
        fi
    fi

    output="$(docker run --rm "$image" generate-vapid-keys 2>/dev/null)" || {
        warn "vapid keypair not generated, web push off: image=$image cause=generate-vapid-keys failed"
        return
    }
    pub="$(sed -n '1p' <<<"$output")"
    priv="$(sed -n '2p' <<<"$output")"
    if [[ -z "$pub" || -z "$priv" ]]; then
        warn "vapid keypair not generated, web push off: image=$image cause=generate-vapid-keys printed no keypair"
        return
    fi

    set_assignment "$ENV_FILE" STEWARD_WEB_PUSH_PUBLIC_KEY "$pub"
    assign "$ENV_FILE" STEWARD_WEB_PUSH_PRIVATE_KEY "$priv"
    log "vapid keypair generated"
}
generate_vapid_keys

# 4a · Everything required is set
# This check fires only when the script failed to write a value that it asked for or generated.
missing="$(env_missing "$ENV_FILE" "${REQUIRED[@]}")"
if [[ -n "$missing" ]]; then
    warn "required values missing: file=$ENV_FILE keys=\"$(joined "$missing")\""
    $CHECK_ONLY && die "--check stopped: the values above are missing. Run without --check to ask for or generate them."
    die "Not deployed: the values above are missing from $ENV_FILE. Set them and run this again."
fi

leftovers="$(env_replace_me_lines "$ENV_FILE")"
if [[ -n "$leftovers" ]]; then
    warn "REPLACE_ME left in file: file=$ENV_FILE lines=\"$(joined "$leftovers")\""
    die "Not deployed: $ENV_FILE still holds REPLACE_ME on the lines above. Replace each with its value and run this again."
fi

profiles="$(env_value "$ENV_FILE" COMPOSE_PROFILES)"
profiles_include "$profiles" steward || die "COMPOSE_PROFILES in $ENV_FILE is $profiles and lacks steward. Add steward and run this again."
warn_unknown_profiles "$ENV_FILE"

declared_env_file="$(env_value "$ENV_FILE" STEWARD_ENV_FILE)"
[[ "$declared_env_file" == "$ENV_FILE" ]] || die "STEWARD_ENV_FILE in $ENV_FILE says $declared_env_file. Set it to $ENV_FILE and run this again."

# The derived directory and name must match the file. Otherwise steward-agent mounts the wrong one.
declared_env_dir="$(env_value "$ENV_FILE" STEWARD_ENV_DIR)"
[[ "$declared_env_dir" == "$(dirname "$ENV_FILE")" ]] || die "STEWARD_ENV_DIR in $ENV_FILE says $declared_env_dir. Set it to $(dirname "$ENV_FILE") and run this again."
declared_dir="$(env_value "$ENV_FILE" NORDTAL_DIR)"
[[ "$declared_dir" == "$INSTALL_DIR" ]] || die "NORDTAL_DIR in $ENV_FILE says $declared_dir, but this run is in $INSTALL_DIR. Run ./nordtal.sh in $declared_dir, or correct NORDTAL_DIR."
is_absolute "$declared_dir" || die "NORDTAL_DIR in $ENV_FILE is $declared_dir, which is not an absolute path. Set it to the full path and run this again."

declared_env_file_name="$(env_value "$ENV_FILE" STEWARD_ENV_FILE_NAME)"
[[ "$declared_env_file_name" == "$(basename "$ENV_FILE")" ]] || die "STEWARD_ENV_FILE_NAME in $ENV_FILE says $declared_env_file_name. Set it to $(basename "$ENV_FILE") and run this again."

log "required values set: url=https://$STEWARD_NAME"

# 4c · The data directories
# Docker would create a missing directory as well. Here the run ends before anything starts when a
# directory cannot be created. Existing directories stay as they are.
log "installation directory used: dir=$INSTALL_DIR"
for entry in "${DATA_DIRS[@]}"; do
    directory="$INSTALL_DIR/$entry"
    if [[ -d "$directory" ]]; then
        continue
    fi
    $CHECK_ONLY && { warn "directory missing, not created: dir=$directory"; continue; }
    mkdir -p "$directory" || die "Could not create $directory. Make $INSTALL_DIR writable and run this again."
done

# 5 · Local builds that a pull would replace
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
        warn "no jq, local images not checked"
        return 0
    }
    docker buildx version >/dev/null 2>&1 || {
        warn "no docker buildx, local images not checked"
        return 0
    }
    json="$(compose_config_json)" || {
        warn "agent image missing, local images not checked: image=$AGENT_IMAGE"
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
        while IFS=$'\t' read -r image services; do
            [[ -n "$image" ]] || continue
            warn "registry not answering, image state unknown: image=$image services=$services"
        done <<<"$unknown"
    fi

    if [[ -n "$risk" ]]; then
        while IFS=$'\t' read -r image services; do
            [[ -n "$image" ]] || continue
            warn "local image differs from registry: image=$image services=$services"
        done <<<"$risk"
        if $CHECK_ONLY; then
            warn "local images question skipped: cause=--check"
        elif [[ -t 0 ]]; then
            printf '\n\033[36m[nordtal]\033[0m %s\n' "Continue, and let the registry overwrite the images listed above?" >&2
            printf '        %s\n        > ' "Anything shipped on them without a release is lost the moment this pulls. [y/N]" >&2
            read -r keep_local_answer
            answer_is_yes "$keep_local_answer" || die "Not continued: nothing was pulled or stopped. Retag or remove the images above, or answer yes to let the registry replace them."
            log "local image replacement accepted"
        else
            die "Not deployed: a pull would replace the local images above, and there is no terminal to ask on. Run this from a shell, or retag or remove them. Nothing was pulled or stopped."
        fi
    fi
fi

if $CHECK_ONLY; then
    log "check passed, nothing changed"
    exit 0
fi

# 6 · Mojang's assets
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
            log "mojang assets allowed: key=NORDTAL_MOJANG_ASSETS value=true"
        else
            set_assignment "$ENV_FILE" NORDTAL_MOJANG_ASSETS false
            log "mojang assets declined: key=NORDTAL_MOJANG_ASSETS value=false"
        fi
    else
        log "mojang assets not asked, no terminal: key=NORDTAL_MOJANG_ASSETS file=$ENV_FILE"
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
    [[ -n "$checkout" ]] || die "--build needs a checkout of nordtal/season-2, and this run is not in one. Run it from a checkout, or drop --build to pull the published image."
    log "agent image build started: image=$AGENT_IMAGE dir=$checkout"
    sh "$checkout/gradlew" :steward-agent:build
    docker build -f "$checkout/deploy/jvm/Dockerfile" --build-arg MODULE=steward-agent -t "$AGENT_IMAGE" "$checkout"
else
    log "agent image pull started: image=$AGENT_IMAGE"
    docker pull "$AGENT_IMAGE" || die "Could not pull $AGENT_IMAGE: release $RELEASE is not published yet, or its package is private. Publish it, or build it here with --build (needs a JDK)."
fi

# 7 · The deployment itself
# A healthy agent gets a request, and the update run installs the newest release as the button of
# Steward does: a temporary container takes over, the migration runs while the servers are down,
# and each step lands in the run's rows.
agent_healthy=false
grep -qxF "${PROJECT}-steward-agent-1" \
    <<<"$(docker ps --filter health=healthy --format '{{.Names}}')" && agent_healthy=true
if [[ "$(deploy_by "$BUILD_AGENT" "$agent_healthy")" == request ]]; then
    if [[ "$RELEASE_NAMED" == true ]]; then
        warn "NORDTAL_RELEASE ignored, update run installs the newest release: release=$RELEASE"
    fi
    log "steward-agent running, deploy sent as update run"
    if cmd_update --env-file "$ENV_FILE"; then
        log "deployed: url=https://$STEWARD_NAME"
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
log "deploy started: release=$RELEASE"
docker run --rm \
    --name "${PROJECT}-setup" \
    -e "COMPOSE_PROJECT_NAME=$PROJECT" \
    -e "NORDTAL_RELEASE=$RELEASE" \
    ${images_env[@]+"${images_env[@]}"} \
    -e "NORDTAL_STEWARD_AGENT_ENV_FILE=/app/env/$(basename "$ENV_FILE")" \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$(dirname "$ENV_FILE"):/app/env:ro" \
    "$AGENT_IMAGE" up \
    || die "Not deployed: the step above failed. docker compose -p $PROJECT ps shows what runs."

log "deployed: release=$RELEASE url=https://$STEWARD_NAME dir=$INSTALL_DIR"
