#!/usr/bin/env bash
#
# The season 2 stack, installed into a directory on a host that has nothing but a Docker daemon.
#
#   curl -fsSL https://raw.githubusercontent.com/nordtal/season-2/main/deploy/nordtal.sh | bash
#
# THAT IS THE INSTALL, AND IT NEEDS NO CHECKOUT. Everything this script needs is either asked for,
# generated here, or inside an image it pulls. It installs into the DIRECTORY IT IS RUN FROM - the
# worlds, the databases, the plugin configs and the backups all become directories there - and the
# first thing it asks is whether that directory is really the right one.
#
# IT THEN STAYS THERE as ./nordtal.sh, and that is how a setting is changed afterwards:
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
# AND ONE SUBCOMMAND, WHICH IS NOT AN INSTALL AT ALL (season-2-ops/153). It writes a row into
# `update_request` and waits for the worker to finish it - the same row Steward and Discord write,
# and the way to start a run when the only other doors are inside the stack being updated:
#
#   ./nordtal.sh update                the whole network: install what is new, restart what needs it
#   ./nordtal.sh update --restart      restart everything, install nothing
#   ./nordtal.sh update --backup       one backup run, now
#   ./nordtal.sh update --down smp     stop one service and hold it down
#   ./nordtal.sh update --start [svc]  release a hold - everything, or one service
#   ./nordtal.sh update --in 10        let the countdown run for ten minutes first
#   ./nordtal.sh update --no-wait      print the request id and return, instead of waiting
#
# It touches nothing else: no self-update, no menu, no deploy. It reads two variables out of the
# environment file (the database user and the database name), reaches the database through
# `docker exec` on the postgres container, and prints the run's own report when it is over.
#
# IT RENEWS ITSELF ON EVERY RUN, and the reason is RUN IT AGAIN AFTER EVERY RELEASE below: a new
# compose.yml reaches this host only inside a new steward-deployer image, and a directory that has
# stood for half a year would otherwise deploy with a script that knows nothing about it. So the
# first thing it does is fetch the current version, write it beside the installation and run THAT.
# Two things follow from doing it at all, and both are deliberate:
#
#   WITHOUT A NETWORK IT CARRIES ON with the copy that is here rather than failing - an installation
#   that cannot be operated because GitHub is unreachable would be a worse bargain than a stale one;
#   IT SAYS WHICH VERSION IT IS RUNNING, every time, with the fingerprint of the file and where that
#   file came from. A script that silently swaps itself for other code must not be quiet about it.
#
# It is also the one thing in this deployment that lives OUTSIDE the deployment. Everything else is
# a container that steward-deployer can recreate; steward-deployer itself cannot, because a service
# that replaces its own container never gets to report how that went. So renewing it is this
# script's job (§9c) - and since it has to exist and be re-runnable anyway, it is also what puts the
# environment file in place and what refuses to continue while the certificate cannot be issued.
#
# NOBODY EDITS A .env. This script asks for the nine things only a person can know - the name the
# interface answers on, the address Let's Encrypt writes to, the EULA, the bot's token, the two
# halves of the Discord application, the guild, the admin role, and bunq if there is a bunq - and it
# writes them itself, into a file it creates with mode 600. Everything else is either generated here
# (the database password, the proxy's forwarding secret, the two Steward tokens) or has a default in
# the service's own configuration, which the interface can then edit.
#
# THE ENVIRONMENT FILE IS NOT IN THE INSTALLATION DIRECTORY, and that is on purpose: it holds every
# secret the deployment has, and the installation directory is the one somebody reaches over SFTP.
# It stays at /etc/nordtal/season-2.env, mode 600, and the installation directory holds only data.
#
# RUN IT AGAIN AFTER EVERY RELEASE. That is not a nicety: a new compose.yml reaches this host only
# inside a new steward-deployer image, so "deploy the new version" is this script, and everything
# else in the stack then follows from the deployer.
#
# IT NEVER PRINTS A SECRET AND NEVER PUTS ONE ON A COMMAND LINE. A secret is read with the terminal
# echo off, handed to `awk` through the environment rather than through `-v` (an argument is visible
# in `ps` to every user on the host), and written into a file created 600 beside its destination.
# The menu prints a secret that is set as three dots and never as itself; the report says which
# variable is missing, never what is in it.
#
# Everything here runs in the directory it was started from, which IS the installation.
set -Eeuo pipefail

# THERE IS NO `ROOT` ANY MORE, and that is the whole shape of this change. It used to be
# `$(dirname "${BASH_SOURCE[0]}")/..`, the repository this file sat in - which does not exist when
# the file arrives through a pipe: under `curl ... | bash` there is no BASH_SOURCE[0] at all
# (measured, bash 5.2: it is unset, so reading it under `set -u` is itself an error). The
# installation is the CURRENT DIRECTORY instead, and nothing here reads a checkout.
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

