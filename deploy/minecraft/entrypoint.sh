#!/usr/bin/env bash
#
# PID 1 for every Minecraft service. Four jobs, in order:
#
#   1. resolve and cache the pinned server jar (PaperMC Fill API)
#   2. refuse to start on an empty plugins folder - the `updater` container fills it
#   3. start the server inside a tmux session, so that `docker exec` has a writable console
#   4. translate SIGTERM into a graceful shutdown and wait for the JVM to finish saving
#
# Step 4 is why this script exists at all: with tmux in the picture and no trap, `docker stop`
# would kill a wrapper and leave the JVM to be SIGKILLed at the end of stop_grace_period, which
# is how worlds get half-written. See ../README.md#why-it-looks-like-this.
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
# THIS SCRIPT NO LONGER FETCHES PLUGINS. It did until 2026-09-01, pulling `<module>-$SEASON_VERSION
# .jar` from a GitHub release and deleting every other version of the same plugin by filename
# prefix. The `updater` container owns the plugin jars now (../../docs/updater.md), and the two
# cannot both own them: an updater that puts 0.3.0 into this volume while .env still said 0.2.0
# would have the next restart delete exactly the jar it had just fetched.
#
# What this container still owns is the server jar above and the datapacks below - neither of
# which the updater touches, and both of which have to be right before the JVM starts.
#
# WHAT WAS LOST WITH IT, and what replaces it: the old code refused to start rather than run an
# older jar, which is a property worth keeping. It is kept in a coarser form - an empty plugins
# folder stops the container. Every service in this deployment has at least one plugin, so "no
# jars at all" means the updater has never run against this volume, and a Minecraft server that
# comes up with no season on it is the failure that gets discovered by a player.
if [[ "${ALLOW_NO_PLUGINS:-false}" != "true" ]]; then
    shopt -s nullglob
    installed=("$PLUGINS"/*.jar)
    shopt -u nullglob
    if (( ${#installed[@]} == 0 )); then
        die "no plugin jars in ${PLUGINS}. This container does not fetch them any more - the updater does. Run it once against this stack:

    docker compose run --rm updater apply

Refusing to start: a Minecraft server with no plugins is a server with no season on it, and nothing about it looks wrong until somebody joins. Set ALLOW_NO_PLUGINS=true if a server with no plugins really is what you want."
    fi
    log "plugins present: ${#installed[@]} jar(s)"
fi

# --- world-generation datapacks ----------------------------------------------------------------
# Terralith and Dungeons and Taverns, pinned, into the level-name world's datapacks/ folder.
#
# WHY THAT FOLDER AND NO OTHER, measured on Paper 26.2 build 121 on 2026-09-01: datapacks are
# server-global. A probe pack in <level-name>/datapacks/ was listed and enabled; an identical probe
# in a secondary world's own datapacks/ folder was never seen - not at start, not after that world
# was created, not after refreshPacks(). There is no per-world datapack API. So one folder feeds
# every world the server generates, the nightly farm world included.
#
# WHY BEFORE THE SERVER STARTS: worldgen registries are read once, at start. A pack dropped in
# afterwards changes no terrain, and terrain is never re-rolled once it is on disk - a farm world
# generated without Terralith is one flat day, but Nordtal generated without it is the whole season
# on a world that has a spawn built on it and therefore cannot be thrown away. The smp plugin
# refuses to enable when a required pack is missing, which turns "wrong terrain forever" into "the
# server did not come up", and that is the trade on purpose.
#
# Format of DATAPACK_URLS: whitespace-separated entries, each either a bare URL or <sha512>@<url>.
# sha512 and not sha256 because that is what Modrinth actually publishes for a file - the pin can
# then be copied straight out of the API response instead of being computed by hand, and a pin
# nobody can re-derive is a pin that rots.
# The checksum is optional but wanted: a datapack that silently changes version between Nordtal's
# one-off pre-generation and a nightly farm world is two worlds that stop looking like each other,
# and nothing reports it.
fetch_datapacks() {
    local dir="$1" spec url sha file dest tmp actual

    mkdir -p "$dir"
    for spec in $DATAPACK_URLS; do
        if [[ "$spec" == *"@"* ]]; then
            sha="${spec%%@*}"
            url="${spec#*@}"
        else
            sha=""
            url="$spec"
        fi

        # The name Paper reports is the name on disk, so percent-escapes have to come back out -
        # "Dungeons%20and%20Taverns%20v5.3.2.zip" would otherwise never match a required-datapacks
        # entry of "Dungeons and Taverns".
        file="${url##*/}"
        file="${file%%\?*}"
        file="$(printf '%b' "${file//%/\\x}")"
        dest="${dir}/${file}"

        if [[ -f "$dest" ]]; then
            log "datapack cached: ${file}"
            continue
        fi

        log "fetching datapack ${file}"
        tmp="${dest}.partial"
        curl -fsSL --max-time 300 -o "$tmp" "$url" \
            || { rm -f "$tmp"; die "could not fetch datapack ${file} from ${url}. Refusing to start: a world generated without its datapacks is vanilla terrain permanently."; }

        if [[ -n "$sha" ]]; then
            actual="$(sha512sum "$tmp" | cut -d' ' -f1)"
            if [[ "$actual" != "$sha" ]]; then
                rm -f "$tmp"
                die "datapack ${file} does not match its pinned sha512 (wanted ${sha}, got ${actual}). Refusing to start rather than generating tomorrow's farm world from a different pack version than Nordtal."
            fi
        else
            log "WARNING: ${file} has no pinned checksum - a silent version change would not be noticed"
        fi

        mv "$tmp" "$dest"
    done
}

