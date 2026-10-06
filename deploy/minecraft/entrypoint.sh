#!/usr/bin/env bash
# PID 1 for every Minecraft service. In order, it:
#
#   1. runs the newest cached server jar, or resolves one from the PaperMC Fill API
#   2. refuses to start on an empty plugins folder, which steward fills
#   3. starts the server in tmux, so `docker exec` has a writable console
#   4. turns SIGTERM into a graceful shutdown and waits for the world to save
set -Eeuo pipefail

log()  { printf '[nordtal] %s\n' "$*"; }
warn() { printf '[nordtal] WARN: %s\n' "$*" >&2; }
die()  { printf '[nordtal] FATAL: %s\n' "$*" >&2; exit 1; }

# Overridable only so entrypoint-test.sh can point the sourced half at fixtures; not an operator knob.
DATA="${DATA:-/data}"

# Paper's default. seed_level_settings and fetch_datapacks both read it, so they agree.
LEVEL_NAME="${LEVEL_NAME:-world}"

# first-start configuration
# Seeds files that do not exist yet and leaves existing ones to the operator. Only online-mode is
# enforced on every start; see prepare_backend.

# Sets key=value in a properties file, creating it if needed, and logs a change.
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

# Deletes an unplayed world that carries Paper's default name, so LEVEL_NAME can apply.
#
# Returns 0 only when the old name is literally `world` and no player has ever logged out in it
# (<world>/playerdata is empty); any other name was a person's choice. The world's nether and end
# folders go with it.
adopt_paper_default_world() {
    local current="$1" playerdata="$DATA/world/playerdata" dimension

    [[ "$current" == "world" ]] || return 1

    # -print -quit stops at the first entry and prints nothing to filter.
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

# Seeds level-name and level-seed before the world exists.
#
# A level-name that disagrees with the volume is fatal: renaming generates a second, empty world.
# Only Paper's default `world` is adopted instead; see adopt_paper_default_world.
seed_level_settings() {
    local file="$DATA/server.properties" current

    current=""
    # `sed -n 1p`, not `head -n1`: an early exit turns the pipeline into 141 under pipefail.
    [[ -f "$file" ]] && current=$(sed -n 's/^level-name=//p' "$file" | sed -n '1p')
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

    # The seed only matters before generation, so on an existing world it is compared, never written,
    # and a mismatch warns. level.dat marks a world; the directory alone may exist for datapacks.
    [[ -n "${LEVEL_SEED:-}" ]] || return 0
    if [[ -f "$DATA/$LEVEL_NAME/level.dat" ]]; then
        current=""
        # `sed -n 1p`, not `head -n1`; see level-name above.
        [[ -f "$file" ]] && current=$(sed -n 's/^level-seed=//p' "$file" | sed -n '1p')
        if [[ "$current" != "$LEVEL_SEED" ]]; then
            warn "world '${LEVEL_NAME}' already exists and was generated with ${current:-a seed nothing recorded}, not with LEVEL_SEED=${LEVEL_SEED}. Terrain is never re-rolled, so this is a note, not a fault - but .env and this volume do not describe the same world."
        fi
        return 0
    fi
    set_property "$file" level-seed "$LEVEL_SEED"
}

# the cached server jar
# Picking the wrong jar fails silently, so entrypoint-test.sh drives these against fixtures.

# Whether version $1 build $2 is newer than version $3 build $4.
#
# Numeric per component: as text "4.10.0" would sort below "4.9.0".
newer_server_jar() {
    local -a mine theirs
    IFS='.' read -r -a mine <<<"$1"
    IFS='.' read -r -a theirs <<<"$3"

    local i max=${#mine[@]} l r
    (( ${#theirs[@]} > max )) && max=${#theirs[@]}
    for (( i = 0; i < max; i++ )); do
        l=$(( 10#${mine[i]:-0} ))
        r=$(( 10#${theirs[i]:-0} ))
        if (( l != r )); then
            return $(( l > r ? 0 : 1 ))
        fi
    done
    (( 10#$2 > 10#$4 ))
}

# Prints the identity of the jar named $1, as JarName reads it: the name before the last `-` that a digit
# follows, so `Plugin-2.6.0-paper.jar` is `Plugin`; with no such dash, the name before its last `-`.
jar_identity() {
    local stem="${1##*/}"
    stem="${stem%.jar}"
    if [[ "$stem" =~ ^(.+)-[0-9].*$ ]]; then
        printf '%s\n' "${BASH_REMATCH[1]}"
    else
        printf '%s\n' "${stem%-*}"
    fi
}

# Deletes every `<kind>-*.jar` in $1 except $3, the jar this start chose.
remove_superseded_jars() {
    local cache="$1" kind="$2" keep="$3"
    local old

    shopt -s nullglob
    for old in "$cache/${kind}-"*.jar; do
        [[ "$old" != "$keep" ]] && { rm -f "$old"; log "removed superseded ${old##*/}"; }
    done
    shopt -u nullglob
    return 0
}

# Prints the filename of the newest `<kind>-<version>-<build>.jar` in directory $1, or nothing.
#
# Globs on the kind only: a version glob would miss a newer minor and refetch the old one. Names with
# no numeric build or a version other than digits and dots are skipped.
newest_server_jar() {
    local cache="$1" kind="$2"
    local best="" best_version="" best_build=""
    local jar name stem version build

    shopt -s nullglob
    for jar in "$cache/${kind}-"*.jar; do
        name="${jar##*/}"
        stem="${name%.jar}"
        stem="${stem#"${kind}-"}"

        [[ "$stem" == *-* ]] || continue
        build="${stem##*-}"
        version="${stem%-*}"
        [[ "$build" =~ ^[0-9]+$ ]] || continue
        [[ "$version" =~ ^[0-9]+(\.[0-9]+)*$ ]] || continue

        if [[ -z "$best" ]] || newer_server_jar "$version" "$build" "$best_version" "$best_build"; then
            best="$name"
            best_version="$version"
            best_build="$build"
        fi
    done
    shopt -u nullglob

    if [[ -n "$best" ]]; then
        printf '%s\n' "$best"
    fi
    return 0
}

# Seeds velocity.toml once with only what this deployment needs; Velocity defaults the rest.
# [forced-hosts] is written empty, since without the table Velocity routes to its example servers.
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

    # Validated first: a half-written velocity.toml would pass for the operator's own.
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
        # No motd or show-max-players: the proxy answers the ping with both, out of the network's settings.
        printf '[servers]\n'
        for entry in $VELOCITY_SERVERS; do
            name="${entry%%=*}"
            address="${entry#*=}"
            printf '%s = "%s"\n' "$name" "$address"
        done
        printf 'try = ["%s"]\n\n' "${VELOCITY_TRY:-${VELOCITY_SERVERS%%=*}}"
        printf '[forced-hosts]\n\n'
        # accepts-transfers lets the standby proxy receive players; Velocity reads it only under
        # [advanced]. This table comes last, since every later key would belong to it.
        printf '[advanced]\n'
        printf 'accepts-transfers = true\n'
    } > "$tmp" || { rm -f "$tmp"; exit 1; }
    mv "$tmp" "$file"
    log "seeded velocity.toml: modern forwarding, servers ${VELOCITY_SERVERS}"
}

# Enforces accepts-transfers on every start, including volumes seeded before the key existed.
ensure_velocity_transfers() {
    ensure_velocity_advanced accepts-transfers true \
        "without it this proxy refuses every player another proxy hands it, and an update that moves a proxy would drop them"
}

# Holds haproxy-protocol to VELOCITY_HAPROXY, which compose.yml sets beside the guard on 25565.
# Wrong either way drops every connection; unset leaves the file alone.
ensure_velocity_haproxy() {
    local wanted="${VELOCITY_HAPROXY:-}"

    [[ -n "$wanted" ]] || return 0
    if [[ "$wanted" != "true" && "$wanted" != "false" ]]; then
        warn "VELOCITY_HAPROXY is \"${wanted}\", which is neither true nor false - velocity.toml left untouched"
        return 0
    fi

    ensure_velocity_advanced haproxy-protocol "$wanted" \
        "a guard in front of this proxy writes a PROXY header that Velocity would otherwise read as the client's first packet"
}

# Holds one key under [advanced] to a value: keeps it, corrects it loudly, adds it under the header,
# or appends the table last. An unparsable file is left alone with a warning.
#
# @param key    the bare key name under [advanced]
# @param wanted the value it must carry, as written into the file
# @param why    one sentence for the warning, on what breaks without it
ensure_velocity_advanced() {
    local key="$1" wanted="$2" why="$3"
    local file="$DATA/velocity.toml" tmp verdict

    # No file means there was nothing to seed from; not this function's business.
    [[ -f "$file" ]] || return 0

    # Whitespace is stripped, since TOML allows both `key=true` and `key = true`. Comments never match.
    verdict=$(awk -v key="$key" -v wanted="$wanted" '
        /^[[:space:]]*\[/ { table = $1; if (table == "[advanced]") advanced = 1; next }
        {
            line = $0
            gsub(/[[:space:]]/, "", line)
            if (line ~ "^" key "=") {
                if (table == "[advanced]") {
                    found = (line == key "=" wanted) ? "wanted" : "other"
                } else if (table == "") {
                    root = 1
                }
            }
        }
        END {
            if (found == "wanted")     state = "present"
            else if (found == "other") state = "wrong"
            else if (advanced)         state = "table-only"
            else                       state = "absent"
            # One line: the verdict and whether a root-level key was seen.
            print state, (root ? "root" : "-")
        }
    ' "$file") || { warn "could not read velocity.toml to check ${key} - left untouched"; return 0; }

    # A root-level key is read by nothing; it is reported, not deleted.
    if [[ "$verdict" == *" root" ]]; then
        warn "velocity.toml has a ${key} at the ROOT of the file. Velocity reads it under [advanced] and nowhere else, so that line does nothing."
    fi

    tmp="${file}.partial"
    case "${verdict%% *}" in
        present)
            log "velocity.toml has ${key} = ${wanted}"
            return 0
            ;;
        wrong)
            # Overrules the operator, loudly: the deployment cannot work otherwise.
            awk -v key="$key" -v wanted="$wanted" '
                /^[[:space:]]*\[/ { table = $1 }
                {
                    line = $0
                    gsub(/[[:space:]]/, "", line)
                    if (table == "[advanced]" && line ~ "^" key "=") {
                        print key " = " wanted
                        next
                    }
                    print
                }
            ' "$file" > "$tmp" || { rm -f "$tmp"; warn "could not rewrite velocity.toml - left untouched"; return 0; }
            mv "$tmp" "$file"
            warn "velocity.toml carried a different ${key} under [advanced]. Set to ${wanted}: ${why}."
            ;;
        table-only)
            # Right after the header: a second [advanced] table is invalid TOML.
            awk -v line="${key} = ${wanted}" '
                { print }
                /^[[:space:]]*\[advanced\][[:space:]]*$/ && !done { print line; done = 1 }
            ' "$file" > "$tmp" || { rm -f "$tmp"; warn "could not rewrite velocity.toml - left untouched"; return 0; }
            mv "$tmp" "$file"
            log "velocity.toml had an [advanced] table without ${key} - added it"
            ;;
        absent)
            # Last, so the new table swallows no later keys.
            cp "$file" "$tmp" || { rm -f "$tmp"; warn "could not rewrite velocity.toml - left untouched"; return 0; }
            {
                printf '\n'
                printf '# Added by the nordtal entrypoint.\n'
                printf '[advanced]\n'
                printf '%s = %s\n' "$key" "$wanted"
            } >> "$tmp" || { rm -f "$tmp"; warn "could not rewrite velocity.toml - left untouched"; return 0; }
            mv "$tmp" "$file"
            log "velocity.toml had no ${key} - appended [advanced] ${key} = ${wanted}"
            ;;
    esac
}

# sourced rather than executed
# entrypoint-test.sh sources the definitions above. The guard sits before the `:?` checks, which
# would kill a sourcing shell.
[[ "${BASH_SOURCE[0]}" == "${0}" ]] || return 0

# inputs
: "${SERVER_KIND:?set SERVER_KIND to paper or velocity}"
# paper: the exact Minecraft version. velocity: Fill's family name, whose newest release is resolved.
: "${SERVER_VERSION:?set SERVER_VERSION (paper: the Minecraft version, e.g. 26.2; velocity: the Fill version family, e.g. 4.0.0)}"
# No SERVER_BUILD: an empty cache gets the newest STABLE build.

case "$SERVER_KIND" in
    paper|velocity) ;;
    *) die "SERVER_KIND must be 'paper' or 'velocity', not '${SERVER_KIND}'" ;;
esac

CACHE="$DATA/.server"
PLUGINS="$DATA/plugins"
SOCK="${MC_TMUX_SOCKET:-/run/mc/tmux.sock}"
SESSION="${MC_TMUX_SESSION:-mc}"

# The Fill API requires a User-Agent naming the project and a contact.
FILL_API="https://fill.papermc.io/v3/projects"
FILL_UA="nordtal-season-2/deploy (+https://github.com/nordtal/season-2)"


mkdir -p "$CACHE" "$PLUGINS" "$(dirname "$SOCK")"

# the server jar
# steward fills the cache; the newest version and build there runs. Fill is asked only when
# the cache is empty. There is no pin and no way back from a bad build; do not add one.
JAR_NAME=$(newest_server_jar "$CACHE" "$SERVER_KIND")

if [[ -n "$JAR_NAME" ]]; then
    JAR_PATH="$CACHE/$JAR_NAME"
    log "server jar from cache: ${JAR_NAME} (the Fill API was not consulted)"
else
    # Resolves the newest release in the family first; for paper that is SERVER_VERSION itself.
    log "no ${SERVER_KIND} jar cached - resolving the newest stable build through the Fill API"
    project=$(curl -fsSL --max-time 60 -H "User-Agent: ${FILL_UA}" "${FILL_API}/${SERVER_KIND}") \
        || die "could not reach the Fill API, and no ${SERVER_KIND} jar is cached in ${CACHE}. Refusing to start: this container has no server to run."

    version=$(jq -er --arg family "$SERVER_VERSION" '
            (.versions[$family] // empty)
            | map(select(test("^[0-9]+(\\.[0-9]+)*$")))
            | sort_by(split(".") | map(tonumber))
            | last // empty' <<<"$project") \
        || die "the Fill API lists no released version in ${SERVER_KIND} family '${SERVER_VERSION}'. A family is Fill's name for a major and is NOT a version - check it against ${FILL_API}/${SERVER_KIND}"
    [[ "$version" == "$SERVER_VERSION" ]] \
        || log "${SERVER_KIND} family ${SERVER_VERSION} resolves to version ${version}"

    # `/builds/latest` includes ALPHA builds, so the list is filtered as steward does.
    builds=$(curl -fsSL --max-time 60 -H "User-Agent: ${FILL_UA}" \
        "${FILL_API}/${SERVER_KIND}/versions/${version}/builds") \
        || die "could not read the ${SERVER_KIND} ${version} builds from the Fill API, and no ${SERVER_KIND} jar is cached in ${CACHE}. Refusing to start: this container has no server to run."

    meta=$(jq -er '[.[] | select(.channel == "STABLE" and .downloads."server:default")] | max_by(.id)' <<<"$builds") \
        || die "the Fill API lists no STABLE build with a 'server:default' download for ${SERVER_KIND} ${version}. Check ${FILL_API}/${SERVER_KIND}/versions/${version}/builds"

    # The API's filename is the one steward installs under, so both agree.
    JAR_NAME=$(jq -er '.downloads."server:default".name' <<<"$meta") \
        || die "the Fill API returned a download with no filename"
    JAR_PATH="$CACHE/$JAR_NAME"
    url=$(jq -er '.downloads."server:default".url' <<<"$meta") \
        || die "the Fill API returned a download with no url"
    sha=$(jq -er '.downloads."server:default".checksums.sha256' <<<"$meta") \
        || die "the Fill API returned a download without a sha256 checksum"
    log "bootstrapping ${JAR_NAME} (build $(jq -r '.id' <<<"$meta"), channel STABLE)"

    tmp="${JAR_PATH}.partial"
    curl -fsSL --max-time 600 -H "User-Agent: ${FILL_UA}" -o "$tmp" "$url" \
        || { rm -f "$tmp"; die "downloading ${JAR_NAME} failed"; }

    actual=$(sha256sum "$tmp" | cut -d' ' -f1)
    [[ "$actual" == "$sha" ]] \
        || { rm -f "$tmp"; die "checksum mismatch for ${JAR_NAME}: expected ${sha}, got ${actual}"; }

    mv "$tmp" "$JAR_PATH"
    log "downloaded and verified ${JAR_NAME}"
fi

remove_superseded_jars "$CACHE" "$SERVER_KIND" "$JAR_PATH"

# Read back from the filename, since SERVER_VERSION is only a family on the proxy.
SERVER_VERSION_RUNNING="${JAR_NAME%.jar}"
SERVER_VERSION_RUNNING="${SERVER_VERSION_RUNNING#"${SERVER_KIND}-"}"
SERVER_BUILD_RUNNING="${SERVER_VERSION_RUNNING##*-}"
SERVER_VERSION_RUNNING="${SERVER_VERSION_RUNNING%-*}"

# plugins
# steward owns the plugin jars; this script must never fetch them.
#
# SERVER_PLUGINS is the service's eu.nordtal.plugins label: artifact[=jar prefix][?], one per plugin.
# Every entry without a `?` must be installed, matched by its jar prefix (the artefact id where none is
# given) as JarName splits a filename (jar_identity). It is a minimum: extra jars are fine, a missing
# one refuses the start. A renamed third-party jar would refuse too, loudly and with the prefix named.
if [[ "${ALLOW_NO_PLUGINS:-false}" != "true" ]]; then
    shopt -s nullglob
    installed=("$PLUGINS"/*.jar)
    shopt -u nullglob

    if (( ${#installed[@]} == 0 )); then
        die "no plugin jars in ${PLUGINS}. This container does not fetch them - steward-agent does, at every start of its own. Restart it against this stack:

    docker compose restart steward-agent

Refusing to start: a Minecraft server with no plugins is a server with no season on it, and nothing about it looks wrong until somebody joins. Set ALLOW_NO_PLUGINS=true if a server with no plugins really is what you want."
    fi

    if [[ -n "${SERVER_PLUGINS:-}" ]]; then
        # The identity of every jar actually in the folder, by the JarName rule.
        present=()
        for jar in "${installed[@]}"; do
            jar="${jar##*/}"
            present+=("$(jar_identity "$jar")")
        done

        missing=()
        required=()
        expected=0
        # read -a, not an unquoted expansion: a `?` entry is a glob pattern to the shell.
        read -ra entries <<<"$SERVER_PLUGINS"
        for entry in "${entries[@]}"; do
            # One the server may lack is not asked for: an artefact with no build must not keep it down.
            [[ "$entry" == *\? ]] && continue
            wanted="${entry#*=}"
            required+=("$wanted")
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
  expected: ${required[*]}

Refusing to start. A folder with SOME of the plugins in it is the state that looks fine and is not: a Minecraft server missing its season jar starts, reports healthy, and is discovered by the first player who joins.

The likeliest cause is a run that could not reach a source and skipped this whole server - read steward-agent's log for a line saying so, and restart it once the source answers:

    docker compose restart steward-agent

If the plugin IS in the folder under a different filename, its publisher renamed the jar: give the new prefix in this service's eu.nordtal.plugins label in compose.yml (artifact=prefix) rather than deleting anything."
        fi
        log "plugins present: ${#installed[@]} jar(s); all ${expected} expected one(s) accounted for"
    else
        log "plugins present: ${#installed[@]} jar(s) - SERVER_PLUGINS is unset, so only 'not empty' was checked"
    fi
fi

# world-generation datapacks
# Pinned packs go into the level-name world's datapacks/, which feeds every world on the server.
# They must be there before the start, since worldgen reads them once and terrain is never re-rolled.
#
# DATAPACK_URLS: whitespace-separated entries, each a URL or <sha512>@<url>. Modrinth publishes
# sha512, so pins can be copied from its API.
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

        # Paper reports the name on disk, so percent-escapes are decoded.
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

# Configures a Paper backend behind the proxy: offline mode and modern forwarding.
prepare_backend() {
    local global="$DATA/config/paper-global.yml"

    # Enforced on every start: a backend that authenticates refuses every forwarded login.
    set_property "$DATA/server.properties" online-mode false

    # Paper reads PAPER_VELOCITY_SECRET from the environment and writes it into paper-global.yml
    # on first load, so rotating means editing both. Only the enable switch is seeded.
    if [[ -f "$global" ]]; then
        log "config/paper-global.yml exists - not touched. Modern forwarding has to be enabled in it (proxies.velocity.enabled: true)."
    else
        mkdir -p "$(dirname "$global")"
        cat > "$global" <<'YAML'
# Seeded by the nordtal entrypoint on first start, and not touched again. Paper adds every other
# setting with its default the first time it loads this file.
#
# proxies.velocity.secret arrives as PAPER_VELOCITY_SECRET, and Paper still writes it into this
# file on first load.
proxies:
  velocity:
    enabled: true
    online-mode: true
YAML
        log "seeded config/paper-global.yml with Velocity modern forwarding enabled"
    fi
}


# per-kind preparation
JAVA_ARGS=()
if [[ "$SERVER_KIND" == "paper" ]]; then
    # The operator accepts the EULA, never an image default.
    [[ "${EULA:-}" == "true" ]] \
        || die "set EULA=true to accept https://aka.ms/MinecraftEULA - this is deliberately not defaulted"
    printf 'eula=true\n' > "$DATA/eula.txt"
    JAVA_ARGS+=(nogui)

    # Before generation and the datapacks, which go into the level-name folder.
    seed_level_settings

    if [[ -n "${DATAPACK_URLS:-}" ]]; then
        fetch_datapacks "$DATA/${LEVEL_NAME}/datapacks"
    fi

    # A forwarding secret makes this a backend behind the proxy.
    if [[ -n "${PAPER_VELOCITY_SECRET:-}" ]]; then
        prepare_backend
    fi
else
    # Velocity reads the forwarding secret from the file velocity.toml names.
    if [[ -n "${VELOCITY_FORWARDING_SECRET:-}" ]]; then
        printf '%s' "$VELOCITY_FORWARDING_SECRET" > "$DATA/forwarding.secret"
        chmod 600 "$DATA/forwarding.secret"
    fi
    seed_velocity_config
    # Runs on every start, also on files this script did not write.
    ensure_velocity_transfers
    ensure_velocity_haproxy
fi

JVM_OPTS="${JVM_OPTS:--Xms${HEAP:-2G} -Xmx${HEAP:-2G} -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+DisableExplicitGC -XX:+AlwaysPreTouch}"

# start it inside tmux
# `docker exec` cannot reach PID 1's stdin; tmux makes the console writable. `console` attaches,
# `mc <cmd>` sends one command.
log "starting ${SERVER_KIND} ${SERVER_VERSION_RUNNING} build ${SERVER_BUILD_RUNNING}"
log "console: run 'console' in this container to attach, or 'mc <command>' to send one command"

LOG_FILE="$DATA/logs/latest.log"
BOOT_LOG="$DATA/logs/console.log"
mkdir -p "$DATA/logs"
: > "$BOOT_LOG"

rm -f "$SOCK"

# Options go on before the session exists. `remain-on-exit` keeps a dead pane so its exit status can
# be read; set later, a JVM that dies at once would report 1. `exit-empty off` keeps the server alive
# without a session so the option can be set first.
tmux -S "$SOCK" start-server \; set-option -g exit-empty off \; set-option -wg remain-on-exit on

# One invocation: a separate pipe-pane against a pane that already died fails and loses its output.
tmux -S "$SOCK" new-session -d -s "$SESSION" -c "$DATA" -x 200 -y 50 \
    "exec java ${JVM_OPTS} -jar '${JAR_PATH}' ${JAVA_ARGS[*]:-}" \
  \; pipe-pane -o -t "$SESSION" "cat >> '$BOOT_LOG'"
piping=1
# Mirrors latest.log to stdout for `docker logs`. Never `pipe-pane > /proc/1/fd/1`: a second handle
# on stdout wedges the container so SIGTERM never arrives.
tail -n 0 -F "$LOG_FILE" 2>/dev/null &
TAIL_PID=$!

# latest.log misses everything before Paper creates it, so the pane is captured into a boot log in
# the volume. It is emptied each start and switched off once latest.log exists.

# graceful shutdown
# SIGTERM goes to the JVM, whose shutdown hook saves on both Paper and Velocity. compose allows 180s,
# since the 10s default cuts a large world's save short.
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
    # Paper logs for itself now, so the pane capture stops.
    if (( piping == 1 )) && [[ -s "$LOG_FILE" ]]; then
        tmux -S "$SOCK" pipe-pane -t "$SESSION" 2>/dev/null || true
        piping=0
    fi
    sleep 1 & wait $! || true
done

status=$(tmux -S "$SOCK" display-message -p -t "$SESSION" '#{pane_dead_status}' 2>/dev/null || echo 1)
# Lets the tail catch up on the shutdown output, then releases stdout.
sleep 1 & wait $! || true
kill "$TAIL_PID" 2>/dev/null || true

# The JVM died before latest.log existed, so the boot capture is the only record; print it.
if [[ ! -s "$LOG_FILE" && -s "$BOOT_LOG" ]]; then
    warn "the server produced no ${LOG_FILE##*/}, so it died before Paper started logging. Its console output follows - this is the only copy, and it is also in ${BOOT_LOG} until the next start:"
    cat "$BOOT_LOG" >&2
fi

tmux -S "$SOCK" kill-server 2>/dev/null || true
log "server exited with status ${status:-1}"
exit "${status:-1}"