# --- the one thing this file cannot ask for politely ---------------------------------------------
# The question table below is four `declare -A`, and associative arrays arrived in bash 4.0 (2009).
# macOS still ships 3.2.57 as /bin/bash and always will - it is the last GPLv2 release - so
# `#!/usr/bin/env bash` on a laptop finds a shell that does not have them.
#
# WITHOUT THIS LINE THE FAILURE IS UNREADABLE, and that is the whole reason it is here: 3.2 does not
# know `-A`, so it reads `[STEWARD_HOST]=plain` as a NUMERIC subscript, evaluates STEWARD_HOST as
# arithmetic, and `set -u` turns that into `STEWARD_HOST: unbound variable` - a sentence about the
# environment file, pointing at a variable the example file sets, on a run that has not read either
# one yet. It cost an afternoon once; it is a `brew install bash` away.
#
# Checked here rather than in deploy/dev because dev sources this file before its own first line,
# so this is the earliest point either script can speak at all.
if [[ "${BASH_VERSINFO[0]:-0}" -lt 4 ]]; then
    die "this needs bash 4 or newer and found ${BASH_VERSION:-an unknown version} at ${BASH:-bash}.
       macOS ships bash 3.2 and cannot be talked out of it. Install a current one and make sure it
       comes first in PATH:  brew install bash"
fi

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
    NORDTAL_DIR
    STEWARD_UI_DISCORD_CLIENT_ID
    STEWARD_UI_DISCORD_CLIENT_SECRET
)

# --- what the installation directory holds ------------------------------------------------------
# One directory per volume compose.yml names, under NORDTAL_DIR, spelt exactly like the volume it
# replaced (season-2-ops/124) - so `nordtal-s2_mc-smp-20260919T031500Z.tar.zst` in the backups is
# `mc-smp/` here, without anybody having to work out a mapping.
#
# THIS LIST IS A COPY OF WHAT compose.yml SAYS, and the copy is the cheap side of the trade: this
# script cannot read compose.yml at all on a fresh host (it is inside steward-deployer's image, and
# no image has been pulled yet at the point the directories are needed). What a stale list costs is
# almost nothing - Docker creates a missing bind source itself, as root, mode 755 - EXCEPT for a
# service that does not run as root, which is the whole reason the second column exists.
#
# THE SECOND COLUMN IS AN OWNER, and steward-ui is the one that has one. It runs as uid 10001 (see
# its Dockerfile: "the process that a stranger reaches first runs as UID 0" is not a sentence to
# leave standing), and a named volume used to hand it the image's ownership for free. A BIND MOUNT
# DOES NOT: Docker creates the directory root:root and the interface then cannot write its own
# steward-ui.yml - which does not fail loudly, it fails as a settings page that saves and changes
# nothing. So this script chowns that one directory and only that one.
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

# The name and the owner out of one of those entries. Two functions rather than one because bash
# returns a string, and a caller that has to split the answer again is a caller that can get the
# splitting wrong.
dir_name()  { printf '%s' "${1%%:*}"; }
dir_owner() { [[ "$1" == *:* ]] && printf '%s' "${1#*:}"; }

# --- decisions, kept apart so they can be tested ---------------------------------------------------
# Everything in this block is a question with an answer and no side effect, which is what lets
# deploy/nordtal-test.sh drive it without a Docker daemon, without a network and without an
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

# A host and a port, the way a player types the network into their client. The port is required and
# that is not pedantry: a SRV record can hide it from somebody typing a name, but the value here is
# what a transfer hands to a client, and the transfer packet carries a port with no SRV lookup
# behind it (season-2-ops/119). `play.example.com` alone would therefore work everywhere except in
# the one place this is read.
looks_like_public_address() {
    local host="${1%:*}" port="${1##*:}"
    [[ "$1" == *:* ]] || return 1
    looks_like_host "$host" || return 1
    [[ "$port" =~ ^[0-9]{1,5}$ ]] && (( port >= 1 && port <= 65535 ))
}

# A profile selection: names, commas, no spaces needed and none rejected. It refuses the shapes a
# selection cannot have - a path, a JSON array, an `=` - rather than a name nobody defines, because
# a profile compose.yml does not know is simply not selected and costs nothing.
looks_like_profiles() {
    [[ "$1" =~ ^[[:space:]]*[a-z0-9-]+([[:space:]]*,[[:space:]]*[a-z0-9-]+)*[[:space:]]*$ ]]
}

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

# --- the questions, as a table rather than as call sites ------------------------------------------
# WHY THIS IS A TABLE (season-2-ops/124): the same twelve variables are now walked twice - once by
# the run that asks for what is MISSING, and once by the menu, which lists what is SET and lets one
# be picked and typed again. Two lists would drift, and the way they would drift is the quiet one: a
# variable that can be asked for on a first install and not changed afterwards.
#
# The order here is the order the menu shows, and it is the order somebody fills them in: the name
# and the certificate first, then the licence, then Discord, then bunq, then what comes up at all.
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

# The secrets this script generates rather than asks for. They are in no menu - regenerating
# POSTGRES_PASSWORD against a database that already exists is the one edit that breaks a working
# deployment in a way nothing reports - but they ARE listed under it, as three dots, so that the
# answer to "where is the database password" is on the same screen as everything else.
GENERATED=(
    POSTGRES_PASSWORD
    VELOCITY_FORWARDING_SECRET
    STEWARD_API_TOKEN
    STEWARD_DEPLOYER_TOKEN
    STEWARD_UI_WEB_PUSH_PUBLIC_KEY
    STEWARD_UI_WEB_PUSH_PRIVATE_KEY
)