# --- first-start configuration -----------------------------------------------------------------
# Everything below is SEEDING, not editing: a file that already exists is left alone and belongs
# to the operator from then on. The one exception is server.properties#online-mode, which is
# enforced on every start because a proxied backend that authenticates players itself does not
# start at all - see prepare_backend.

# Sets key=value in a Java properties file, creating the file if it is not there yet. Idempotent,
# and it says so in the log when it actually changes something.
set_property() {
    local file="$1" key="$2" value="$3" tmp
    if [[ -f "$file" ]] && grep -qE "^${key}=" "$file"; then
        grep -qxF "${key}=${value}" "$file" && return 0
        tmp="${file}.tmp"
        sed "s|^${key}=.*|${key}=${value}|" "$file" > "$tmp" && mv "$tmp" "$file"
        log "${file##*/}: ${key} set to ${value}"
    else
        printf '%s=%s\n' "$key" "$value" >> "$file"
        log "${file##*/}: ${key}=${value} added"
    fi
}

# A Paper server that sits behind the proxy. Two things have to be true and neither of them is
# Paper's default, which is why this used to be two manual steps per backend in the runbook.
prepare_backend() {
    local global="$DATA/config/paper-global.yml"

    # The proxy is what authenticates; a backend doing it as well refuses every forwarded login.
    # Enforced on every start rather than seeded: online-mode=true here is not a preference, it
    # is a server that cannot work, and Paper writes the file itself on first start.
    set_property "$DATA/server.properties" online-mode false

    # The secret itself is not seeded: Paper reads PAPER_VELOCITY_SECRET from the environment
    # (PaperMC/Paper#10127), which is what removes the manual paste into three separate files.
    # It does NOT keep the secret out of the volume - verified 2026-09-01 on Paper 26.2 build
    # 121, Paper writes the value it took from the environment straight into paper-global.yml on
    # first load. Rotating it means changing .env AND that line in each backend.
    #
    # What Paper has no environment variable for is the switch that turns modern forwarding on,
    # so that much is seeded here. Paper fills in every other key with its defaults on first
    # load - measured: a four-line file comes back as the full ~150-line config, with a warning
    # that it had no version set.
    if [[ -f "$global" ]]; then
        log "config/paper-global.yml exists - not touched. Modern forwarding has to be enabled in it (proxies.velocity.enabled: true)."
    else
        mkdir -p "$(dirname "$global")"
        cat > "$global" <<'YAML'
# Seeded by the nordtal entrypoint on first start, and not touched again. Paper adds every other
# setting with its default the first time it loads this file.
#
# proxies.velocity.secret is absent here because it arrives as PAPER_VELOCITY_SECRET from the
# environment. Paper writes it into this file on first load, so it does end up in this volume -
# the environment variable saves the manual paste, not the copy on disk.
proxies:
  velocity:
    enabled: true
    online-mode: true
YAML
        log "seeded config/paper-global.yml with Velocity modern forwarding enabled"
    fi
}

