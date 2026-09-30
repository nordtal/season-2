#!/usr/bin/env bash
#
# Installs the season 2 stack into the current directory, on a host with nothing but Docker.
#
#   curl -fsSL https://raw.githubusercontent.com/nordtal/season-2/main/deploy/nordtal.sh | bash
#
# It stays there as ./nordtal.sh, which is how a setting is changed afterwards:
#
#   ./nordtal.sh                       the menu: what is set, change one, then deploy
#   ./nordtal.sh --deploy              no menu; ask only for what is missing, then deploy
#   ./nordtal.sh --build               build the deployer from a checkout first (needs a JDK)
#   ./nordtal.sh --check               every check, and stop before anything is changed
#   ./nordtal.sh --from PATH           take the answers from this file instead of asking
#   ./nordtal.sh --env-file PATH       where the environment file belongs on this host
#                                      (default: /etc/nordtal/season-2.env)
#   ./nordtal.sh --address IP          this host's public address, for a host behind NAT
#   ./nordtal.sh --no-self-update      run this file as it is, without asking GitHub for a newer one
#
# `update` only writes an `update_request` row and waits for the worker's report:
#
#   ./nordtal.sh update                the whole network: install what is new, restart what needs it
#   ./nordtal.sh update --restart      restart everything, install nothing
#   ./nordtal.sh update --backup       one backup run, now
#   ./nordtal.sh update --down smp     stop one service and hold it down
#   ./nordtal.sh update --start [svc]  release a hold: everything, or one service
#   ./nordtal.sh update --in 10        let the countdown run for ten minutes first
#   ./nordtal.sh update --no-wait      print the request id and return, instead of waiting
#
# Every run first fetches the current script and runs that, saying which version and where from;
# without a network it runs the local copy. Run it after every release: a new compose.yml arrives
# only inside a new steward-deployer image, and this script is what renews that container.
#
# It asks for what only a person knows, generates the other secrets, and writes them to
# /etc/nordtal/season-2.env with mode 600, outside the installation directory. It never prints a
# secret or puts one on a command line.
set -Eeuo pipefail

# The installation is the current directory; under `curl ... | bash` there is no BASH_SOURCE[0].
INSTALL_DIR="$PWD"
SELF_NAME="nordtal.sh"
INSTALLED="$INSTALL_DIR/$SELF_NAME"
SELF_URL="${NORDTAL_SH_URL:-https://raw.githubusercontent.com/nordtal/season-2/main/deploy/nordtal.sh}"

DEFAULT_ENV_FILE="/etc/nordtal/season-2.env"
DEPLOYER_IMAGE="ghcr.io/nordtal/steward-deployer:latest"
DEFAULT_PROJECT="nordtal-s2"

# How long to give GitHub before running what is already here.
SELF_UPDATE_TIMEOUT=10

# How long between two attempts at the name, and how often to say so out loud while nothing changes.
DNS_INTERVAL=15
DNS_HEARTBEAT=8

log()  { printf '\033[36m[nordtal]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[nordtal]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m[nordtal]\033[0m %s\n' "$*" >&2; exit 1; }

# The question table needs associative arrays (bash 4); macOS ships bash 3.2, which fails unreadably.
if [[ "${BASH_VERSINFO[0]:-0}" -lt 4 ]]; then
    die "this needs bash 4 or newer and found ${BASH_VERSION:-an unknown version} at ${BASH:-bash}.
       macOS ships bash 3.2 and cannot be talked out of it. Install a current one and make sure it
       comes first in PATH:  brew install bash"
fi

# What the environment file must hold before the stack starts; absent, empty or REPLACE_ME is missing.
# Roles and channels are optional: an unset one means that feature is not served.
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
    POSTGRES_STEWARD_UI_PASSWORD
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
    NORDTAL_DIR
    STEWARD_UI_DISCORD_CLIENT_ID
    STEWARD_UI_DISCORD_CLIENT_SECRET
)