# What the menu prints for a value, and the rule it keeps is absolute: A SECRET IS NEVER PRINTED.
# Not the first characters of it, not its length - the fact that one is set is the whole of what a
# menu needs to say, and anything more ends up in a screenshot or a terminal recording. The dots
# are three, always, whatever is behind them.
#
# The other two answers are as short: a value that is set is shown as it is (a host name and a
# guild id are not secrets and reading them back is the point of the menu), and one that is not
# says so in words rather than as an empty column nobody can tell from a space.
shown_value() {
    local kind="$1" value="$2"
    if [[ -z "${value//[[:space:]]/}" ]]; then
        printf '(not set)'
        return
    fi
    case "$kind" in
        secret|optional-secret) printf '\u2022\u2022\u2022' ;;
        *)                      printf '%s' "$value" ;;
    esac
}

# What a typed menu answer means: `quit`, `deploy`, `edit <n>`, or nothing at all for an answer
# that is none of those.
#
# A BARE RETURN IS NOT A DEPLOY, and that is the same decision deploy/restore.sh and `deploy/dev
# reset` make about a confirmation: the one answer somebody gives without reading is the empty one,
# and here it would stop four Minecraft servers. It redraws the menu instead.
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

# Which of compose.yml's images are on this host and would be replaced by §7's unconditional `up`
# (steward/107) - either because they carry no RepoDigests at all, or because the registry currently
# serves a manifest digest that this image's own RepoDigests do not contain.
#
# THE FIRST VERSION OF THIS ONLY CHECKED FOR "no RepoDigests at all", and it was wrong on this very
# host: the classic docker image store never assigns a locally built image a RepoDigest, but the
# containerd image store DOES - identical to the image ID, not derived from any registry - so a
# steward-ui rebuilt with `docker compose build` and never pushed anywhere still carried one, and the
# check was silent for exactly the case it exists for (steward, 2026-09-17, measured against a real
# rebuild of steward-ui and steward-deployer on this host). Whether a RepoDigest exists is therefore
# not the question; whether it matches what the registry serves RIGHT NOW is.
#
# PURE ON PURPOSE, the same way addresses_not_ours is: it takes what docker calls already found
# rather than making them itself, so deploy/nordtal-test.sh can hand it fixture text and check the
# decision without a daemon.
#
#   pairs             one "<image><TAB><service>" line per service compose.yml defines under the
#                     active profiles - the same image can (and for the four Minecraft services,
#                     does) appear more than once, under different service names.
#   local_digests     one "<image><TAB><RepoDigests-as-JSON>" line per image already on this host -
#                     an image `docker image inspect` cannot find at all is simply absent here, which
#                     is correct: nothing is at risk from an image the pull would fetch for the first
#                     time.
#   registry_digests  one "<image><TAB><manifest digest>" line per image the registry answered a
#                     current digest for. An image absent here is one the registry did NOT answer
#                     for - a private repository this host has no manifest access to, a network
#                     failure, a tag nothing ever pushed - and that is not the same as "safe".
#
# Output: one "<RISK|UNKNOWN><TAB><image><TAB><services, comma-separated>" line per image that is
# already on this host and either
#   RISK     carries no RepoDigests at all, OR the registry's current digest for it is not among its
#            own RepoDigests - a pull would change what is running;
#   UNKNOWN  carries RepoDigests, but the registry could not be asked - folding this into "safe"
#            would trade one silent failure (steward/107 itself) for another.
# An image absent from local_digests entirely produces no line at all: it has never been pulled or
# built here, so there is nothing local for a pull to replace.
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
            services[$1] = ($1 in services ? services[$1] "," : "") $2
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

# --- renewing this file, which is the one decision made before anything else ----------------------
# The fingerprint is the file's own hash and not a version constant, because a constant in here is
# a second place to write a version down and every version written down twice in this repository
# has gone stale. Twelve characters is enough to compare two of them by eye.
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

# A working copy is never replaced by the published one. Somebody editing deploy/nordtal.sh in a
# checkout and running it means to run what they edited; fetching over that would be the single
# most confusing thing this section could do.
from_a_checkout() {
    running_from_a_file || return 1
    local here; here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    [[ "$(basename "$here")" == "deploy" && -f "$here/../compose.yml" ]]
}

# Fetches the current version into $1. curl or wget, whichever is here; a host with neither still
# has whatever copy it is running.
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

# Whether what came back is this script rather than a proxy's error page, a login form or half a
# download. Three cheap questions, and the third is the one that matters: a truncated script is
# valid bash right up to where it stops.
looks_like_this_script() {
    local file="$1"
    [[ -s "$file" ]] || return 1
    # `sed -n 1p` into a comparison rather than `head -1 | grep -q`: that pipe is the shape
    # deploy/pipe-safety-test.sh exists for, and it caught this line when it was first written.
    [[ "$(sed -n '1p' "$file")" == '#!/usr/bin/env bash' ]] || return 1
    grep -q '^SELF_NAME=' "$file" || return 1
    bash -n "$file" 2>/dev/null
}

# --- asking, and writing down an answer --------------------------------------------------------
# THESE FOUR ARE ABOVE THE SEAM SO THAT `deploy/dev` CAN USE THEM (season-2-ops/147). Till's cut is
# that the two scripts share the QUESTIONS and nothing else: a local setup asks a person the same
# things in the same words, with the same shape checks and the same "a secret is never echoed"
# rule, and then does none of what the rest of this file does - no images pulled, no /etc/nordtal,
# no waiting on DNS, no root.
#
# They read $ENV_FILE and $CHECK_ONLY, which a caller sets: this file sets them in section 2 below,
# `deploy/dev` sets them itself. That is the whole of the contract, and it is why they are
# definitions and not calls - the call sites stay down there, where the installation is.

