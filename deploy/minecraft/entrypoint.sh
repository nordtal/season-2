#!/usr/bin/env bash
#
# PID 1 for every Minecraft service. Four jobs, in order:
#
#   1. resolve and cache the pinned server jar (PaperMC Fill API)
#   2. pull the pinned plugin jars from the GitHub release, cache-first
#   3. start the server inside a tmux session, so that `docker exec` has a writable console
#   4. translate SIGTERM into a graceful shutdown and wait for the JVM to finish saving
#
# Step 4 is why this script exists at all: with tmux in the picture and no trap, `docker stop`
# would kill a wrapper and leave the JVM to be SIGKILLed at the end of stop_grace_period, which
# is how worlds get half-written. See docs/operations.md#the-server-containers.
set -Eeuo pipefail

log()  { printf '[nordtal] %s\n' "$*"; }
warn() { printf '[nordtal] WARN: %s\n' "$*" >&2; }
die()  { printf '[nordtal] FATAL: %s\n' "$*" >&2; exit 1; }

# --- inputs ----------------------------------------------------------------------------------
: "${SERVER_KIND:?set SERVER_KIND to paper or velocity}"
: "${SERVER_VERSION:?set SERVER_VERSION (paper: 26.2, velocity: 4.1.1)}"
: "${SERVER_BUILD:?set SERVER_BUILD - the exact build number, never 'latest'}"

case "$SERVER_KIND" in
    paper|velocity) ;;
    *) die "SERVER_KIND must be 'paper' or 'velocity', not '${SERVER_KIND}'" ;;
esac

DATA=/data
CACHE="$DATA/.server"
PLUGINS="$DATA/plugins"
SOCK="${MC_TMUX_SOCKET:-/run/mc/tmux.sock}"
SESSION="${MC_TMUX_SESSION:-mc}"

# The Fill API requires a User-Agent that identifies the project and a contact.
FILL_API="https://fill.papermc.io/v3/projects"
FILL_UA="nordtal-season-2/deploy (+https://github.com/nordtal/season-2)"

SEASON_REPO="${SEASON_REPO:-nordtal/season-2}"

mkdir -p "$CACHE" "$PLUGINS" "$(dirname "$SOCK")"

# --- the server jar --------------------------------------------------------------------------
# Cache-first. Fill's download URLs are content-addressed (fill-data.papermc.io/v1/objects/<sha256>)
# and cannot be constructed by hand, so a cache miss means an API call. A cache HIT means no
# network at all, which is what keeps a GitHub or PaperMC outage from blocking a restart.
JAR_NAME="${SERVER_KIND}-${SERVER_VERSION}-${SERVER_BUILD}.jar"
JAR_PATH="$CACHE/$JAR_NAME"

if [[ -f "$JAR_PATH" ]]; then
    log "server jar cached: ${JAR_NAME}"
else
    log "resolving ${SERVER_KIND} ${SERVER_VERSION} build ${SERVER_BUILD} through the Fill API"
    meta=$(curl -fsSL --max-time 60 -H "User-Agent: ${FILL_UA}" \
        "${FILL_API}/${SERVER_KIND}/versions/${SERVER_VERSION}/builds/${SERVER_BUILD}") \
        || die "could not reach the Fill API, and ${JAR_NAME} is not cached in ${CACHE}. Refusing to start: this container has no server to run."

    url=$(jq -er '.downloads."server:default".url' <<<"$meta") \
        || die "the Fill API knows no 'server:default' download for ${SERVER_KIND} ${SERVER_VERSION} build ${SERVER_BUILD}. Check the pin in .env against https://fill.papermc.io/v3/projects/${SERVER_KIND}"
    sha=$(jq -er '.downloads."server:default".checksums.sha256' <<<"$meta") \
        || die "the Fill API returned a download without a sha256 checksum"
    channel=$(jq -r '.channel // "UNKNOWN"' <<<"$meta")
    [[ "$channel" == "STABLE" ]] || warn "build ${SERVER_BUILD} is channel ${channel}, not STABLE"

    tmp="${JAR_PATH}.partial"
    curl -fsSL --max-time 600 -H "User-Agent: ${FILL_UA}" -o "$tmp" "$url" \
        || { rm -f "$tmp"; die "downloading ${JAR_NAME} failed"; }

    actual=$(sha256sum "$tmp" | cut -d' ' -f1)
    [[ "$actual" == "$sha" ]] \
        || { rm -f "$tmp"; die "checksum mismatch for ${JAR_NAME}: expected ${sha}, got ${actual}"; }

    mv "$tmp" "$JAR_PATH"
    log "downloaded and verified ${JAR_NAME}"

    # One pinned build per kind; older jars are 40-70 MB each and nothing refers to them.
    shopt -s nullglob
    for old in "$CACHE/${SERVER_KIND}-"*.jar; do
        [[ "$old" != "$JAR_PATH" ]] && { rm -f "$old"; log "removed superseded ${old##*/}"; }
    done
    shopt -u nullglob
