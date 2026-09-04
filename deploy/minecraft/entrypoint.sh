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

# The server directory, and the world inside it Paper treats as primary. Both are resolved here,
# above everything else, because what follows them is the half of this script that
# entrypoint-test.sh SOURCES and exercises against fixture directories instead of a real volume.
#
# DATA is overridable for that reason and for no other - nothing in the container ever sets it,
# and it is not a knob offered to an operator. The image mounts the volume at /data, the Dockerfile
# says so, and a second answer to "where is the server directory" is not something this deployment
# should have.
DATA="${DATA:-/data}"

# Paper's own default. The one place LEVEL_NAME is resolved: seed_level_settings writes it into
# server.properties and fetch_datapacks reads the same value, so the two cannot disagree.
LEVEL_NAME="${LEVEL_NAME:-world}"

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

# Repairs the one level-name disagreement that was never anybody's decision, and answers whether it
# did. Returns 0 when the volume has been adopted and the old world is gone; 1 when the caller must
# refuse to start.
#
# TWO CONDITIONS, AND BOTH ARE NARROW ON PURPOSE. This function deletes a world folder without
# asking, in an automatic start, so what it is allowed to delete has to be a shape that cannot be
# anything but junk:
#
#   1. THE OLD NAME IS LITERALLY `world`. That is Paper's own default, the value it writes when
#      nothing has told it otherwise - which is exactly what every volume from before v0.2.3 has,
#      because the key was not written then. Any other name was typed by a person into .env, and a
#      name somebody chose is a decision this script does not get to overrule. It also means the
#      repair happens at most once per volume: after this, level-name is `nordtal` or
#      `hunger_games`, and a later mismatch can only be an edit to .env - the case the refusal is
#      for.
#   2. NOBODY HAS EVER LOGGED OUT IN IT. Paper writes <world>/playerdata/<uuid>.dat when a player
#      leaves, so a single file there means somebody was in this world and it is not ours to bin.
#      An empty or absent folder means Paper generated terrain that no one has seen.
#
# `limbo` never reaches here and is the proof the first condition is not arbitrary: it deliberately
# has no LEVEL_NAME (compose.yml), so its level-name IS `world`, matches, and never disagrees.
#
# WHAT GOES WITH IT: world_nether and world_the_end, which are that same world's two dimensions
# under Paper's naming. Deleting the overworld and leaving its Nether behind would keep gigabytes
# of data belonging to a world nothing can reach any more.
adopt_paper_default_world() {
    local current="$1" playerdata="$DATA/world/playerdata" dimension

    [[ "$current" == "world" ]] || return 1

    # -print -quit stops at the first entry rather than listing the folder, and unlike `ls -A` it
    # says nothing on stdout that has to be filtered back out.
    if [[ -d "$playerdata" ]] \
        && [[ -n "$(find "$playerdata" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
        warn "this volume's world is called 'world' (Paper's default, from before this image wrote level-name) and LEVEL_NAME says '${LEVEL_NAME}' - but somebody has played in it, so it is not being thrown away automatically."
        return 1
    fi

    warn "this volume carries level-name=world, Paper's default from before this image wrote the key, while LEVEL_NAME says '${LEVEL_NAME}'. No player has ever logged out in it, so it is being removed and the name corrected - the alternative is a container that refuses to start forever over a value nobody chose."
    rm -rf "${DATA:?}/world"
    for dimension in world_nether world_the_end; do
        [[ -d "$DATA/$dimension" ]] || continue
        rm -rf "${DATA:?}/$dimension"
        log "removed /data/${dimension} - a dimension of the world just deleted"
    done
    log "removed /data/world; '${LEVEL_NAME}' will be generated fresh"
    return 0
}

# The world this server generates, and the seed it generates it with. BOTH have to be settled
# before anything else touches the volume: level-name decides which folder the datapacks go into,
# and a seed means nothing once the terrain exists.
#
# WHY level-name IS SEEDED RATHER THAN ENFORCED, and why a disagreement is fatal. Paper's default
# is `world`, and until 2026-09-02 nothing here wrote the key at all - so the datapacks were fetched
# into /data/${LEVEL_NAME}/datapacks while Paper generated `world/` and never looked at them. The
# fix is not to force the value on every start the way online-mode is forced: online-mode=true is a
# backend that CANNOT work, whereas a level-name that disagrees is a server that works perfectly on
# the wrong world. Pointing an existing volume at a new name does not move a world - it generates a
# second, empty one beside it and leaves the season where nobody is looking. So it is written once,
# on a volume that has none, and after that a disagreement stops the container: the same trade this
# script already makes for a datapack whose checksum moved, and that smp makes for a missing pack.
# "Wrong world forever, silently" becomes "the server did not come up", on purpose.
#
# THE ONE DISAGREEMENT THAT IS NOBODY'S DECISION, and why it is repaired instead of refused. The
# release that first wrote the key (v0.2.3) met volumes that had already run without it, so every
# one of them said `level-name=world` - a value Paper picked because nothing had told it otherwise.
# The guard above then did exactly what it says on the tin and stopped `smp` and `hunger-games` on
# every start: a check built to prevent a misconfiguration had become the misconfiguration. Which
# is the shape worth naming, because a guard that fires on its own migration is a guard people
# switch off.
#
# So `world`, and only the literal string `world`, is adopted rather than refused - see
# adopt_paper_default_world for what that costs and what it checks first. Every other disagreement
# is still fatal, and after this runs once no volume in this deployment says `world` any more:
# a later mismatch can only mean somebody changed LEVEL_NAME in .env, which is precisely the case
# the refusal exists for.
seed_level_settings() {
    local file="$DATA/server.properties" current

    current=""
    [[ -f "$file" ]] && current=$(sed -n 's/^level-name=//p' "$file" | head -n1)
    if [[ -n "$current" && "$current" != "$LEVEL_NAME" ]] && ! adopt_paper_default_world "$current"; then
        die "this volume's server.properties says level-name=${current}, but LEVEL_NAME is '${LEVEL_NAME}'.

Refusing to start. Changing it would not move the existing world: Paper would generate an empty '${LEVEL_NAME}' beside '${current}' and run the season on that, while the world with everything in it sat untouched in the same volume.

Two ways out, and only you can pick:
  - the world in this volume is the right one -> set LEVEL_NAME=${current} for this service
  - '${LEVEL_NAME}' really is meant to be a new, empty world -> remove /data/${current} from the volume first, with the server stopped. If the whole volume is disposable, that is

        docker compose stop <service>
        docker volume rm nordtal-s2_mc-<service>
        docker compose up -d <service>"
    fi
    set_property "$file" level-name "$LEVEL_NAME"

    # The seed is only ever an input to GENERATION, so it is written while there is still nothing
    # to generate against and only compared afterwards. Writing it onto a volume whose world
    # already exists would change no terrain and would leave the file claiming a seed the world on
    # disk does not have - which is worse than saying nothing, because the next person reads it.
    #
    # A disagreement here warns rather than stops: unlike level-name it cannot swap the world under
    # anybody, it only means this world came from a different seed than .env now claims.
    # "The world already exists" IS level.dat AND NOT THE DIRECTORY, and the difference is not
    # pedantry: fetch_datapacks below creates /data/${LEVEL_NAME}/datapacks before Paper has
    # generated anything, so on every volume that has ever had datapacks the directory test was
    # already true and the seed was never written. Nordtal would have generated from a random seed
    # while .env named 1837371427, and terrain is not re-rolled - the mistake would have been
    # permanent and silent. Paper writes level.dat when it saves the world, so it is the only file
    # here whose presence means a world rather than a folder somebody made.
    [[ -n "${LEVEL_SEED:-}" ]] || return 0
    if [[ -f "$DATA/$LEVEL_NAME/level.dat" ]]; then
        current=""
        [[ -f "$file" ]] && current=$(sed -n 's/^level-seed=//p' "$file" | head -n1)
        if [[ "$current" != "$LEVEL_SEED" ]]; then
            warn "world '${LEVEL_NAME}' already exists and was generated with ${current:-a seed nothing recorded}, not with LEVEL_SEED=${LEVEL_SEED}. Terrain is never re-rolled, so this is a note, not a fault - but .env and this volume do not describe the same world."
        fi
        return 0
    fi
    set_property "$file" level-seed "$LEVEL_SEED"
}

# --- sourced rather than executed ---------------------------------------------------------------
# Everything ABOVE this line is definitions and can be pulled into another shell; everything BELOW
# it is this container's own run and reaches for the network, the volume and tmux. entrypoint-test.sh
# sources this file to exercise the seeding against fixture directories, which is the only way that
# logic gets tested at all: it decides whether a world folder is deleted, and there is no second
# chance to notice it decided wrong on a real volume.
#
# The `: "${SERVER_KIND:?}"` checks below are the immediate reason the guard sits exactly here and
# not lower - a sourcing shell has none of those variables and would be killed by the first of them.
[[ "${BASH_SOURCE[0]}" == "${0}" ]] || return 0

# --- inputs ----------------------------------------------------------------------------------
: "${SERVER_KIND:?set SERVER_KIND to paper or velocity}"
: "${SERVER_VERSION:?set SERVER_VERSION (paper: 26.2, velocity: 4.1.1)}"
: "${SERVER_BUILD:?set SERVER_BUILD - the exact build fetched into an empty cache; the updater moves it from there}"

case "$SERVER_KIND" in
    paper|velocity) ;;
    *) die "SERVER_KIND must be 'paper' or 'velocity', not '${SERVER_KIND}'" ;;