# Asks once for one variable and writes it. `kind` is one of:
#   plain            required, echoed while typing
#   secret           required, echo off
#   optional-plain   may be left empty by pressing Enter
#   optional-secret  the same, with the echo off
#   licence          y/N, and only a yes writes anything - see below
# `check` is the name of a shape function or "-" for anything non-empty.
#
# `force` re-asks a variable that is already set, which is what the menu does with the one a person
# picked. Without it a set value is left alone and reported, which is what a plain run does.
#
# THE LICENCE IS A KIND AND NOT A BLOCK OF ITS OWN any more (season-2-ops/124). It was written out
# twice as long further down, and the menu would have needed a third copy: a person who mistyped
# the EULA answer could otherwise never correct it, because every other value is editable and that
# one was not. What makes it its own kind rather than a plain question is that NOTHING IS WRITTEN
# FOR A NO - `answer_is_yes` decides, and a no leaves the variable unset so that the run stops at
# the check below rather than recording a licence nobody accepted.
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

# Ask one of the table's questions. `again` re-asks one that is already set, which is what the menu
# does; without it a value that is there is left alone.
ask_question() {
    local name="$1" again="${2:-}"
    ask_for "$name" "${QUESTION_KIND[$name]}" "${QUESTION_CHECK[$name]}" \
        "${QUESTION_PROMPT[$name]}" "${QUESTION_HINT[$name]}" "$again"
}

# Generates one shared secret if there is none. REPLACE_ME counts as none, the same way `ask_for`
# and `env_missing` read it: it is this project's marker for a line that exists so that a file is
# complete, not because somebody answered it. Without that rule a `deploy/dev.env` copied from the
# example would keep the word REPLACE_ME as its database password and every container would fail
# to authenticate against a database that is working perfectly (season-2-ops/147).
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

# --- an update run, asked for from the host (season-2-ops/153) ------------------------------------
# WHY THIS IS HERE AT ALL: `/update` on a Minecraft console is the one surface that does not depend
# on what is being updated, and season-2-ops/154 removes it. Steward is the other door and Steward
# is a container in this stack. So the emergency exit is this file, which is already outside the
# deployment, already knows where the environment file is, and already has to exist.
#
# Everything in this block is a decision and touches nothing: `cmd_update` below the seam is the
# half that reaches for Docker. deploy/nordtal-test.sh exercises these.

# The kinds `update_request.kind` accepts, in the order the flags below name them. REPORT and APPLY
# are the worker's own internal kinds and are deliberately not offered here.
UPDATE_KINDS=(UPDATE RESTART BACKUP DOWN START)

# How long a wait may last before the command gives up and says so. It gives up on WAITING, never
# on the run: the row stays, the worker carries on, and the id is printed so it can be looked at.
UPDATE_TIMEOUT_DEFAULT=1800

# `update_request_scope_check` in the database, spelled the same way. A scope that does not match
# is refused HERE rather than by a constraint violation three layers down.
update_scope_ok() {
    [[ "$1" =~ ^[a-z0-9-]+(,[a-z0-9-]+)*$ ]]
}

# Who asked, for the `requested_by` column - which is 64 characters and carries no foreign key.
# EVERYTHING OUTSIDE THE ALLOWED SHAPE BECOMES A DASH, which is what makes the SQL below safe to
# assemble by hand: there is no quote left in it to close.
update_requester() {
    local who host
    who="${SUDO_USER:-${USER:-$(id -un 2>/dev/null || echo unknown)}}"
    host="$(hostname -s 2>/dev/null || echo unknown)"
    printf '%s' "${who}@${host}" | tr -c 'A-Za-z0-9._@-' '-' | cut -c1-64
}

# Reads the flags of `./nordtal.sh update` into UPDATE_KIND, UPDATE_SCOPE, UPDATE_DELAY,
# UPDATE_WAIT, UPDATE_TIMEOUT and UPDATE_ENV_FILE. Dies on anything it does not recognise rather
# than ignoring it: this command starts a run that stops servers.
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
            # THE SERVICE IS OPTIONAL HERE AND NOWHERE ELSE: `--start` with nothing after it
            # releases every hold, which is what somebody who has forgotten what they stopped
            # actually wants. A following flag is not a service name.
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

# The statement that writes the row and rings the bell in one go, exactly as `UpdateDao#submit`
# does: the `pg_notify` rides along in the same statement, so there is no window in which a row
# exists that nobody was told about.
#
# ASSEMBLED BY CONCATENATION AND THAT IS SAFE HERE, because every one of the four values has been
# through a shape check first: the kind is one of UPDATE_KINDS, the scope matched the database's
# own regular expression, the delay is digits, and the requester has had every character outside
# [A-Za-z0-9._@-] replaced. None of them can carry a quote.
update_insert_sql() {
    local kind="$1" scope="$2" minutes="$3" requester="$4"
    local scope_sql="NULL"
    [[ -n "$scope" ]] && scope_sql="'$scope'"
    cat <<SQL
WITH inserted AS (
    INSERT INTO update_request (kind, source, requested_by, not_before, scope)
    VALUES ('$kind', 'CONSOLE', '$requester',
            now() + make_interval(mins => $minutes), $scope_sql)
    RETURNING id
), notified AS (
    SELECT pg_notify('nordtal_update', '') FROM inserted
)
SELECT inserted.id FROM inserted, notified;
SQL
}