# One directory per compose.yml volume under NORDTAL_DIR, named like the volume without its prefix.
# A copy of compose.yml, which is not on the host yet. The optional owner is for a service that does
# not run as root: steward-ui, whose bind mount Docker would create as root.
DATA_DIRS=(
    "postgres-data"
    "steward-backups"
    "bot-config"
    "bot-jar"
    "steward-worker-config"
    "steward-worker-jar"
    "steward-ui-config:10001:10001"
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

# The name and the owner of one of those entries.
dir_name()  { printf '%s' "${1%%:*}"; }
dir_owner() { if [[ "$1" == *:* ]]; then printf '%s' "${1#*:}"; fi; }

# Decisions without side effects, which deploy/nordtal-test.sh drives without Docker or a network.

# One value from an environment file, read rather than sourced, which would execute it.
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

# Writes `name=value` into the file, replacing an existing assignment (also with leading whitespace
# or `export`) or appending one. The value passes through awk's environment, never a command line,
# into a mode 600 file renamed over the destination.
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

# The line numbers of every REPLACE_ME left outside comments; never the lines, which hold secrets.
env_replace_me_lines() {
    local file="$1"
    [[ -f "$file" ]] || return 0
    grep -n 'REPLACE_ME' "$file" | grep -v '^[0-9]*:[[:space:]]*#' | cut -d: -f1 || true
}

# COMPOSE_PROFILES must include `steward`, or the stack comes up without an interface.
profiles_include() {
    local profiles="$1" wanted="$2" profile
    local IFS=','
    for profile in $profiles; do
        [[ "${profile//[[:space:]]/}" == "$wanted" ]] && return 0
    done
    return 1
}

is_absolute() { [[ "$1" == /* ]]; }

# Answer shapes, checked at the prompt; they refuse only what cannot be right.

# A Discord snowflake: digits only, with a wide lower bound for older ids.
looks_like_snowflake() { [[ "$1" =~ ^[0-9]{15,21}$ ]]; }

# A host name, not a URL: letters, digits, hyphens and at least one dot.
looks_like_host() {
    [[ "$1" =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$ ]]
}

# An e-mail address: one @, a dot in the domain, no whitespace. Let's Encrypt rejects a bad one.
looks_like_email() { [[ "$1" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; }

# A host and a port; the port is required, since a transfer packet does no SRV lookup.
looks_like_public_address() {
    local host="${1%:*}" port="${1##*:}"
    [[ "$1" == *:* ]] || return 1
    looks_like_host "$host" || return 1
    [[ "$port" =~ ^[0-9]{1,5}$ ]] && (( port >= 1 && port <= 65535 ))
}

# A profile selection: names and commas. An unknown name is harmless and allowed.
looks_like_profiles() {
    [[ "$1" =~ ^[[:space:]]*[a-z0-9-]+([[:space:]]*,[[:space:]]*[a-z0-9-]+)*[[:space:]]*$ ]]
}

# Yes, in English spellings only; anything else, silence included, is no.
answer_is_yes() {
    case "${1,,}" in
        y|yes|true) return 0 ;;
        *) return 1 ;;
    esac
}

# The questions, one table for both the first run and the menu, in the order the menu shows.
QUESTIONS=(
    STEWARD_HOST
    STEWARD_ACME_EMAIL
    NETWORK_PUBLIC_ADDRESS
    EULA
    NORDTAL_BOT_TOKEN
    STEWARD_UI_DISCORD_CLIENT_ID
    STEWARD_UI_DISCORD_CLIENT_SECRET
    NORDTAL_ACCESS_GUILD_ID
    NORDTAL_ACCESS_ROLES_ADMIN
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
    [STEWARD_UI_DISCORD_CLIENT_ID]=plain
    [STEWARD_UI_DISCORD_CLIENT_SECRET]=secret
    [NORDTAL_ACCESS_GUILD_ID]=plain
    [NORDTAL_ACCESS_ROLES_ADMIN]=plain
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
    [STEWARD_UI_DISCORD_CLIENT_ID]=looks_like_snowflake
    [STEWARD_UI_DISCORD_CLIENT_SECRET]=-
    [NORDTAL_ACCESS_GUILD_ID]=looks_like_snowflake
    [NORDTAL_ACCESS_ROLES_ADMIN]=looks_like_snowflake
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
    [STEWARD_UI_DISCORD_CLIENT_ID]="The Discord application's Client ID - this is what the interface signs you in with."
    [STEWARD_UI_DISCORD_CLIENT_SECRET]="The same application's Client Secret."
    [NORDTAL_ACCESS_GUILD_ID]="The id of the guild this deployment belongs to."
    [NORDTAL_ACCESS_ROLES_ADMIN]="The id of the admin role."
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
    [STEWARD_UI_DISCORD_CLIENT_ID]="Same application, OAuth2 page. Its redirect URI has to be
        https://<the name above>/auth/callback, or the sign-in comes back with an error from
        Discord rather than from here."
    [STEWARD_UI_DISCORD_CLIENT_SECRET]="OAuth2 -> Reset Secret. Discord shows it once; if you have lost it, reset it and paste the new
        one - nothing else in this deployment holds a copy."
    [NORDTAL_ACCESS_GUILD_ID]="Discord -> Developer Mode -> right-click the server -> Copy Server ID."
    [NORDTAL_ACCESS_ROLES_ADMIN]="This is the one role that is not optional: it is what the bot mirrors into the database, and it
        is what decides who may sign in to the interface at all. Right-click the role -> Copy Role ID,
        and make sure your own account has it."
    [NORDTAL_STEWARD_BUNQ_API_KEY]="Without it the whole stack starts and runs; steward-worker just never polls bunq, and access
        can only be granted by hand - through the interface or through /access in Discord."
    [NORDTAL_STEWARD_BUNQ_ACCOUNT_ID]="A number. steward-worker refuses to start with a key and no account, because a poll loop
        with nowhere to look would be a silent one."
    [COMPOSE_PROFILES]="A comma-separated list. The production selection is db,bot,mc,backup,steward and there is
        rarely a reason to type anything else; 'steward' has to be in it or the interface is defined
        and never started."
)

# The generated secrets: listed under the menu, never editable, since a new POSTGRES_PASSWORD breaks
# an existing database.
GENERATED=(
    POSTGRES_PASSWORD
    POSTGRES_DISCORD_BOT_PASSWORD
    POSTGRES_PROXY_PASSWORD
    POSTGRES_LIMBO_PASSWORD
    POSTGRES_HUNGER_GAMES_PASSWORD
    POSTGRES_SMP_PASSWORD
    POSTGRES_STEWARD_UI_PASSWORD
    VELOCITY_FORWARDING_SECRET
    STEWARD_API_TOKEN
    STEWARD_DEPLOYER_TOKEN
    STEWARD_UI_WEB_PUSH_PUBLIC_KEY
    STEWARD_UI_WEB_PUSH_PRIVATE_KEY
)

# What the menu prints for a value: three dots for a set secret, the value otherwise, or "not set".
shown_value() {
    local kind="$1" value="$2"
    if [[ -z "${value//[[:space:]]/}" ]]; then
        printf '(not set)'
        return
    fi
    case "$kind" in
        # The bytes of three bullets in UTF-8, because \u is only expanded in a UTF-8 locale.
        secret|optional-secret) printf '\342\200\242\342\200\242\342\200\242' ;;
        *)                      printf '%s' "$value" ;;
    esac
}

# What a typed menu answer means: `quit`, `deploy`, `edit <n>` or nothing. A bare Return redraws.
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

# Which of the addresses the name resolves to are not this host's; every one must be ours.
addresses_not_ours() {
    local resolved="$1" ours="$2" address
    if [[ -z "${resolved//[[:space:]]/}" ]]; then
        # A name that resolves to nothing does not match.
        printf '(nothing - the name does not resolve)\n'
        return
    fi
    for address in $resolved; do
        grep -qxF "$address" <<<"$ours" || printf '%s\n' "$address"
    done
}

# Which images on this host a pull before `up` would replace. Pure, so the test can feed it fixtures.
# A RepoDigest alone proves nothing: the containerd store gives local builds one too.
#
#   pairs             "<image><TAB><service>" per service under the active profiles
#   local_digests     "<image><TAB><RepoDigests as JSON>" per image present on this host
#   registry_digests  "<image><TAB><manifest digest>" per image the registry answered for
#
# Prints "<RISK|UNKNOWN><TAB><image><TAB><services>" per local image: RISK when it has no RepoDigests
# or lacks the registry's digest, UNKNOWN when the registry could not be asked.
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
            # mawk creates services[$1] before the right side runs, so the test cannot be inline.
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

# renewing this file, which is the one decision made before anything else
# The file's own hash, shortened to twelve characters for comparing by eye.
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

# A copy in a checkout is never replaced, since whoever runs it edited it.
from_a_checkout() {
    running_from_a_file || return 1
    local here; here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    [[ "$(basename "$here")" == "deploy" && -f "$here/../compose.yml" ]]
}

# Fetches the current version into $1 with curl or wget.
fetch_self() {
    local into="$1"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --max-time "$SELF_UPDATE_TIMEOUT" "$SELF_URL" -o "$into" 2>/dev/null
    elif command -v wget >/dev/null 2>&1; then
        wget -q --timeout="$SELF_UPDATE_TIMEOUT" -O "$into" "$SELF_URL" 2>/dev/null
    else
        return 1
    fi
}

# Whether a download is this script, complete, rather than an error page or a truncated file.
looks_like_this_script() {
    local file="$1"
    [[ -s "$file" ]] || return 1
    # `sed -n 1p`, not a pipe into `head`; see deploy/pipe-safety-test.sh.
    [[ "$(sed -n '1p' "$file")" == '#!/usr/bin/env bash' ]] || return 1
    grep -q '^SELF_NAME=' "$file" || return 1
    bash -n "$file" 2>/dev/null
}

# Asking and writing answers. They read $ENV_FILE and $CHECK_ONLY, which the caller sets. `dev init`
# asks some of QUESTIONS in the same words, and NordtalQuestionsTest holds the two together.

# Asks once for one variable and writes it. `kind` is one of:
#   plain            required, echoed while typing
#   secret           required, echo off
#   optional-plain   may be left empty by pressing Enter
#   optional-secret  the same, with the echo off
#   licence          y/N; a no writes nothing, so the run stops at the required check
# `check` is the name of a shape function or "-" for anything non-empty.
# `force` re-asks a variable that is already set; without it a set value is left alone.
ask_for() {
    local name="$1" kind="$2" check="$3" prompt="$4" hint="${5:-}" force="${6:-}" value existing

    existing="$(env_value "$ENV_FILE" "$name")"
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
            set_assignment "$ENV_FILE" "$name" true
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
            # The value is not repeated, since it may be a secret.
            warn "that does not look like it can be right. Try again."
            continue
        fi
        set_assignment "$ENV_FILE" "$name" "$value"
        log "$name written to $ENV_FILE"
        return 0
    done
}

# Writes a default value only if there is none, without asking.
default_for() {
    local name="$1" value="$2" existing
    existing="$(env_value "$ENV_FILE" "$name")"
    [[ -n "${existing//[[:space:]]/}" ]] && return 0
    $CHECK_ONLY && { warn "$name is not set; a real run would write the default"; return 0; }
    set_assignment "$ENV_FILE" "$name" "$value"
    log "$name = $value (default)"
}

# Asks one of the table's questions; `again` re-asks one that is already set.
ask_question() {
    local name="$1" again="${2:-}"
    ask_for "$name" "${QUESTION_KIND[$name]}" "${QUESTION_CHECK[$name]}" \
        "${QUESTION_PROMPT[$name]}" "${QUESTION_HINT[$name]}" "$again"
}

# Generates one shared secret if there is none; REPLACE_ME counts as none.
set_secret() {
    local name="$1" bytes="${2:-32}" value
    value="$(env_value "$ENV_FILE" "$name")"
    if [[ -n "${value//[[:space:]]/}" && "$value" != *REPLACE_ME* ]]; then
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

# An update run requested from the host, which works while the stack itself is broken.
# These are decisions only; `cmd_update` below the seam reaches for Docker.

# The kinds `update_request.kind` accepts.
UPDATE_KINDS=(UPDATE RESTART BACKUP DOWN START)

# How long the command waits; the run itself carries on after it gives up.
UPDATE_TIMEOUT_DEFAULT=1800

# The database's `update_request_scope_check`, so a bad scope is refused here.
update_scope_ok() {
    [[ "$1" =~ ^[a-z0-9-]+(,[a-z0-9-]+)*$ ]]
}

# Reads the flags of `./nordtal.sh update` into the UPDATE_ variables; dies on anything unknown.
parse_update_args() {
    UPDATE_KIND=UPDATE
    UPDATE_SCOPE=""
    UPDATE_DELAY=0
    UPDATE_WAIT=true
    UPDATE_TIMEOUT="$UPDATE_TIMEOUT_DEFAULT"
    UPDATE_ENV_FILE="$DEFAULT_ENV_FILE"
    local kinds=0
    while (( $# > 0 )); do
        case "$1" in
            --restart)  UPDATE_KIND=RESTART; kinds=$(( kinds + 1 )); shift ;;
            --backup)   UPDATE_KIND=BACKUP;  kinds=$(( kinds + 1 )); shift ;;
            --down)     UPDATE_KIND=DOWN;    kinds=$(( kinds + 1 ))
                        UPDATE_SCOPE="${2:-}"
                        [[ -n "$UPDATE_SCOPE" ]] || die "update --down needs a service to stop"
                        shift 2 ;;
            # A bare `--start` releases every hold; a following flag is not a service.
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
            *)          die "unknown argument to \`update\`: $1" ;;
        esac
    done

    (( kinds <= 1 )) || die "update takes one of --restart, --backup, --down or --start, not several"
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

# Inserts the row and notifies in one statement, as `UpdateDao#submit` does.
# Concatenation is safe because every value passed a shape check that admits no quote.
update_insert_sql() {
    local kind="$1" scope="$2" minutes="$3"
    local scope_sql="NULL"
    [[ -n "$scope" ]] && scope_sql="'$scope'"
    cat <<SQL
WITH inserted AS (
    INSERT INTO update_request (kind, actor_kind, scheduled_for, scope)
    VALUES ('$kind', 'HOST', now() + make_interval(mins => $minutes), $scope_sql)
    RETURNING id
), notified AS (
    SELECT pg_notify('nordtal_update', '') FROM inserted
)
SELECT inserted.id FROM inserted, notified;
SQL
}

# One line: the status, a tab and the report, empty rather than NULL.
update_status_sql() {
    printf "SELECT status, coalesce(result, '') FROM update_request WHERE id = %s;\n" "$1"
}

# Whether a status means the worker is finished with this row, one way or another.
update_is_over() {
    case "$1" in
        DONE|FAILED|CANCELLED) return 0 ;;
        *) return 1 ;;
    esac
}

# Definitions end here; only a genuine `source` returns. Under `curl ... | bash` there is no BASH_SOURCE.
if [[ -n "${BASH_SOURCE[0]:-}" && "${BASH_SOURCE[0]}" != "$0" ]]; then
    return 0
fi

# The `update` subcommand, before the self-update, so it never needs the network.

# One psql inside the postgres container, reading its statement from stdin; no password needed.
update_psql() {
    local container="$1" user="$2" database="$3"
    docker exec -i "$container" \
        psql -v ON_ERROR_STOP=1 -qtAX -F $'\t' -U "$user" -d "$database"
}

cmd_update() {
    parse_update_args "$@"

    [[ -f "$UPDATE_ENV_FILE" ]] \
        || die "$UPDATE_ENV_FILE is not there, so this host has no deployment to update.
       If the environment file is somewhere else: ./nordtal.sh update --env-file PATH"

    # Two names only, since the file also holds secrets.
    local project user database container
    project="$(env_value "$UPDATE_ENV_FILE" COMPOSE_PROJECT_NAME)"
    project="${project:-$DEFAULT_PROJECT}"
    user="$(env_value "$UPDATE_ENV_FILE" POSTGRES_USER)"
    database="$(env_value "$UPDATE_ENV_FILE" POSTGRES_DB)"
    [[ -n "$user" && -n "$database" ]] \
        || die "POSTGRES_USER and POSTGRES_DB are not both set in $UPDATE_ENV_FILE"
    container="${project}-postgres-1"

    # `docker ps`, never `docker inspect` on a container carrying secrets; a here-string, not a pipe.
    grep -qxF "$container" <<<"$(docker ps --format '{{.Names}}')" \
        || die "$container is not running, so there is nowhere to write the request.
       \`docker compose -p $project ps\` says what is up."

    local id
    id="$(update_insert_sql "$UPDATE_KIND" "$UPDATE_SCOPE" "$UPDATE_DELAY" \
        | update_psql "$container" "$user" "$database" | sed -n '1p' | tr -d '[:space:]')"
    [[ "$id" =~ ^[0-9]+$ ]] || die "the database did not answer with a request id (got: '$id')"

    local when=""
    (( UPDATE_DELAY > 0 )) && when=", not before $UPDATE_DELAY minute(s) from now"
    log "request $id: $UPDATE_KIND${UPDATE_SCOPE:+ $UPDATE_SCOPE}$when"
    if [[ "$UPDATE_WAIT" != true ]]; then
        printf '%s\n' "$id"
        return 0
    fi

    update_wait "$id" "$container" "$user" "$database"
}

# Follows one request until the worker is finished with it, then prints the run's report.
update_wait() {
    local id="$1" container="$2" user="$3" database="$4"
    local waited=0 answer status report said=""

    log "waiting; Ctrl-C stops WATCHING and never the run itself"
    while :; do
        answer="$(update_status_sql "$id" | update_psql "$container" "$user" "$database" | sed -n '1p')"
        status="${answer%%$'\t'*}"
        report="${answer#*$'\t'}"
        [[ "$status" == "$answer" ]] && report=""

        if [[ -n "$status" && "$status" != "$said" ]]; then
            log "request $id is $status"
            said="$status"
        fi
        if [[ -z "$status" ]]; then
            die "request $id is no longer in update_request. Somebody deleted the row."
        fi
        if update_is_over "$status"; then
            # jq if it is there, the raw JSON line if not.
            if [[ -n "$report" ]]; then
                if command -v jq >/dev/null 2>&1; then
                    printf '%s\n' "$report" | jq . || printf '%s\n' "$report"
                else
                    printf '%s\n' "$report"
                fi
            fi
            [[ "$status" == DONE ]] && return 0
            return 1
        fi

        (( waited += 5 ))
        if (( waited > UPDATE_TIMEOUT )); then
            # Only the waiting stops; the run carries on.
            warn "request $id is still $status after ${UPDATE_TIMEOUT}s. The run continues without
       this command watching it; ./nordtal.sh update --no-wait prints ids, and the interface shows
       the run under /operations."
            return 2
        fi
        sleep 5
    done
}

if [[ "${1:-}" == update ]]; then
    shift
    cmd_update "$@"
    exit $?
fi

# arguments
# Kept whole, since the self-update may hand them to a newer copy.
ARGV=("$@")

FROM_FILE=""
ENV_FILE=""
ADDRESSES_GIVEN=""
CHECK_ONLY=false
BUILD_DEPLOYER=false
SELF_UPDATE=true
# A plain run shows the menu; `--deploy`, `--check` and `--build` turn it off.
MENU=true

# The help text is the comment block at the top of this file.
usage() {
    local file="${BASH_SOURCE[0]:-}"
    [[ -f "$file" ]] || file="$INSTALLED"
    [[ -f "$file" ]] || { printf '%s\n' "$SELF_URL" ; return 0; }
    awk 'NR > 1 { if ($0 !~ /^#/) exit; print }' "$file" | sed 's/^# \{0,1\}//'
}

while (( $# > 0 )); do
    case "$1" in
        --from)             FROM_FILE="${2:?--from needs a path}"; shift 2 ;;
        --env-file)         ENV_FILE="${2:?--env-file needs a path}"; shift 2 ;;
        --address)          ADDRESSES_GIVEN+="${2:?--address needs an address}"$'\n'; shift 2 ;;
        --check)            CHECK_ONLY=true; MENU=false; shift ;;
        --build)            BUILD_DEPLOYER=true; MENU=false; shift ;;
        --deploy)           MENU=false; shift ;;
        --no-self-update)   SELF_UPDATE=false; shift ;;
        -h|--help)          usage; exit 0 ;;
        *)                  die "unknown argument: $1 (try --help)" ;;
    esac
done

# Without a terminal nothing is asked: the run has what it needs or names what is missing.
[[ -t 0 ]] || MENU=false

# 0 · which copy of this file is running
# The first line of output names the version, since this script may replace itself.
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
    if ! fetch_self "$candidate" || ! looks_like_this_script "$candidate"; then
        rm -f "$candidate"
        # Without a network it carries on, unless piped: then there is no copy to run.
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
        ORIGIN="this copy, which is what GitHub currently serves"
        return 0
    fi

    # Runs from the temporary file; it installs itself once the directory question is answered.
    chmod 755 "$candidate"
    export NORDTAL_SH_ORIGIN="$SELF_URL, fetched for this run"
    export NORDTAL_SH_TEMP="$candidate"
    if running_from_a_file; then
        log "a newer version is published ($(fingerprint "$candidate")); running that one instead
       of this one ($(fingerprint "${BASH_SOURCE[0]}"))"
    else
        log "fetched the current version; running that"
    fi
    if [[ ! -t 0 ]] && (exec </dev/tty) 2>/dev/null; then
        # Piped in: stdin is the script, so the new copy asks on the terminal.
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

# Copies this file into the installation directory. Never called under --check.
install_self() {
    running_from_a_file || return 0
    local source; source="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
    if [[ "$source" != "$INSTALLED" ]]; then
        install -m 755 "$source" "$INSTALLED" \
            || die "could not write $INSTALLED. The installation directory has to be writable - it
       is where every world and every database in this deployment is about to live."
        log "this script is now $INSTALLED - run it again to change a setting or to deploy"
    fi
    # Deleting the file bash is reading is safe: the open file survives the name.
    [[ -n "$SELF_TEMP" && -f "$SELF_TEMP" && "$SELF_TEMP" != "$INSTALLED" ]] && rm -f "$SELF_TEMP"
    return 0
}

# 0a · is this the directory?
# Asked once per installation. An environment file naming another NORDTAL_DIR stops the run, since
# two installations would share a password, a project name and volumes.
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

# 1 · what has to be on the host already
command -v docker >/dev/null 2>&1 \
    || die "no docker on this host. Everything below needs a daemon; nothing here installs one."
docker compose version >/dev/null 2>&1 \
    || die "docker is here but \`docker compose\` is not. The compose plugin is a separate package."
docker info >/dev/null 2>&1 \
    || die "docker is installed and this user cannot talk to the daemon. Run this as root, or as a
       member of the docker group - which is root with extra steps, and is the trade this host has
       already made by running a stack at all."
log "docker $(docker version --format '{{.Server.Version}}'), compose $(docker compose version --short)"

# 2 · the environment file
# Holds every secret; compose interpolates it and steward-deployer mounts it read-only.
is_absolute "$ENV_FILE" || die "--env-file has to be absolute, and '$ENV_FILE' is not. compose
       resolves a relative path against steward-deployer's project directory, which is inside its
       image - so a relative path here points at a file that does not exist."

if [[ ! -f "$ENV_FILE" ]]; then
    if [[ -z "$FROM_FILE" && -f "$INSTALL_DIR/.env" ]]; then
        FROM_FILE="$INSTALL_DIR/.env"
    fi
    if [[ -n "$FROM_FILE" ]]; then
        [[ -f "$FROM_FILE" ]] || die "--from $FROM_FILE does not exist."
        $CHECK_ONLY || {
            install -D -m 600 "$FROM_FILE" "$ENV_FILE"
            # Copied, never moved: the old file stays as the way back.
            cmp -s "$FROM_FILE" "$ENV_FILE" || die "the copy of the environment file does not match its
       source. Nothing further has been done."
            log "environment file copied to $ENV_FILE (mode 600); $FROM_FILE is left alone"
        }
    else
        # A new host starts with an empty file that the questions below fill.
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

# 2a · the questions
# Asks once for each missing value. Secrets are read with echo off and never shown; a malformed
# answer is refused at the prompt.

default_for COMPOSE_PROFILES     "db,bot,mc,backup,steward"
default_for COMPOSE_PROJECT_NAME "$DEFAULT_PROJECT"
default_for POSTGRES_DB          "nordtal"
default_for POSTGRES_USER        "nordtal"
# So steward-deployer mounts the file this deployment is configured from; section 3 checks it.
default_for STEWARD_ENV_FILE     "$ENV_FILE"
# Absolute, since compose resolves a relative bind inside steward-deployer's image.
default_for NORDTAL_DIR           "$INSTALL_DIR"
# steward-deployer mounts the directory: a file bind would keep a rotated file's old inode.
default_for STEWARD_ENV_DIR       "$(dirname "$ENV_FILE")"
default_for STEWARD_ENV_FILE_NAME "$(basename "$ENV_FILE")"

# The bunq pair is asked below; COMPOSE_PROFILES has a default and is changed in the menu.
for question in "${QUESTIONS[@]}"; do
    case "$question" in
        NORDTAL_STEWARD_BUNQ_*|COMPOSE_PROFILES) continue ;;
    esac
    if [[ "$question" == EULA ]]; then
        # A refused licence ends the run.
        ask_question EULA || die "the EULA was not accepted, so there is nothing to deploy.
       Nothing has been changed beyond the environment file this script has been filling in."
        continue
    fi
    ask_question "$question"
done

# bunq is optional: without it nothing polls for payments. The key lives in steward-worker.
if ask_question NORDTAL_STEWARD_BUNQ_API_KEY; then
    ask_question NORDTAL_STEWARD_BUNQ_ACCOUNT_ID
fi

# Warns about the old bunq names, which nothing reads. Values are not copied: a bunq key is bound to
# the context of the container that registered it.
for stale in NORDTAL_BOT_BUNQ_API_KEY NORDTAL_BOT_BUNQ_ACCOUNT_ID; do
    if [[ -n "$(env_value "$ENV_FILE" "$stale")" ]]; then
        warn "$ENV_FILE still has $stale. Nothing reads it - bunq lives in
       steward-worker and the names are NORDTAL_STEWARD_BUNQ_API_KEY and
       NORDTAL_STEWARD_BUNQ_ACCOUNT_ID. Delete the old line once the new one is in, and read
       steward-worker's first log line after the next deploy: it says 'bunq is ON' or 'bunq is
       OFF' in one sentence, and that sentence is the only confirmation there is."
    fi
done

# 2b · the menu
# Shows every value, lets one be changed and deploys at the end. Secrets show as three dots.
# Only a run with no arguments on a terminal gets it.
show_menu() {
    local index=1 name value
    printf '\n'
    log "the installation in $INSTALL_DIR"
    printf '        %s\n\n' "$ENV_FILE"
    for name in "${QUESTIONS[@]}"; do
        value="$(env_value "$ENV_FILE" "$name")"
        printf '   %2d  %-34s %s\n' "$index" "$name" \
            "$(shown_value "${QUESTION_KIND[$name]}" "$value")"
        index=$(( index + 1 ))
    done
    printf '\n'
    for name in "${GENERATED[@]}"; do
        value="$(env_value "$ENV_FILE" "$name")"
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
                # steward-worker refuses a bunq key without an account, so ask for both.
                if [[ "$picked" == NORDTAL_STEWARD_BUNQ_API_KEY \
                    && -n "$(env_value "$ENV_FILE" NORDTAL_STEWARD_BUNQ_API_KEY)" \
                    && -z "$(env_value "$ENV_FILE" NORDTAL_STEWARD_BUNQ_ACCOUNT_ID)" ]]; then
                    ask_question NORDTAL_STEWARD_BUNQ_ACCOUNT_ID || true
                fi
                ;;
            *)
                warn "that is not one of them: a number from the list, d to deploy, q to stop."
                ;;
        esac
    done
fi

# 3 · which deployment this is
# Another project name would start a second, empty stack. Runs before section 4, which must not
# invent a password for an existing database.
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

# Named volumes of an older installation are no longer mounted, so starting on empty directories
# needs consent. Nothing is copied or deleted.
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

# 4 · generated secrets
# Postgres reads POSTGRES_PASSWORD only on an empty data directory; an adopted host is asked.
command -v openssl >/dev/null 2>&1 || die "no openssl on this host, and the generated secrets have to
       come from somewhere. Install it, or put every one GENERATED lists into $ENV_FILE yourself -
       and not the same value twice."

if [[ -z "$(env_value "$ENV_FILE" POSTGRES_PASSWORD)" ]] && $ADOPTING && ! $CHECK_ONLY; then
    ask_for POSTGRES_PASSWORD secret - \
        "The password of the database that is already on this host." \
        "'${PROJECT}_postgres-data' exists, so postgres will not take a new password: it reads
        POSTGRES_PASSWORD only when it initialises an empty data directory. Generating one here
        would leave every service unable to log in to a database that is working fine."
else
    set_secret POSTGRES_PASSWORD 24
fi

# Each service's own database role; steward-worker sets these on the roles at every start, so a new
# one only needs a restart, unlike POSTGRES_PASSWORD.
set_secret POSTGRES_DISCORD_BOT_PASSWORD 24
set_secret POSTGRES_PROXY_PASSWORD 24
set_secret POSTGRES_LIMBO_PASSWORD 24
set_secret POSTGRES_HUNGER_GAMES_PASSWORD 24
set_secret POSTGRES_SMP_PASSWORD 24
set_secret POSTGRES_STEWARD_UI_PASSWORD 24
set_secret VELOCITY_FORWARDING_SECRET 24
set_secret STEWARD_API_TOKEN
set_secret STEWARD_DEPLOYER_TOKEN

if [[ "$(env_value "$ENV_FILE" STEWARD_API_TOKEN)" == "$(env_value "$ENV_FILE" STEWARD_DEPLOYER_TOKEN)" ]]; then
    $CHECK_ONLY || die "STEWARD_API_TOKEN and STEWARD_DEPLOYER_TOKEN are the same value. Reading
       containers and creating containers are different privileges - that is why they are two
       services on two ports - and one token for both makes the boundary a comment."
fi

# 4b · the Web Push keypair
# Minted with `steward-ui generate-vapid-keys`, which needs neither database nor config. A half
# set pair is replaced whole. The image is pulled only if absent, so a local build survives.
generate_vapid_keys() {
    local image="${STEWARD_UI_IMAGE:-ghcr.io/nordtal/steward-ui:latest}"
    local pub priv output
    pub="$(env_value "$ENV_FILE" STEWARD_UI_WEB_PUSH_PUBLIC_KEY)"
    priv="$(env_value "$ENV_FILE" STEWARD_UI_WEB_PUSH_PRIVATE_KEY)"

    if [[ -n "${pub//[[:space:]]/}" && -n "${priv//[[:space:]]/}" ]]; then
        log "STEWARD_UI_WEB_PUSH_PUBLIC_KEY / _PRIVATE_KEY are already set (left alone)"
        return
    fi
    if $CHECK_ONLY; then
        warn "the Web Push VAPID keypair is not set; a real run would generate one"
        return
    fi
    if [[ -n "${pub//[[:space:]]/}" || -n "${priv//[[:space:]]/}" ]]; then
        warn "exactly one half of the Web Push VAPID keypair is set in $ENV_FILE - WebPushSpec
       reads that as broken, not as 'not configured', so a fresh pair replaces both halves."
    fi

    local fallback="web-push stays unconfigured for now - the subscribe button is simply not
       drawn. Generate a pair once the stack is up with \`docker exec ${PROJECT}-steward-ui-1
       steward-ui generate-vapid-keys\`, paste the two lines into STEWARD_UI_WEB_PUSH_PUBLIC_KEY
       and STEWARD_UI_WEB_PUSH_PRIVATE_KEY in $ENV_FILE, and recreate steward-ui so it reads them."

    if ! docker image inspect "$image" >/dev/null 2>&1; then
        log "pulling $image to mint a Web Push VAPID keypair (no database and no config needed for
       that one command - see steward-ui's StewardUi.java)"
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

    set_assignment "$ENV_FILE" STEWARD_UI_WEB_PUSH_PUBLIC_KEY "$pub"
    set_assignment "$ENV_FILE" STEWARD_UI_WEB_PUSH_PRIVATE_KEY "$priv"
    log "STEWARD_UI_WEB_PUSH_PUBLIC_KEY / _PRIVATE_KEY generated (a fresh VAPID keypair)"
}
generate_vapid_keys

# 4a · everything required is set
# Firing here means this script failed to write a value it asked for or generated.
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

# The derived directory and name must match the file, or steward-deployer mounts the wrong one.
declared_env_dir="$(env_value "$ENV_FILE" STEWARD_ENV_DIR)"
[[ "$declared_env_dir" == "$(dirname "$ENV_FILE")" ]] || die "STEWARD_ENV_DIR inside the file says
       '$declared_env_dir', and the file is at '$ENV_FILE' (directory '$(dirname "$ENV_FILE")').
       That value is what compose.yml mounts into steward-deployer as a directory, so a stale
       STEWARD_ENV_DIR would mount the wrong directory entirely."
declared_dir="$(env_value "$ENV_FILE" NORDTAL_DIR)"
[[ "$declared_dir" == "$INSTALL_DIR" ]] || die "NORDTAL_DIR inside the file says '$declared_dir',
       and this run is in '$INSTALL_DIR'. That value is what every volume in compose.yml hangs off,
       so the deployment would read its worlds and its database from somewhere other than the
       directory this run is installing into."
is_absolute "$declared_dir" || die "NORDTAL_DIR inside the file is '$declared_dir', which is not an
       absolute path. steward-deployer runs compose from /app inside its own image, so a relative
       bind resolves against a directory that exists only in there."

declared_env_file_name="$(env_value "$ENV_FILE" STEWARD_ENV_FILE_NAME)"
[[ "$declared_env_file_name" == "$(basename "$ENV_FILE")" ]] || die "STEWARD_ENV_FILE_NAME inside
       the file says '$declared_env_file_name', and the file is named '$(basename "$ENV_FILE")'.
       steward-deployer looks for exactly this name inside the mounted directory."

log "every required value is set; the interface will answer on $STEWARD_NAME"

# 4c · the data directories
# Created here because Docker would create steward-ui-config root-owned, and steward-ui runs as uid
# 10001. Existing directories are left alone.
log "the installation directory: $INSTALL_DIR"
for entry in "${DATA_DIRS[@]}"; do
    directory="$INSTALL_DIR/$(dir_name "$entry")"
    owner="$(dir_owner "$entry")"
    if [[ -d "$directory" ]]; then
        continue
    fi
    $CHECK_ONLY && { warn "$directory does not exist; a real run would create it"; continue; }
    mkdir -p "$directory" || die "could not create $directory. Every world, every database and
       every backup this deployment has is about to live under $INSTALL_DIR, so a directory that
       cannot be created there is the end of the run."
    if [[ -n "$owner" ]]; then
        chown "$owner" "$directory" || die "could not chown $directory to $owner. steward-ui runs
       as that uid and writes its own configuration there; root-owned, it would report a saved
       setting and change nothing."
        log "$(dir_name "$entry")/ created, owned by $owner"
    fi
done

# 5 · the name, and the wait
# Caddy requests a certificate for STEWARD_HOST on start, so the name must point here first.
this_hosts_addresses() {
    if [[ -n "$ADDRESSES_GIVEN" ]]; then
        printf '%s' "$ADDRESSES_GIVEN"
        return
    fi
    # Behind NAT there is no public address here; --address supplies it.
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

# 5a · locally built images about to be replaced
# The deployer pulls every service before `up`, replacing a local build whose tag the registry
# answers. compose.yml comes from the local deployer image; without one there is nothing to warn.
COMPOSE_CACHE=""
compose_file() {
    [[ -n "$COMPOSE_CACHE" ]] && { printf '%s' "$COMPOSE_CACHE"; return 0; }
    docker image inspect "$DEPLOYER_IMAGE" >/dev/null 2>&1 || return 1
    local container cache
    cache="$(mktemp -d)/compose.yml"
    container="$(docker create "$DEPLOYER_IMAGE" 2>/dev/null)" || return 1
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

# Gathers what at_risk_images decides on: service images, their local RepoDigests and the registry
# digests. Without jq or buildx it warns and skips; neither is a prerequisite.
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
        warn "no copy of $DEPLOYER_IMAGE on this host yet, so there is no compose.yml to read the"
        warn "image list out of. Nothing here has been deployed before, which is also why there is"
        warn "nothing for this check to protect."
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

        # A failed lookup counts as unknown, not safe; `|| reg=""` keeps set -e from exiting.
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

# 6 · renew steward-deployer
# The one image the stack cannot replace itself; compose.yml is baked into it.
if $BUILD_DEPLOYER; then
    # Needs a JDK and a checkout: the installation directory or the one this script sits in.
    checkout=""
    if [[ -f "$INSTALL_DIR/gradlew" && -d "$INSTALL_DIR/steward-deployer" ]]; then
        checkout="$INSTALL_DIR"
    elif running_from_a_file && from_a_checkout; then
        checkout="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    fi
    [[ -n "$checkout" ]] || die "--build builds $DEPLOYER_IMAGE from a checkout of nordtal/season-2,
       and this run is not in one. Run it from a checkout, or drop --build and let it pull the
       published image."
    log "building $DEPLOYER_IMAGE from $checkout"
    sh "$checkout/gradlew" :steward-deployer:build
    docker build -t "$DEPLOYER_IMAGE" "$checkout/steward-deployer"
else
    log "pulling $DEPLOYER_IMAGE"
    docker pull "$DEPLOYER_IMAGE" || die "could not pull $DEPLOYER_IMAGE. A registry that answers
       'denied' means the same thing as one that answers 'not found': either the release that
       carries this image has not been published yet, or the package is still private. --build
       builds it from this checkout instead, and needs a JDK."
fi

# 7 · the deployment itself
# A one-off deployer runs `up`, which pulls every image before stopping anything. The env
# directory is mounted, and NORDTAL_STEWARD_ENV_FILE names the file inside it.
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
log "The installation is $INSTALL_DIR - the worlds, the database and the backups are directories"
log "in there, and \`./nordtal.sh\` is how a setting is changed or a new release deployed."