esac

CACHE="$DATA/.server"
PLUGINS="$DATA/plugins"
SOCK="${MC_TMUX_SOCKET:-/run/mc/tmux.sock}"
SESSION="${MC_TMUX_SESSION:-mc}"

# The Fill API requires a User-Agent that identifies the project and a contact.
FILL_API="https://fill.papermc.io/v3/projects"
FILL_UA="nordtal-season-2/deploy (+https://github.com/nordtal/season-2)"


mkdir -p "$CACHE" "$PLUGINS" "$(dirname "$SOCK")"

# --- the server jar --------------------------------------------------------------------------
# THE UPDATER OWNS THIS JAR (since 2026-09-02). What runs is whichever build of SERVER_VERSION is
# lying in the cache: the `updater` container puts the newest STABLE build there and supersedes
# the previous one by filename prefix. SERVER_BUILD is consulted only when the cache holds no jar
# of this version at all - the first start of a fresh volume, or a version bump before the updater
# has run against it - and is then fetched once, exactly, through the Fill API. It is a floor for
# the first start, the same role the image tag plays for the bot and the updater, not the version.
#
# Until 2026-09-02 this section fetched SERVER_BUILD unconditionally and deleted every other jar,
# which undid each updater run on the next restart: the updater installed build 125 and removed
# 121, this script wanted 121, re-downloaded it and removed 125, and the next run started over.
# Every restart after an update was a cache miss, so a Fill outage at that moment stopped the
# container - the exact outage the cache exists to survive.
#
# Fill's download URLs are content-addressed (fill-data.papermc.io/v1/objects/<sha256>) and cannot
# be constructed by hand, so the bootstrap is an API call. A cached jar means no network at all.
shopt -s nullglob
present=("$CACHE/${SERVER_KIND}-${SERVER_VERSION}-"*.jar)
shopt -u nullglob