# One line: the status, then a tab, then the report. `coalesce` rather than a NULL, so that the
# caller can split on the tab without having to know whether the run has written anything yet.
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

# --- sourced rather than executed ----------------------------------------------------------------
# Everything above this line is definitions; everything below reaches for Docker, the resolver and
# the filesystem. deploy/nordtal-test.sh sources this file to exercise the decisions above, the same
# way dev-test.sh sources deploy/dev - see the long comment there.
#
# IT IS SPELT OUT RATHER THAN `[[ "${BASH_SOURCE[0]}" == "$0" ]] || return 0`, which is what stood
# here and which this file can no longer use: under `curl ... | bash` there is no BASH_SOURCE at
# all, so reading it is an error under `set -u` - and if it were not, the `return` would then run
# at the top level of a script nobody sourced, which is an error of its own. A pipe therefore has
# to fall through here, and only a genuine `source` may return.
if [[ -n "${BASH_SOURCE[0]:-}" && "${BASH_SOURCE[0]}" != "$0" ]]; then
    return 0
fi

# --- update: the subcommand that is not an install (season-2-ops/153) ------------------------------
# Deliberately FIRST, above the argument parser and above §0's self-update. Starting a run is what
# somebody does when something is wrong, and a command that fetches a new copy of itself from
# GitHub before doing it would pick exactly that moment to need the network.