fi

# --- plugins ---------------------------------------------------------------------------------
# Cache-first with a hard failure only on a jar that is required and absent. A jar's filename
# carries its version, so "is the pinned version already here" is a file-existence test, and a
# version bump is automatically a cache miss. The container NEVER falls back to an older jar:
# "the server is up, running last week's plugin" is the fault that gets discovered late.
fetch_plugin() {
    local file="$1" url="$2" dest="$PLUGINS/$1" base tmp

    if [[ -f "$dest" ]]; then
        log "plugin cached: ${file}"
        return 0
    fi

    log "fetching plugin ${file}"
    tmp="${dest}.partial"
    curl -fsSL --max-time 300 -o "$tmp" "$url" \
        || { rm -f "$tmp"; die "could not fetch ${file} from ${url}, and it is not present in ${PLUGINS}. Refusing to start rather than running an older jar. If GitHub is up, check that the release tag and the asset name in .env are right."; }
    mv "$tmp" "$dest"

    # Drop earlier versions of the same plugin only once the new one is safely in place.
    # plugins/<name>/ data folders are untouched - only the jar is versioned.
    base="${file%-*.jar}"
    shopt -s nullglob
    for old in "$PLUGINS/${base}-"*.jar; do
        [[ "$old" != "$dest" ]] && { rm -f "$old"; log "removed superseded ${old##*/}"; }
    done
    shopt -u nullglob
}

if [[ -n "${SEASON_PLUGINS:-}" ]]; then
    : "${SEASON_RELEASE:?SEASON_PLUGINS is set, so SEASON_RELEASE must name the release tag}"
    for file in $SEASON_PLUGINS; do
        fetch_plugin "$file" \
            "https://github.com/${SEASON_REPO}/releases/download/${SEASON_RELEASE}/${file}"
    done
fi

# Third-party jars that are not ours: DisplayTags and PacketEvents on the SMP server, and
# CoreProtect if it ever ships for 26.2. Full URLs, because they come from other releases.
if [[ -n "${EXTRA_PLUGIN_URLS:-}" ]]; then
    for url in $EXTRA_PLUGIN_URLS; do
        fetch_plugin "${url##*/}" "$url"
    done
fi

# --- per-kind preparation --------------------------------------------------------------------
JAVA_ARGS=()
if [[ "$SERVER_KIND" == "paper" ]]; then
    # Minecraft's EULA has to be accepted by the operator, not by an image default.
    [[ "${EULA:-}" == "true" ]] \
        || die "set EULA=true to accept https://aka.ms/MinecraftEULA - this is deliberately not defaulted"
    printf 'eula=true\n' > "$DATA/eula.txt"
    JAVA_ARGS+=(nogui)
else
    # Velocity reads the modern-forwarding secret from the file named in velocity.toml
    # (forwarding-secret-file). Writing it here keeps it out of the volume's committed config and
    # in the environment, where every other secret in this project lives. The SAME secret has to
    # be in each Paper backend's config/paper-global.yml - see deploy/README.md.
    if [[ -n "${VELOCITY_FORWARDING_SECRET:-}" ]]; then
        printf '%s' "$VELOCITY_FORWARDING_SECRET" > "$DATA/forwarding.secret"
        chmod 600 "$DATA/forwarding.secret"
    fi