if (( ${#present[@]} > 0 )); then
    # Highest build wins. Fill numbers builds as plain integers, and the updater leaves exactly one
    # per version - two means a jar was copied in by hand, which is worth saying but not stopping for.
    JAR_NAME=$(for jar in "${present[@]}"; do printf '%s\n' "${jar##*/}"; done | sort -t- -k3,3n | tail -n1)
    JAR_PATH="$CACHE/$JAR_NAME"
    (( ${#present[@]} > 1 )) && warn "${#present[@]} ${SERVER_KIND} ${SERVER_VERSION} jars in ${CACHE}; running the highest build, ${JAR_NAME}"
    log "server jar from cache: ${JAR_NAME} (SERVER_BUILD=${SERVER_BUILD} is the bootstrap floor and was not consulted)"
else
    JAR_NAME="${SERVER_KIND}-${SERVER_VERSION}-${SERVER_BUILD}.jar"
    JAR_PATH="$CACHE/$JAR_NAME"
    log "no ${SERVER_KIND} ${SERVER_VERSION} jar cached - bootstrapping build ${SERVER_BUILD} through the Fill API"
    meta=$(curl -fsSL --max-time 60 -H "User-Agent: ${FILL_UA}" \
        "${FILL_API}/${SERVER_KIND}/versions/${SERVER_VERSION}/builds/${SERVER_BUILD}") \
        || die "could not reach the Fill API, and no ${SERVER_KIND} ${SERVER_VERSION} jar is cached in ${CACHE}. Refusing to start: this container has no server to run."

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
fi

# One server jar per kind. What this removes is a jar of ANOTHER version - the updater supersedes
# within a version (paper-26.2-121 -> paper-26.2-125) but never across one, so after a version bump
# the old jar would otherwise sit here forever at 40-70 MB. A second build of the running version
# was warned about above and goes the same way.
shopt -s nullglob
for old in "$CACHE/${SERVER_KIND}-"*.jar; do
    [[ "$old" != "$JAR_PATH" ]] && { rm -f "$old"; log "removed superseded ${old##*/}"; }
done
shopt -u nullglob
SERVER_BUILD_RUNNING="${JAR_NAME%.jar}"
SERVER_BUILD_RUNNING="${SERVER_BUILD_RUNNING##*-}"

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
#
# COUNTING JARS WAS NOT ENOUGH, learned on the first deployment (2026-09-02). The updater's
# bootstrap dropped every artefact it could not resolve before it rendered its report, so a run in
# which the GitHub API answered 403 installed PacketEvents and Chunky - which had resolved - and no
# season jar at all, then closed with "Everything asked for was done." limbo, hunger-games and
# network-control were caught here, because their folders really were empty. smp was not: it had two
# jars in it, the count was non-zero, and it came up with no season on it. Exactly the failure this
# guard exists to prevent, walking straight past it.
#
# So the guard asks for the jars it is SUPPOSED to have. EXPECTED_PLUGINS is a whitespace-separated
# list of filename prefixes, split the way JarName splits them - ${file%-*.jar}, the same rule the
# updater uses to decide which jar supersedes which, so no second and disagreeing rule gets invented
# here.
#
# IT IS A MINIMUM, NEVER AN EXACT SET. An extra jar is legitimate and expected: docs/smp.md plans
# CoreProtect (or Prism 4.4) as a hand-installed block logger on its own SQLite file, and the
# updater's own rule for anything it does not account for is that it is reported and left alone. A
# guard demanding an exact set would stop the SMP the first evening one is added.
#
# The two third-party prefixes are the soft spot and it is worth naming: Topology deliberately reads
# a prefix back off the resolved filename rather than assuming one, because `packetevents` resolves
# to packetevents-spigot-*.jar and `chunky` to Chunky-Bukkit-*.jar. Listing them here does assume
# it. If either publisher renames a jar on a first install, this refuses to start while the plugin
# is really there - a false positive, but a loud one with the prefix in the message, and the same
# blind spot the updater already has (it would call the artefact MISSING and report the old jar as
# unclaimed). Refusing is the right side to fail on.
if [[ "${ALLOW_NO_PLUGINS:-false}" != "true" ]]; then
    shopt -s nullglob
    installed=("$PLUGINS"/*.jar)
    shopt -u nullglob

    if (( ${#installed[@]} == 0 )); then
        die "no plugin jars in ${PLUGINS}. This container does not fetch them any more - the updater does. Run it once against this stack:

    docker compose run --rm updater apply

Refusing to start: a Minecraft server with no plugins is a server with no season on it, and nothing about it looks wrong until somebody joins. Set ALLOW_NO_PLUGINS=true if a server with no plugins really is what you want."
    fi

    if [[ -n "${EXPECTED_PLUGINS:-}" ]]; then
        # The identity of every jar actually in the folder, by the JarName rule.
        present=()
        for jar in "${installed[@]}"; do
            jar="${jar##*/}"
            present+=("${jar%-*.jar}")
        done

        missing=()
        expected=0
        for wanted in $EXPECTED_PLUGINS; do
            expected=$(( expected + 1 ))
            found=0
            for have in "${present[@]}"; do
                [[ "$have" == "$wanted" ]] && { found=1; break; }
            done
            (( found == 0 )) && missing+=("$wanted")
        done

        if (( ${#missing[@]} > 0 )); then
            die "${PLUGINS} is missing ${#missing[@]} of the ${expected} plugin(s) this server needs.

  missing:  ${missing[*]}
  present:  ${present[*]:-nothing}
  expected: ${EXPECTED_PLUGINS}

Refusing to start. A folder with SOME of the plugins in it is the state that looks fine and is not: a Minecraft server missing its season jar starts, reports healthy, and is discovered by the first player who joins.

The likeliest cause is an updater run that could not reach a source and skipped this whole server - read its log for a line saying so, and run it again once the source answers:

    docker compose run --rm updater apply

If the plugin IS in the folder under a different filename, its publisher renamed the jar: correct EXPECTED_PLUGINS for this service rather than deleting anything."
        fi
        log "plugins present: ${#installed[@]} jar(s); all ${expected} expected one(s) accounted for"
    else
        log "plugins present: ${#installed[@]} jar(s) - EXPECTED_PLUGINS is unset, so only 'not empty' was checked"
    fi
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

# The player limit, and it is ENFORCED on every start rather than seeded.
#
# ONE NUMBER, since 2026-09-04. MAX_PLAYERS is NETWORK_MAX_PLAYERS out of .env, the same value
# network-control is given for network.yml#max-players - so what the server browser advertises,
# what the proxy's login gate enforces and what this server's tab list shows are the same number.
#
# It was two numbers between 2026-09-03 and now: BACKEND_MAX_PLAYERS, set far out of reach so that
# only the proxy ever refused a player, with the proxy carrying a copy and refusing to start if the
# two crossed. That was safe and still wrong - the backends' number is the one every screen ON a
# backend can reach, so the network advertised 500 while the tab list said 3/1000.
#
# The proxy is still the thing that refuses, and it still refuses at the login gate where it can say
# why. What this value does is make sure a backend is never a SMALLER limit than the advertised one.
# Paper's own default is 20, and until 2026-09-02 nothing set it: the network advertised 500 slots
# while `limbo` - the first backend every login reaches - refused the 21st player with "Server full".
#
# The one login this CAN refuse is an admin's, because admins are exempt from the proxy's limit and
# a full network therefore holds max-players plus them. That exemption is rebuilt inside each Paper
# plugin (common's FullServerAdmission), which is where the admin flag can be read at all - it is
# discord_user.admin in the database, not ops.json, so nothing in this script can act on it.
#
# Enforced on every start, unlike level-name beside it, because the two failure modes are nothing
# alike: a level-name that disagrees would swap a world, so it stops the container; a player limit
# that disagrees just quietly caps the network below what it promises, and there is no world to
# lose by correcting it on a restart.
enforce_player_limit() {
    [[ -n "${MAX_PLAYERS:-}" ]] || return 0
    set_property "$DATA/server.properties" max-players "$MAX_PLAYERS"
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
        # NO motd AND NO show-max-players HERE, and that is the point rather than an omission.
        # Both moved into network-control's network.yml on 2026-09-03, where the plugin answers
        # every ProxyPingEvent with them. Seeding them here would put a second, permanently stale
        # copy of the MOTD in a file this script only ever writes once - which is exactly the trap
        # that made VELOCITY_MOTD do nothing on any volume that had already started.
        #
        # Velocity's own defaults for the two are harmless: nothing reads its motd once the plugin
        # answers the ping, and show-max-players is a display value the plugin overrides. A proxy
        # without network-control does not start at all - see EXPECTED_PLUGINS.
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

    # Before anything generates, and before the datapacks: level-name IS the folder they go into,
    # and the world every other one is created inside.
    seed_level_settings
    enforce_player_limit

    if [[ -n "${DATAPACK_URLS:-}" ]]; then
        fetch_datapacks "$DATA/${LEVEL_NAME}/datapacks"
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
log "starting ${SERVER_KIND} ${SERVER_VERSION} build ${SERVER_BUILD_RUNNING}"
log "console: run 'console' in this container to attach, or 'mc <command>' to send one command"

LOG_FILE="$DATA/logs/latest.log"
BOOT_LOG="$DATA/logs/console.log"
mkdir -p "$DATA/logs"
: > "$BOOT_LOG"

rm -f "$SOCK"

# THE OPTIONS GO ON BEFORE THE SESSION EXISTS, and that ordering is the whole point.
#
# `remain-on-exit` is what keeps the pane after the JVM exits, so its status can be read back and
# reported as this container's. It used to be set on the session AFTER new-session had already
# started the JVM, which works for every server that runs for a while and fails for exactly the one
# that does not: a JVM that dies at once takes the session with it before the option lands, the
# `display-message` queries below then fail, and their `|| echo 1` fallbacks turn any exit status
# into 1. So the crash-looping container reported "server exited with status 1" whatever had
# actually happened - verified in a container 2026-09-02, where a real status of 3 came back as 1
# before this change and as 3 after it.
#
# `exit-empty off` is what makes that possible at all: a tmux server with no sessions exits
# immediately by default, so there is no server to set a global option on until a session exists.
# With it off, the server is started first, told what every window should do, and given the session
# afterwards.
tmux -S "$SOCK" start-server \; set-option -g exit-empty off \; set-option -wg remain-on-exit on

# new-session and pipe-pane in ONE invocation, for the same reason: a separate pipe-pane call
# against a pane that has already died fails with "target pane has exited", and the output that
# killed it is gone. Measured in a container 2026-09-02 with a command that exits instantly.
tmux -S "$SOCK" new-session -d -s "$SESSION" -c "$DATA" -x 200 -y 50 \
    "exec java ${JVM_OPTS} -jar '${JAR_PATH}' ${JAVA_ARGS[*]:-}" \
  \; pipe-pane -o -t "$SESSION" "cat >> '$BOOT_LOG'"
piping=1
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
tail -n 0 -F "$LOG_FILE" 2>/dev/null &
TAIL_PID=$!

# --- and the gap that leaves ------------------------------------------------------------------
# `tail -F latest.log` shows nothing that happens BEFORE Paper creates that file, because there is
# no file to follow. Everything the JVM writes until then - Paperclip resolving and patching the
# server jar, a bad -Xmx, a missing class, an hs_err header - goes to the tmux pane and nowhere
# else, and the pane dies with the container. That is not a corner: a Paperclip that cannot load
# mojang_26.2.jar prints a forty-line stack trace and exits 1, and what reached `docker logs` was
#
#     [nordtal] starting paper 26.2 build 121
#     [nordtal] server exited with status 1
#
# in an endless restart loop, with the cause in no log anybody could reach.
#
# THIS pipe-pane IS NOT THE FORBIDDEN ONE. ../README.md#never-mirror-the-console-with-tmux-pipe-pane
# rules out `pipe-pane ... > /proc/1/fd/1`, and what makes that one lethal is the second handle on
# the CONTAINER'S STDOUT PIPE: it wedges the container so completely that SIGTERM never reaches PID
# 1 and even `docker rm -f` fails. Writing to an ordinary file in the volume shares none of that -
# different descriptor, no pipe, nothing holding stdout open. Measured 2026-09-01 for the first
# form; the distinction is the whole reason this is allowed.
#
# It is bounded by construction rather than by a rotation policy: the capture is emptied at every
# start and switched OFF again the moment latest.log exists, below in the wait loop. Past that
# point Paper is logging for itself and every line is already in the container log, so a second
# escape-laden copy of the whole session would be pure cost. What it costs a running server is
# therefore nothing at all - it is a boot log, and it stops being written before the first player
# could join.

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
    # Paper has taken over its own logging, and `tail -F` above is already mirroring it. Stop
    # capturing the pane: from here the two would say the same thing, one of them in colour.
    if (( piping == 1 )) && [[ -s "$LOG_FILE" ]]; then
        tmux -S "$SOCK" pipe-pane -t "$SESSION" 2>/dev/null || true
        piping=0
    fi
    sleep 1 & wait $! || true
done

status=$(tmux -S "$SOCK" display-message -p -t "$SESSION" '#{pane_dead_status}' 2>/dev/null || echo 1)
# Let the tail catch up on whatever the server wrote while shutting down, then stop holding stdout.
sleep 1 & wait $! || true
kill "$TAIL_PID" 2>/dev/null || true

# THE POST-MORTEM, and the only reason the capture above exists. The JVM died without Paper ever
# creating latest.log, so `tail -F` had nothing to follow and the container log is about to say
# "server exited with status 1" and not one word about why. The pane held the answer and is about
# to be destroyed with the tmux server, so it goes to stdout now - which is where a person, and
# Arcane's log view, will actually look.
if [[ ! -s "$LOG_FILE" && -s "$BOOT_LOG" ]]; then
    warn "the server produced no ${LOG_FILE##*/}, so it died before Paper started logging. Its console output follows - this is the only copy, and it is also in ${BOOT_LOG} until the next start:"
    cat "$BOOT_LOG" >&2
fi

tmux -S "$SOCK" kill-server 2>/dev/null || true
log "server exited with status ${status:-1}"
exit "${status:-1}"