# One psql, inside the postgres container, reading its statement from stdin.
#
# THROUGH THE CONTAINER AND NOT A CLIENT ON THE HOST, which is the way the rest of this repository
# already reaches the database: there is no psql to install, no port to publish, and above all no
# password anywhere in the process tree - the container is already authenticated as its own user.
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

    # TWO VARIABLES AND NOT THE FILE. The same file holds the Discord token and the bunq key, and
    # `env_value` reads one name at a time on purpose - see IT NEVER PRINTS A SECRET at the top.
    local project user database container
    project="$(env_value "$UPDATE_ENV_FILE" COMPOSE_PROJECT_NAME)"
    project="${project:-$DEFAULT_PROJECT}"
    user="$(env_value "$UPDATE_ENV_FILE" POSTGRES_USER)"
    database="$(env_value "$UPDATE_ENV_FILE" POSTGRES_DB)"
    [[ -n "$user" && -n "$database" ]] \
        || die "POSTGRES_USER and POSTGRES_DB are not both set in $UPDATE_ENV_FILE"
    container="${project}-postgres-1"

    # `docker ps` and not `docker inspect`: the question is only whether it is running, and an
    # inspect of a container carrying a live secret is a door this repository keeps shut.
    # A here-string and not a pipe: `grep -q` stops reading at its first match, and under the
    # `pipefail` at the top of this file that is a SIGPIPE for docker - deploy/pipe-safety-test.sh
    # refuses the pattern outright.
    grep -qxF "$container" <<<"$(docker ps --format '{{.Names}}')" \
        || die "$container is not running, so there is nowhere to write the request.
       \`docker compose -p $project ps\` says what is up."

    local id
    id="$(update_insert_sql "$UPDATE_KIND" "$UPDATE_SCOPE" "$UPDATE_DELAY" "$(update_requester)" \
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

# Follows one request until the worker is finished with it, then prints the report the run wrote
# into its own row - the same report `/update` shows on a Minecraft console today.
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
            # jq IF IT IS THERE AND THE RAW LINE IF IT IS NOT. The report is one long line of JSON
            # and this is somebody reading it on a console in the middle of something going wrong;
            # requiring jq for that would be a dependency bought at exactly the wrong moment.
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
            # THE RUN IS NOT GIVEN UP ON, only the watching. The row is still there and the worker
            # is still on it; what ran out is this command's patience.
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

# --- arguments -------------------------------------------------------------------------------------
# Kept whole before they are parsed, because §0 below may hand them to a newer copy of this file.
ARGV=("$@")

FROM_FILE=""
ENV_FILE=""
ADDRESSES_GIVEN=""
CHECK_ONLY=false
BUILD_DEPLOYER=false
SELF_UPDATE=true
# THE MENU IS WHAT A PLAIN RUN IS NOW, and the three flags below are what turn it off: `--deploy`
# is the behaviour this script had before the menu existed, `--check` changes nothing at all and
# `--build` is a developer in a hurry. It is deliberately NOT "no arguments at all": §0 re-runs
# this file with --no-self-update in front of whatever was typed, so counting arguments would mean
# a renewed run never showing the menu - which is precisely the run that would need it most.
MENU=true

# The help text is the comment block at the top of this file, read out of whichever copy is at
# hand - and found by shape rather than by line number, because a line range written down here is a
# line range that goes stale the first time somebody adds a paragraph up there.
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

# WITHOUT A TERMINAL THERE IS NO MENU, and this is not a fallback so much as the same rule the
# prompts keep: a script that reads answers from a pipe is a script that reads a secret from a CI
# log. A run with no terminal asks nothing, writes nothing new, and either has everything it needs
# in the environment file or stops naming what is missing.
[[ -t 0 ]] || MENU=false

# --- 0 · which copy of this file is running, and where it came from ----------------------------------
# THE FIRST LINE OF OUTPUT IS WHICH VERSION THIS IS. A script that fetches its own replacement and
# runs it has to say so out loud, every time: the alternative is a host where "I ran nordtal.sh"
# and "I ran THIS nordtal.sh" are different sentences that look identical afterwards. The five
# functions this rests on are above the source guard with the other decisions, where
# deploy/nordtal-test.sh can reach them.
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
        # WITHOUT A NETWORK IT CARRIES ON. The one case it cannot carry on from is a pipe, because
        # then there is no copy anywhere to carry on WITH: bash has been reading this script off
        # stdin and cannot be asked for it again.
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

    # NOT INTO THE INSTALLATION DIRECTORY YET. Nothing is written there before the question below
    # has been answered, so the new version runs out of the temporary file and installs itself once
    # it knows it is welcome.
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
        # Piped in. The new copy needs a terminal to ask on, and stdin here is the script itself.
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

# Puts this file in the installation directory, which is what makes it the thing that maintains the
# installation afterwards. Called once the directory is settled and never under --check.
install_self() {
    running_from_a_file || return 0
    local source; source="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
    if [[ "$source" != "$INSTALLED" ]]; then
        install -m 755 "$source" "$INSTALLED" \
            || die "could not write $INSTALLED. The installation directory has to be writable - it
       is where every world and every database in this deployment is about to live."
        log "this script is now $INSTALLED - run it again to change a setting or to deploy"
    fi
    # The temporary copy a renewal ran from has done its job. Deleting it while bash is reading it
    # is safe on Linux: the open file survives the name.
    [[ -n "$SELF_TEMP" && -f "$SELF_TEMP" && "$SELF_TEMP" != "$INSTALLED" ]] && rm -f "$SELF_TEMP"
    return 0
}

# --- 0a · IS THIS THE DIRECTORY? The first question, before anything is looked at ----------------
# `curl ... | bash` runs in whatever directory the shell happened to be in, and that directory is
# about to become four worlds, a database and every backup this deployment takes. So it is said
# back before anything else happens - not as a path in a log line afterwards, as a question.
#
# It is asked ONCE per installation and not once per run: an environment file whose NORDTAL_DIR is
# this directory is this installation, and a person changing a setting should not have to agree to
# an install they did years ago. An environment file pointing SOMEWHERE ELSE is the interesting
# case and the one that stops the run - two installations sharing one environment file would share
# a database password, a project name and a certificate, and the first sign of it would be the
# second stack quietly adopting the first one's volumes.
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
is_absolute "$ENV_FILE" || die "--env-file has to be absolute, and '$ENV_FILE' is not. compose
       resolves a relative path against steward-deployer's project directory, which is INSIDE its
       image - so a relative path here points at a file that does not exist."

if [[ ! -f "$ENV_FILE" ]]; then
    if [[ -z "$FROM_FILE" && -f "$INSTALL_DIR/.env" ]]; then
        FROM_FILE="$INSTALL_DIR/.env"
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
#
# `ask_for`, `ask_question`, `default_for` and `set_secret` are defined above the seam, because
# `deploy/dev` uses them too (season-2-ops/147). What is below here is only the calls - which
# question this installation asks, in which order, and what it does with a no.

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
# The installation directory, absolute, and the reason it has to be written down at all: compose
# resolves a relative bind against steward-deployer's project directory, which is INSIDE its image.
# Every volume in compose.yml hangs off this one value - see the note at proxy's plugins/
# line - and §0a above has already established that it is this directory or nothing.
default_for NORDTAL_DIR           "$INSTALL_DIR"
default_for STEWARD_ENV_DIR       "$(dirname "$ENV_FILE")"
default_for STEWARD_ENV_FILE_NAME "$(basename "$ENV_FILE")"

# Everything in QUESTIONS except the two bunq ones, which are a pair and are asked for below.
#
# COMPOSE_PROFILES IS NOT ASKED FOR EITHER: `default_for` above has already written the production
# selection, so there is nothing missing for this loop to ask about. It is in the table for the
# menu, which is where somebody who wants a different selection changes it.
for question in "${QUESTIONS[@]}"; do
    case "$question" in
        NORDTAL_STEWARD_BUNQ_*|COMPOSE_PROFILES) continue ;;
    esac
    if [[ "$question" == EULA ]]; then
        # A licence that is refused is not an answer to record and not a deployment to continue.
        # `ask_for` writes nothing for a no; this is what it means.
        ask_question EULA || die "the EULA was not accepted, so there is nothing to deploy.
       Nothing has been changed beyond the environment file this script has been filling in."
        continue
    fi
    ask_question "$question"
done

# bunq is the one answer with a real "no". Without it nothing polls for payments and nobody can buy
# access; every other part of the deployment is unaffected. Saying so at the prompt is cheaper than a
# person inventing a key to get past a question.
#
# THE VARIABLES WERE RENAMED IN steward/109, from NORDTAL_BOT_BUNQ_* to NORDTAL_STEWARD_BUNQ_*: the
# key lives in steward-worker now and the bot has neither it nor the bunq SDK. A host whose
# environment file still carries the old names is answered by the block right below this one, which
# is the only thing between it and a stack where every container is healthy and no payment is ever
# noticed.
if ask_question NORDTAL_STEWARD_BUNQ_API_KEY; then
    ask_question NORDTAL_STEWARD_BUNQ_ACCOUNT_ID
fi

# THE OLD NAMES, AND WHY THIS BLOCK EXISTS (steward/109, steward/101).
#
# Both bunq variables are optional by design - a season without a bank account is a valid season -
# so neither compose nor any container complains about a name nothing reads. That is exactly what
# makes the rename dangerous: an environment file carrying NORDTAL_BOT_BUNQ_API_KEY hands it to
# nobody, every service comes up healthy, and the first sign is that no payment is ever noticed.
#
# The values are NOT copied across automatically. A bunq API key is installed against a device and
# an IP, and the context volume it produced belongs to the container that made it; the correct move
# is to put the key in under its new name and let steward-worker register a fresh context. Copying
# the old value is the one step that looks like it worked and then fails inside a poll.
for stale in NORDTAL_BOT_BUNQ_API_KEY NORDTAL_BOT_BUNQ_ACCOUNT_ID; do
    if [[ -n "$(env_value "$ENV_FILE" "$stale")" ]]; then
        warn "$ENV_FILE still has $stale. Nothing reads it any more - bunq moved into
       steward-worker in steward/109 and the names are NORDTAL_STEWARD_BUNQ_API_KEY and
       NORDTAL_STEWARD_BUNQ_ACCOUNT_ID. Delete the old line once the new one is in, and read
       steward-worker's first log line after the next deploy: it says 'bunq is ON' or 'bunq is
       OFF' in one sentence, and that sentence is the only confirmation there is."
    fi
done

# --- 2b · the menu ------------------------------------------------------------------------------
# WHAT THIS IS FOR (season-2-ops/124): until now this script could only fill in what was MISSING. A
# value that was already there was reported and left alone, so changing one - a bot token that was
# reset, a guild that moved, an admin role that was recreated - meant editing the environment file
# by hand, which is the one thing this script exists to make unnecessary. The menu is the other
# half: it shows what is set, lets one be picked and typed again, and deploys at the end.
#
# A SECRET IS THREE DOTS AND NOTHING ELSE. See `shown_value` - the menu is the place a person reads
# their configuration back, which makes it the place a token would end up in a screenshot.
#
# It is the run with no arguments, on a terminal. `--deploy` is the old behaviour and is what an
# unattended run uses; `--check` and `--build` bypass it too, because neither is a person sitting
# in front of a list.
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
                # The two bunq variables are a pair, and steward-worker refuses to start with a key
                # and no account. Somebody who has just put a key in is asked for the account there
                # and then rather than being let out of the menu with half of it.
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

# AND WHAT "ADOPTING" MEANS SINCE season-2-ops/124, which is the one thing about it that changed:
# the volumes are still there and this deployment no longer mounts them. Every volume in compose.yml
# defaults to a directory under NORDTAL_DIR now, so a host that was installed before that keeps its
# `${PROJECT}_postgres-data` and starts an EMPTY database beside it. Nothing is deleted - the
# volumes stay exactly where they are and `docker volume ls` still shows them - but a stack that
# comes back with an empty world is not a thing to discover afterwards, so it is said here and it
# has to be agreed to.
#
# There is deliberately no copying step. Moving a volume into a directory is `docker run --rm -v
# old:/from -v new:/to alpine cp -a`, one line per volume, and it is a line somebody types while
# looking at the result - not something a setup script does to a host on its way past.
if $ADOPTING && ! $CHECK_ONLY; then
    if docker volume inspect "${PROJECT}_postgres-data" >/dev/null 2>&1 \
        && [[ ! -d "$INSTALL_DIR/postgres-data" ]]; then
        warn "this host carries the volumes of an earlier installation (${PROJECT}_postgres-data
       among them), and this deployment does not mount them any more: since 2026-09-19 every volume
       is a directory under $INSTALL_DIR. Nothing here deletes them - they stay on the disk and
       `docker volume ls` still lists them - but the stack will come up on EMPTY directories: a new
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
#
# `set_secret` itself is above the seam with the other three; this is where it is used.
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