# The proxy's own config. Only the settings this deployment cannot work without; Velocity applies
# its defaults to everything a config file leaves out, so this stays short instead of freezing a
# copy of Velocity's 200-line default that would go stale on the next upgrade.
#
# [forced-hosts] IS WRITTEN EMPTY ON PURPOSE, and it is not decoration. Measured 2026-09-01 with
# Velocity 4.1.1 build 24: leave the table out and Velocity falls back to its default one, which
# routes lobby.example.com/factions.example.com/minigames.example.com at servers this file does
# not define - and it then refuses to start at all ("Your configuration is invalid"). "Velocity
# defaults the rest" is true per key, not per table.
seed_velocity_config() {
    local file="$DATA/velocity.toml" name address tmp

    if [[ -f "$file" ]]; then
        log "velocity.toml exists - not touched"
        return 0
    fi
    if [[ -z "${VELOCITY_SERVERS:-}" ]]; then
        warn "no velocity.toml and VELOCITY_SERVERS is unset, so Velocity will write its own default: forwarding off and three example servers on 127.0.0.1. Nobody can join through that."
        return 0
    fi

    # Checked before a byte is written. A malformed entry used to abort halfway through the
    # redirection below, and a half-written velocity.toml is indistinguishable from an operator's
    # own on the next start - seeded once means there is no second chance to get it right.
    for entry in $VELOCITY_SERVERS; do
        [[ "$entry" == *=* ]] || die "VELOCITY_SERVERS entries are name=host:port, not '${entry}'"
    done

    tmp="${file}.partial"
    {
        printf '# Seeded by the nordtal entrypoint on first start, and not touched again.\n'
        printf '# Everything Velocity is not told here keeps its own default.\n'
        printf 'config-version = "2.8"\n'
        printf 'bind = "0.0.0.0:25565"\n'
        printf 'online-mode = true\n'
        printf 'player-info-forwarding-mode = "modern"\n'
        printf 'forwarding-secret-file = "forwarding.secret"\n\n'
        printf '[servers]\n'
        for entry in $VELOCITY_SERVERS; do
            name="${entry%%=*}"
            address="${entry#*=}"
            printf '%s = "%s"\n' "$name" "$address"
        done
        printf 'try = ["%s"]\n\n' "${VELOCITY_TRY:-${VELOCITY_SERVERS%%=*}}"
        printf '[forced-hosts]\n'
    } > "$tmp" || { rm -f "$tmp"; exit 1; }
    mv "$tmp" "$file"
    log "seeded velocity.toml: modern forwarding, servers ${VELOCITY_SERVERS}"
}

# --- per-kind preparation --------------------------------------------------------------------
JAVA_ARGS=()
if [[ "$SERVER_KIND" == "paper" ]]; then
    # Minecraft's EULA has to be accepted by the operator, not by an image default.
    [[ "${EULA:-}" == "true" ]] \
        || die "set EULA=true to accept https://aka.ms/MinecraftEULA - this is deliberately not defaulted"
    printf 'eula=true\n' > "$DATA/eula.txt"
    JAVA_ARGS+=(nogui)

    # Before anything generates. LEVEL_NAME has to agree with server.properties#level-name; it is
    # the world Paper reads datapacks from and the world every other one is created inside.
    if [[ -n "${DATAPACK_URLS:-}" ]]; then
        fetch_datapacks "$DATA/${LEVEL_NAME:-world}/datapacks"
    fi

    # A Paper server that is given a forwarding secret is by definition a backend behind the
    # proxy. That is the whole switch: one variable, and the two settings that follow from it are
    # applied rather than written into a runbook.
    #
    # Spelled as an `if` and not as `[[ ... ]] && prepare_backend`: the AND-list form is exempt
    # from `set -e` and would be fine, but this script is PID 1 and "the container exits before
    # the server starts" is not a failure worth being clever about.
    if [[ -n "${PAPER_VELOCITY_SECRET:-}" ]]; then
        prepare_backend
    fi
else
    # Velocity reads the modern-forwarding secret from the file named in velocity.toml
    # (forwarding-secret-file). Writing it here keeps it out of the volume's committed config and
    # in the environment, where every other secret in this project lives.
    if [[ -n "${VELOCITY_FORWARDING_SECRET:-}" ]]; then
        printf '%s' "$VELOCITY_FORWARDING_SECRET" > "$DATA/forwarding.secret"
        chmod 600 "$DATA/forwarding.secret"
    fi
    seed_velocity_config
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