fi

JVM_OPTS="${JVM_OPTS:--Xms${HEAP:-2G} -Xmx${HEAP:-2G} -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+DisableExplicitGC -XX:+AlwaysPreTouch}"

# --- start it inside tmux --------------------------------------------------------------------
# Arcane's per-container shell is a `docker exec` and therefore cannot reach PID 1's stdin. tmux
# is what makes the console writable from there; `console` attaches, `mc <cmd>` sends one command.
log "starting ${SERVER_KIND} ${SERVER_VERSION} build ${SERVER_BUILD}"
log "console: run 'console' in this container to attach, or 'mc <command>' to send one command"

rm -f "$SOCK"
tmux -S "$SOCK" new-session -d -s "$SESSION" -c "$DATA" -x 200 -y 50 \
    "exec java ${JVM_OPTS} -jar '${JAR_PATH}' ${JAVA_ARGS[*]:-}"

# Keep the pane after the JVM exits, so its exit status can be read and reported as ours.
tmux -S "$SOCK" set-option -t "$SESSION" remain-on-exit on
# Mirror the server log to this process's stdout, so `docker logs` and Arcane's log view keep
# showing everything they would have shown without tmux.
#
# THIS IS DELIBERATELY `tail -F` AND NOT `tmux pipe-pane ... > /proc/1/fd/1`, which is the obvious
# way to do it and is a trap. Measured 2026-09-01 on Docker 29.4.1: a pipe-pane writer holding a
# second handle on the container's stdout pipe wedges the container completely - SIGTERM never
# reaches PID 1, the shutdown trap never runs, and the container survives even SIGKILL, leaving a
# `docker rm -f` that fails with "did not receive an exit event". With the same image and only
# this line removed, `docker stop` finishes in one second with exit 143. `tail` is a plain child
# of PID 1 inheriting its stdout, which is the ordinary case and does not do that.
#
# It also reads better: the log file carries no terminal escape sequences, so the container log is
# clean while the tmux console keeps its colours for whoever is attached.
LOG_FILE="$DATA/logs/latest.log"
mkdir -p "$DATA/logs"
tail -n 0 -F "$LOG_FILE" 2>/dev/null &
TAIL_PID=$!

# --- graceful shutdown -----------------------------------------------------------------------
# Both Paper and Velocity install a JVM shutdown hook that saves and stops on SIGTERM, so the
# signal is forwarded to the JVM itself rather than typed into the console: that avoids depending
# on a command name which differs between the two ('stop' vs 'shutdown').
#
# compose sets stop_grace_period to 180s. The 10s default does not save a border-4000 world, and
# a save cut off halfway is the failure that stays invisible for days.
shutting_down=0
on_term() {
    [[ $shutting_down -eq 1 ]] && return 0
    shutting_down=1
    local pid
    pid=$(tmux -S "$SOCK" display-message -p -t "$SESSION" '#{pane_pid}' 2>/dev/null || true)
    if [[ -n "$pid" ]]; then
        log "shutdown requested - SIGTERM to the server (pid ${pid}), waiting for it to save"
        kill -TERM "$pid" 2>/dev/null || true
    else
        warn "shutdown requested but the server process could not be found"
    fi
}
trap on_term TERM INT

while :; do
    dead=$(tmux -S "$SOCK" display-message -p -t "$SESSION" '#{pane_dead}' 2>/dev/null || echo 1)
    [[ "$dead" == "1" ]] && break
    sleep 1 & wait $! || true
done

status=$(tmux -S "$SOCK" display-message -p -t "$SESSION" '#{pane_dead_status}' 2>/dev/null || echo 1)
# Let the tail catch up on whatever the server wrote while shutting down, then stop holding stdout.
sleep 1 & wait $! || true
kill "$TAIL_PID" 2>/dev/null || true
tmux -S "$SOCK" kill-server 2>/dev/null || true
log "server exited with status ${status:-1}"
exit "${status:-1}"