# --- 4b · the Web Push keypair, minted rather than asked for (steward/118) --------------------------
# steward/117 wired the two variables through compose.yml; what it left open is that a fresh
# installation never got asked, and web-push then sits silently unconfigured (a WARN line at
# startup is the only sign - see StewardUi.main). THE KEYPAIR IS NOT A SECRET SOMEBODY HOLDS
# ELSEWHERE, unlike the Discord token or a bunq key: nobody has one before this runs, it is minted
# here, so - like POSTGRES_PASSWORD and the two tokens above - it belongs beside `set_secret`, not
# in the "questions" block. It needs no terminal.
#
# UNLIKE set_secret THIS NEEDS AN IMAGE, not just openssl - StewardUi.java's own comment for
# `generate-vapid-keys` says why that is still cheap: "No database, no config directory, nothing
# this deployment already has" - it is one call into com.interaso.webpush and two lines on stdout,
# so a bare `docker run --rm <image> generate-vapid-keys`, run before steward-ui is ever brought
# up, is the whole thing - the same shape §6 already pulls the deployer image with.
#
# BOTH OR NEITHER: WebPushSpec reads a half-filled pair as broken, not as "not configured" (two
# blanks is what that means). A call here that finds exactly one of the two already set treats the
# pair as unusable and replaces both, rather than trying to keep half of a mismatched key.
#
# THE IMAGE IS PULLED ONLY IF NONE IS ALREADY ON THIS HOST. §5a exists because `up` silently
# replaces a locally built image with whatever the registry currently serves under the same tag -
# an unconditional pull here, ahead of that check, would do exactly the same thing to steward-ui
# before anybody had a chance to be warned. A host that already carries an image (built here, or
# pulled by an earlier run) uses it as it stands; only a host with no steward-ui image at all - the
# fresh-install case this ticket is about - reaches for the registry.
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

# --- 4c · the directories the deployment lives in --------------------------------------------------
# Created here rather than left to Docker, and the difference is one directory out of eighteen.
#
# Docker creates a missing bind source itself - as root, mode 755 - which is right for seventeen of
# these and wrong for steward-ui-config: the interface runs as uid 10001 (its Dockerfile explains
# why at length) and a root-owned directory leaves it unable to write its own steward-ui.yml. That
# does not fail loudly. It fails as a settings page that saves and changes nothing, which is the
# same shape of quiet failure the /configs mounts were moved to steward-worker for.
#
# A NAMED VOLUME USED TO DO THIS FOR FREE: Docker copies the image's content AND its ownership into
# an empty volume on first use, and copies nothing into a bind. That is the one thing the move to
# directories costs, and this is where it is paid.
#
# Existing directories are left exactly as they are, ownership included: a second run must not
# reach into a world.
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

# --- 5a · images this host built itself, about to be silently replaced (steward/107) ----------------
# §7's `up` pulls EVERY image compose.yml names before it stops anything, and that pull is not
# "whatever is missing": compose.yml has no `pull_policy` anywhere, and the deployer image §7 runs
# does not lean on one either - steward-deployer/src/main/java/eu/nordtal/s2/steward/deployer/
# Compose.java's `pull()` runs a plain `docker compose pull <service>` for every service in
# COMPOSE_PROFILES, unconditionally, before `up` ever starts (read 2026-09-17). The ONE tolerance
# in there is the other direction: a pull that fails outright (denied or not found) keeps the local
# image, which is what lets steward-ui run before its first release. A pull that SUCCEEDS is never
# compared to what is already here - so a tag the registry still answers for is replaced by whatever
# that is, even if this host's copy is newer. That is exactly the shape of the deploy/README.md
# workaround for shipping without a release (`docker compose build <service>` then
# `up -d --no-deps <service>`): it survives until the next `nordtal.sh` run undoes it, silently.
#
# WHETHER AN IMAGE HAS A RepoDigest IS NOT THE SIGNAL - see the long comment on at_risk_images. What
# this section actually asks, per image already on this host, is whether `docker buildx imagetools
# inspect` currently sees a DIFFERENT manifest digest under the same tag; RepoDigests only tells the
# other half, whether that mismatch is the whole story or the registry could not be asked at all.
# WHERE compose.yml COMES FROM NOW THAT THERE IS NO CHECKOUT. It is inside steward-deployer's
# image (§8b) and nowhere else on this host, so it is copied out of whatever copy of that image is
# already here. A host that has none is a host that has never deployed, which is exactly the host
# this whole section has nothing to warn about - so it skips, the same graceful shape the missing
# jq and the missing buildx get below.
#
# THE IMAGE IS NOT PULLED FOR THIS. §6 pulls it, after the question this section asks; pulling it
# here would replace a locally built deployer before anybody was warned that it was about to be.
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

# The docker calls at_risk_images (above the source guard) needs, and nothing else: the
# service/image pairs compose.yml resolves to under this deployment's profiles, the RepoDigests of
# every distinct image among them that is already on this host, and what the registry currently
# serves under each of those tags.
#
# jq AND buildx ARE NOT PREREQUISITES OF THIS SCRIPT, and deliberately so. An earlier draft made jq
# one and died in §1 without it - which is the wrong trade: this check protects images this host
# BUILT ITSELF, and a host fresh enough to be missing either tool has none yet. Refusing to bootstrap
# a new machine over a warning that has nothing to warn about is worse than not warning; a warning
# and a graceful skip is the shape both tools get.
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

        # A failure here - private repository, network, a tag never pushed - is not "safe": it goes
        # into UNKNOWN below, which is why it is NOT `|| continue`. Written as `|| reg=""` rather
        # than a bare failing assignment because `set -e` treats a failing command substitution
        # assigned on its own as a failure of the whole script, the same trap documented on
        # at_risk_images' own loop further up this file.
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

# --- 6 · renew steward-deployer ---------------------------------------------------------------------
# The one image nothing inside the stack can replace. compose.yml is baked into it, so this step is
# also how a changed deployment reaches this host at all.
if $BUILD_DEPLOYER; then
    # The alpha's way in, and it needs a JDK and a checkout. It is also the only way while the image
    # is not published - which it is, from the first release that carries it.
    #
    # THIS IS THE ONE FLAG THAT NEEDS A REPOSITORY, and since the installation is a directory rather
    # than a checkout it has to be told where one is: `--build` run from inside a checkout uses that
    # one, and anywhere else it says so rather than building nothing.
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
log "The installation is $INSTALL_DIR - the worlds, the database and the backups are directories"
log "in there, and \`./nordtal.sh\` is how a setting is changed or a new release deployed."
