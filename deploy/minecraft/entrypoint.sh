#!/usr/bin/env bash
# PID 1 of every Minecraft service. It does this, in order:
#
#   1. It runs the newest cached server jar, or resolves one from the PaperMC Fill API.
#   2. It refuses to start on an empty plugins folder, which steward fills.
#   3. It starts the server in tmux, so that `docker exec` reaches a writable console.
#   4. It turns SIGTERM into a graceful shutdown and waits until the world is saved.
set -Eeuo pipefail

log()  { printf '[nordtal] %s\n' "$*"; }
warn() { printf '[nordtal] WARN: %s\n' "$*" >&2; }
die()  { printf '[nordtal] FATAL: %s\n' "$*" >&2; exit 1; }

# Overridable so that entrypoint-test.sh can point the sourced half at fixtures. Operators do not set it.
DATA="${DATA:-/data}"

# Paper's default level name. seed_level_settings and fetch_datapacks both read it.
LEVEL_NAME="${LEVEL_NAME:-world}"

# NordtalPlugin.fatal writes the reason of a refused start here before the server stops.
REFUSED_START="$DATA/refused-start"

# Prints that reason on one line, or nothing when the last start was not refused.
refusal() {
    [[ -s "$REFUSED_START" ]] || return 0
    tr -s '\n' ' ' < "$REFUSED_START" | sed 's/ *$//'
}

# The seeding functions write files that do not exist yet and leave existing files to the operator.
# Only online-mode and white-list are enforced at every start (see prepare_backend).
set_property() {
    local file="$1" key="$2" value="$3" tmp
    if [[ -f "$file" ]] && grep -qE "^${key}=" "$file"; then
        grep -qxF "${key}=${value}" "$file" && return 0
        tmp="${file}.tmp"
        sed "s|^${key}=.*|${key}=${value}|" "$file" > "$tmp" && mv "$tmp" "$file"
        log "property set: file=${file##*/} key=${key} value=${value}"
    else
        printf '%s=%s\n' "$key" "$value" >> "$file"
        log "property added: file=${file##*/} key=${key} value=${value}"
    fi
}

# Deletes an unplayed world with the default name `world`, so that LEVEL_NAME applies. The function
# returns 0 only when the old name is literally `world` and <world>/playerdata is empty. The nether
# and end folders of that world go with it.
adopt_paper_default_world() {
    local current="$1" playerdata="$DATA/world/playerdata" dimension

    [[ "$current" == "world" ]] || return 1

    # -print -quit stops at the first entry.
    if [[ -d "$playerdata" ]] \
        && [[ -n "$(find "$playerdata" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
        warn "default world played in, not removed: from=world to=${LEVEL_NAME}"
        return 1
    fi

    warn "unplayed default world adopted: from=world to=${LEVEL_NAME}"
    rm -rf "${DATA:?}/world"
    for dimension in world_nether world_the_end; do
        [[ -d "$DATA/$dimension" ]] || continue
        rm -rf "${DATA:?}/$dimension"
        log "default world dimension removed: dir=/data/${dimension}"
    done
    log "default world removed: dir=/data/world"
    return 0
}

# Seeds level-name and level-seed before the world exists. A level-name that differs from the volume
# is fatal, because a rename would generate a second, empty world. Only Paper's default `world` is
# adopted (see adopt_paper_default_world).
seed_level_settings() {
    local file="$DATA/server.properties" current

    current=""
    # `sed -n 1p` instead of `head -n1`: an early exit makes the pipeline return 141 under pipefail.
    [[ -f "$file" ]] && current=$(sed -n 's/^level-name=//p' "$file" | sed -n '1p')
    if [[ -n "$current" && "$current" != "$LEVEL_NAME" ]] && ! adopt_paper_default_world "$current"; then
        die "Not started: server.properties says level-name=${current}, but LEVEL_NAME is ${LEVEL_NAME}. Set LEVEL_NAME=${current}, or remove /data/${current} with the server stopped to start an empty world."
    fi
    set_property "$file" level-name "$LEVEL_NAME"

    # A seed matters only before generation. On an existing world the function compares it, never
    # writes it, and warns on a mismatch. level.dat marks a world, because the directory alone may exist for datapacks.
    [[ -n "${LEVEL_SEED:-}" ]] || return 0
    if [[ -f "$DATA/$LEVEL_NAME/level.dat" ]]; then
        current=""
        # `sed -n 1p` instead of `head -n1`, as above.
        [[ -f "$file" ]] && current=$(sed -n 's/^level-seed=//p' "$file" | sed -n '1p')
        if [[ "$current" != "$LEVEL_SEED" ]]; then
            warn "world seed differs from LEVEL_SEED, world kept: world=${LEVEL_NAME} seed=${current:-unknown} level_seed=${LEVEL_SEED}"
        fi
        return 0
    fi
    set_property "$file" level-seed "$LEVEL_SEED"
}

# The function puts pinned packs into the datapacks/ folder of the level-name world, which feeds every
# world of the server. They must be there before the start, because worldgen reads them once.
#
# DATAPACK_URLS holds whitespace-separated entries, each a URL or <sha512>@<url>. The function owns the
# folder and removes every zip there that DATAPACK_URLS does not name.
fetch_datapacks() {
    local dir="$1" spec url sha file dest tmp actual old
    local -A named=()

    mkdir -p "$dir"
    for spec in $DATAPACK_URLS; do
        if [[ "$spec" == *"@"* ]]; then
            sha="${spec%%@*}"
            url="${spec#*@}"
        else
            sha=""
            url="$spec"
        fi

        # Paper reports the name on disk, so the function decodes percent-escapes.
        file="${url##*/}"
        file="${file%%\?*}"
        file="$(printf '%b' "${file//%/\\x}")"
        dest="${dir}/${file}"
        named["$file"]=1

        if [[ -f "$dest" ]]; then
            log "datapack cached: file=\"${file}\""
            continue
        fi

        log "datapack fetch started: file=\"${file}\""
        tmp="${dest}.partial"
        curl -fsSL --max-time 300 -o "$tmp" "$url" \
            || { rm -f "$tmp"; die "Not started: datapack ${file} could not be fetched from ${url}. Check that the URL answers, then start the container again."; }

        if [[ -n "$sha" ]]; then
            actual="$(sha512sum "$tmp" | cut -d' ' -f1)"
            if [[ "$actual" != "$sha" ]]; then
                rm -f "$tmp"
                die "Not started: datapack ${file} does not match its pinned sha512 (wanted ${sha}, got ${actual})."
            fi
        else
            log "datapack checksum not pinned: file=\"${file}\""
        fi

        mv "$tmp" "$dest"
    done

    shopt -s nullglob
    for old in "$dir"/*.zip; do
        [[ -n "${named["${old##*/}"]:-}" ]] && continue
        rm -f "$old"
        log "unnamed datapack removed: file=\"${old##*/}\""
    done
    shopt -u nullglob
}

prepare_backend() {
    local global="$DATA/config/paper-global.yml"

    # A backend that authenticates refuses every forwarded login.
    set_property "$DATA/server.properties" online-mode false
    # A fresh server writes white-list=true, and nobody here is on a white list.
    set_property "$DATA/server.properties" white-list false

    # Paper reads PAPER_VELOCITY_SECRET from the environment and writes it into paper-global.yml on
    # first load, so rotating the secret means editing both. The function seeds only the enable switch.
    if [[ -f "$global" ]]; then
        log "existing file not touched: file=config/paper-global.yml"
    else
        mkdir -p "$(dirname "$global")"
        cat > "$global" <<'YAML'
# Written at first start and not touched again. Paper adds every other setting with its default when
# it loads the file. proxies.velocity.secret arrives as PAPER_VELOCITY_SECRET, and Paper writes it
# into this file on first load.
proxies:
  velocity:
    enabled: true
    online-mode: true
YAML
        log "velocity forwarding enabled, file seeded: file=config/paper-global.yml"
    fi

    # An eye of ender flies only to a stronghold Paper locates, and every stronghold lies beyond the
    # season's early border. Enforced at every start, because Paper writes false into a file it creates.
    local world="$DATA/config/paper-world-defaults.yml" key=locate-structures-outside-world-border
    if [[ ! -f "$world" ]]; then
        printf 'environment:\n  %s: true\n' "$key" > "$world"
        log "file seeded: file=config/paper-world-defaults.yml key=${key} value=true"
    elif grep -qxF "  ${key}: false" "$world"; then
        # A temporary file, as in set_property, since GNU and BSD sed spell -i differently.
        sed "s|^  ${key}: false\$|  ${key}: true|" "$world" > "${world}.tmp" && mv "${world}.tmp" "$world"
        log "property set: file=config/paper-world-defaults.yml key=${key} value=true"
    fi
}

# Prism, the SMP's block logger, stores in the stack's database, in the schema of its own that smp's
# role owns. Written at every start, so the file follows the password; Prism adds its defaults to it.
configure_prism() {
    local url="${NORDTAL_SMP_DATABASE_JDBC_URL:-}" file="$DATA/plugins/prism/storage.conf"
    local pattern='^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/([^/?]+)'
    # Prism is optional, so a setting it cannot use leaves it on its defaults and the SMP starts.
    if [[ ! "$url" =~ $pattern ]]; then
        rm -f "$file"
        warn "database url not postgresql, prism left on defaults: key=NORDTAL_SMP_DATABASE_JDBC_URL"
        return 0
    fi
    if [[ "${NORDTAL_SMP_DATABASE_PASSWORD:?}" == *[[:cntrl:]]* ]]; then
        rm -f "$file"
        warn "database password holds a control character, prism left on defaults"
        return 0
    fi
    local host="${BASH_REMATCH[1]}" port="${BASH_REMATCH[3]:-5432}" database="${BASH_REMATCH[4]}"
    mkdir -p "$(dirname "$file")"
    (
        umask 077
        # A new file, because truncating keeps a mode the password must not get.
        rm -f "$file"
        cat > "$file" <<HOCON
# Written by the entrypoint at every start from smp's database settings. A change here is lost.
primary-storage-type=POSTGRES
postgres {
    host=$(hocon_string "$host")
    port=$(hocon_string "$port")
    database=$(hocon_string "$database")
    schema=prism
    username=$(hocon_string "${NORDTAL_SMP_DATABASE_USERNAME:?}")
    password=$(hocon_string "${NORDTAL_SMP_DATABASE_PASSWORD:?}")
}
HOCON
    )
    # Prism saves the file back with the JVM's umask, so the mode is set again after every write.
    chmod 600 "$file"
    log "prism storage configured: file=plugins/prism/storage.conf schema=prism database=${database} host=${host} port=${port}"
}

# A value as a quoted HOCON string, so a password with a quote or a backslash stays one value.
hocon_string() {
    local value="${1//\\/\\\\}"
    printf '"%s"' "${value//\"/\\\"}"
}

# entrypoint-test.sh runs these functions against fixtures, because a wrong jar fails silently.

# True when version $1 build $2 is newer than version $3 build $4. The comparison is numeric per
# component: as text, "4.10.0" would sort below "4.9.0".
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

# Prints the identity of the jar named $1, as JarName reads it. That is the name before the last `-`
# that a digit follows, so `Plugin-2.6.0-paper.jar` is `Plugin`. Without such a dash, it is the name before the last `-`.
jar_identity() {
    local stem="${1##*/}"
    stem="${stem%.jar}"
    if [[ "$stem" =~ ^(.+)-[0-9].*$ ]]; then
        printf '%s\n' "${BASH_REMATCH[1]}"
    else
        printf '%s\n' "${stem%-*}"
    fi
}

remove_superseded_jars() {
    local cache="$1" kind="$2" keep="$3"
    local old

    shopt -s nullglob
    for old in "$cache/${kind}-"*.jar; do
        [[ "$old" != "$keep" ]] && { rm -f "$old"; log "superseded jar removed: file=${old##*/}"; }
    done
    shopt -u nullglob
    return 0
}

# Prints the filename of the newest `<kind>-<version>-<build>.jar` in directory $1, or nothing. The
# glob uses the kind only, because a version glob would miss a newer minor and fetch the old jar again.
# The function skips names without a numeric build and names whose version is not digits and dots.
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

# Seeds velocity.toml once with only what this deployment needs. Velocity defaults the rest.
# [forced-hosts] is written empty, because Velocity routes to its example servers without the table.
seed_velocity_config() {
    local file="$DATA/velocity.toml" name address tmp

    if [[ -f "$file" ]]; then
        log "existing file not touched: file=velocity.toml"
        return 0
    fi
    if [[ -z "${VELOCITY_SERVERS:-}" ]]; then
        warn "VELOCITY_SERVERS unset, velocity.toml not seeded"
        return 0
    fi

    # The function validates first, because a half-written velocity.toml would pass for the operator's own.
    for entry in $VELOCITY_SERVERS; do
        [[ "$entry" == *=* ]] || die "Not started: VELOCITY_SERVERS holds ${entry}, which is not name=host:port. Correct the entry."
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
        # No motd and no show-max-players: the proxy answers the ping with both, from the settings of the network.
        printf '[servers]\n'
        for entry in $VELOCITY_SERVERS; do
            name="${entry%%=*}"
            address="${entry#*=}"
            printf '%s = "%s"\n' "$name" "$address"
        done
        printf 'try = ["%s"]\n\n' "${VELOCITY_TRY:-${VELOCITY_SERVERS%%=*}}"
        printf '[forced-hosts]\n\n'
        # accepts-transfers lets the standby proxy receive players. Velocity reads it only under
        # [advanced]. This table comes last, because every later key would belong to it.
        printf '[advanced]\n'
        printf 'accepts-transfers = true\n'
    } > "$tmp" || { rm -f "$tmp"; exit 1; }
    mv "$tmp" "$file"
    log "file seeded: file=velocity.toml servers=\"${VELOCITY_SERVERS}\""
}

ensure_velocity_transfers() {
    ensure_velocity_advanced accepts-transfers true
}

# Holds haproxy-protocol to VELOCITY_HAPROXY, which compose.yml sets beside the guard on 25565.
# A wrong value in either direction drops every connection. An unset VELOCITY_HAPROXY leaves the file alone.
ensure_velocity_haproxy() {
    local wanted="${VELOCITY_HAPROXY:-}"

    [[ -n "$wanted" ]] || return 0
    if [[ "$wanted" != "true" && "$wanted" != "false" ]]; then
        warn "VELOCITY_HAPROXY neither true nor false, velocity.toml not touched: value=\"${wanted}\""
        return 0
    fi

    ensure_velocity_advanced haproxy-protocol "$wanted"
}

# Holds one key under [advanced] to a value. The function keeps a correct value, corrects a wrong one
# loudly, adds a missing key under the header, or appends the table last. It leaves an unparsable
# file alone and warns.
#
# @param key    the bare key name under [advanced]
# @param wanted the value of the key, as written into the file
ensure_velocity_advanced() {
    local key="$1" wanted="$2"
    local file="$DATA/velocity.toml" tmp verdict

    [[ -f "$file" ]] || return 0

    # Whitespace is stripped, because TOML allows `key=true` and `key = true`. Comments never match.
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
            print state, (root ? "root" : "-")
        }
    ' "$file") || { warn "velocity.toml unreadable, not touched: key=${key}"; return 0; }

    # Nothing reads a root-level key. The function reports it and does not delete it.
    if [[ "$verdict" == *" root" ]]; then
        warn "velocity setting at file root, not read: file=velocity.toml key=${key}"
    fi

    tmp="${file}.partial"
    case "${verdict%% *}" in
        present)
            log "velocity setting present: file=velocity.toml key=${key} value=${wanted}"
            return 0
            ;;
        wrong)
            # The deployment cannot work otherwise, so the function overrules the operator and says so.
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
            ' "$file" > "$tmp" || { rm -f "$tmp"; warn "velocity.toml not rewritten, not touched: key=${key}"; return 0; }
            mv "$tmp" "$file"
            warn "velocity setting corrected: file=velocity.toml key=${key} value=${wanted}"
            ;;
        table-only)
            # Right after the header, because a second [advanced] table is invalid TOML.
            awk -v line="${key} = ${wanted}" '
                { print }
                /^[[:space:]]*\[advanced\][[:space:]]*$/ && !done { print line; done = 1 }
            ' "$file" > "$tmp" || { rm -f "$tmp"; warn "velocity.toml not rewritten, not touched: key=${key}"; return 0; }
            mv "$tmp" "$file"
            log "velocity setting added: file=velocity.toml key=${key} value=${wanted}"
            ;;
        absent)
            # Last, so that the new table swallows no later keys.
            cp "$file" "$tmp" || { rm -f "$tmp"; warn "velocity.toml not rewritten, not touched: key=${key}"; return 0; }
            {
                printf '\n'
                printf '# Added by the nordtal entrypoint.\n'
                printf '[advanced]\n'
                printf '%s = %s\n' "$key" "$wanted"
            } >> "$tmp" || { rm -f "$tmp"; warn "velocity.toml not rewritten, not touched: key=${key}"; return 0; }
            mv "$tmp" "$file"
            log "velocity table appended: file=velocity.toml table=advanced key=${key} value=${wanted}"
            ;;
    esac
}

# entrypoint-test.sh sources the definitions above. The guard sits before the `:?` checks, which
# would kill a sourcing shell.
[[ "${BASH_SOURCE[0]}" == "${0}" ]] || return 0

: "${SERVER_KIND:?Not started: SERVER_KIND is not set. Set it to paper or velocity.}"
# paper: the exact Minecraft version. velocity: the family name of Fill, whose newest release the script resolves.
: "${SERVER_VERSION:?Not started: SERVER_VERSION is not set. Set it to the Minecraft version for paper, as in 26.2, or the Fill family for velocity, as in 4.0.0.}"
# SERVER_BUILD does not exist: an empty cache gets the newest STABLE build.

case "$SERVER_KIND" in
    paper|velocity) ;;
    *) die "Not started: SERVER_KIND is ${SERVER_KIND}. Set it to paper or velocity." ;;
esac

CACHE="$DATA/.server"
PLUGINS="$DATA/plugins"
SOCK="${MC_TMUX_SOCKET:-/run/mc/tmux.sock}"
SESSION="${MC_TMUX_SESSION:-mc}"

# The Fill API requires a User-Agent that names the project and a contact.
FILL_API="https://fill.papermc.io/v3/projects"
FILL_UA="nordtal-season-2/deploy (+https://github.com/nordtal/season-2)"


mkdir -p "$CACHE" "$PLUGINS" "$(dirname "$SOCK")"

# steward fills the cache, and the newest version and build there runs. Fill is asked only when the
# cache is empty. There is no pin and no way back from a bad build.
JAR_NAME=$(newest_server_jar "$CACHE" "$SERVER_KIND")

if [[ -n "$JAR_NAME" ]]; then
    JAR_PATH="$CACHE/$JAR_NAME"
    log "cached server jar used: file=${JAR_NAME}"
else
    log "no server jar cached, fill api asked: kind=${SERVER_KIND}"
    project=$(curl -fsSL --max-time 60 -H "User-Agent: ${FILL_UA}" "${FILL_API}/${SERVER_KIND}") \
        || die "Not started: the Fill API did not answer, and no ${SERVER_KIND} jar is cached in ${CACHE}. Start the container again once ${FILL_API} answers."

    version=$(jq -er --arg family "$SERVER_VERSION" '
            (.versions[$family] // empty)
            | map(select(test("^[0-9]+(\\.[0-9]+)*$")))
            | sort_by(split(".") | map(tonumber))
            | last // empty' <<<"$project") \
        || die "Not started: Fill lists no released ${SERVER_KIND} version in family ${SERVER_VERSION}. Check SERVER_VERSION against ${FILL_API}/${SERVER_KIND}."
    [[ "$version" == "$SERVER_VERSION" ]] \
        || log "fill family resolved: kind=${SERVER_KIND} family=${SERVER_VERSION} version=${version}"

    # `/builds/latest` includes ALPHA builds, so the list is filtered as steward filters it.
    builds=$(curl -fsSL --max-time 60 -H "User-Agent: ${FILL_UA}" \
        "${FILL_API}/${SERVER_KIND}/versions/${version}/builds") \
        || die "Not started: the ${SERVER_KIND} ${version} builds could not be read from the Fill API, and no jar is cached in ${CACHE}. Start the container again once ${FILL_API} answers."

    meta=$(jq -er '[.[] | select(.channel == "STABLE" and .downloads."server:default")] | max_by(.id)' <<<"$builds") \
        || die "Not started: Fill lists no STABLE ${SERVER_KIND} ${version} build with a server download. Check ${FILL_API}/${SERVER_KIND}/versions/${version}/builds."

    JAR_NAME=$(jq -er '.downloads."server:default".name' <<<"$meta") \
        || die "Not started: the Fill API named a ${SERVER_KIND} ${version} download without a file name."
    JAR_PATH="$CACHE/$JAR_NAME"
    url=$(jq -er '.downloads."server:default".url' <<<"$meta") \
        || die "Not started: the Fill API named a ${SERVER_KIND} ${version} download without a URL."
    sha=$(jq -er '.downloads."server:default".checksums.sha256' <<<"$meta") \
        || die "Not started: the Fill API named a ${SERVER_KIND} ${version} download without a sha256 checksum."
    log "server jar download started: file=${JAR_NAME} build=$(jq -r '.id' <<<"$meta")"

    tmp="${JAR_PATH}.partial"
    curl -fsSL --max-time 600 -H "User-Agent: ${FILL_UA}" -o "$tmp" "$url" \
        || { rm -f "$tmp"; die "Not started: ${JAR_NAME} could not be downloaded from ${url}."; }

    actual=$(sha256sum "$tmp" | cut -d' ' -f1)
    [[ "$actual" == "$sha" ]] \
        || { rm -f "$tmp"; die "Not started: ${JAR_NAME} does not match its sha256 (wanted ${sha}, got ${actual})."; }

    mv "$tmp" "$JAR_PATH"
    log "server jar downloaded and verified: file=${JAR_NAME}"
fi

remove_superseded_jars "$CACHE" "$SERVER_KIND" "$JAR_PATH"

# The version comes from the filename, because SERVER_VERSION is only a family on the proxy.
SERVER_VERSION_RUNNING="${JAR_NAME%.jar}"
SERVER_VERSION_RUNNING="${SERVER_VERSION_RUNNING#"${SERVER_KIND}-"}"
SERVER_BUILD_RUNNING="${SERVER_VERSION_RUNNING##*-}"
SERVER_VERSION_RUNNING="${SERVER_VERSION_RUNNING%-*}"

# steward owns the plugin jars. This script never fetches them.
#
# SERVER_PLUGINS is the label eu.nordtal.plugins of the service: artifact[=jar prefix][?] per plugin.
# Every entry without a `?` must be installed. A jar matches by its prefix (the artefact id where none
# is given), split as JarName splits a filename (jar_identity). The list is a minimum: extra jars are
# fine, and a missing jar refuses the start. A renamed third-party jar also refuses, with the prefix named.
if [[ "${ALLOW_NO_PLUGINS:-false}" != "true" ]]; then
    shopt -s nullglob
    installed=("$PLUGINS"/*.jar)
    shopt -u nullglob

    if (( ${#installed[@]} == 0 )); then
        die "Not started: no plugin jars in ${PLUGINS}. Restart steward-agent against this stack: docker compose restart steward-agent. Set ALLOW_NO_PLUGINS=true if this server runs without plugins."
    fi

    if [[ -n "${SERVER_PLUGINS:-}" ]]; then
        # The identity of every jar in the folder, by the JarName rule.
        present=()
        for jar in "${installed[@]}"; do
            jar="${jar##*/}"
            present+=("$(jar_identity "$jar")")
        done

        missing=()
        required=()
        expected=0
        # `read -a` instead of an unquoted expansion: the shell reads a `?` entry as a glob pattern.
        read -ra entries <<<"$SERVER_PLUGINS"
        for entry in "${entries[@]}"; do
            # An optional plugin may be missing. An artefact without a build must not keep the server down.
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
            die "Not started: ${PLUGINS} lacks ${missing[*]} (present: ${present[*]:-none}). Restart steward-agent once its sources answer: docker compose restart steward-agent. If a jar was renamed, give its new prefix in this service's eu.nordtal.plugins label."
        fi
        log "plugins present: count=${#installed[@]} required=${expected}"
    else
        log "plugins present, SERVER_PLUGINS unset: count=${#installed[@]}"
    fi
fi

JAVA_ARGS=()
if [[ "$SERVER_KIND" == "paper" ]]; then
    [[ "${EULA:-}" == "true" ]] \
        || die "Not started: EULA is not accepted. Set EULA=true."
    printf 'eula=true\n' > "$DATA/eula.txt"
    JAVA_ARGS+=(nogui)

    seed_level_settings

    if [[ -n "${DATAPACK_URLS:-}" ]]; then
        fetch_datapacks "$DATA/${LEVEL_NAME}/datapacks"
    fi

    if [[ -n "${PAPER_VELOCITY_SECRET:-}" ]]; then
        prepare_backend
    fi

    if [[ -n "${NORDTAL_SMP_DATABASE_PASSWORD:-}" ]]; then
        configure_prism
    fi
else
    if [[ -n "${VELOCITY_FORWARDING_SECRET:-}" ]]; then
        printf '%s' "$VELOCITY_FORWARDING_SECRET" > "$DATA/forwarding.secret"
        chmod 600 "$DATA/forwarding.secret"
    fi
    seed_velocity_config
    ensure_velocity_transfers
    ensure_velocity_haproxy
fi

JVM_OPTS="${JVM_OPTS:--Xms${HEAP:-2G} -Xmx${HEAP:-2G} -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+DisableExplicitGC -XX:+AlwaysPreTouch}"

# `docker exec` cannot reach stdin of PID 1, so tmux provides the writable console. `console` attaches
# to it, and `mc <cmd>` sends one command.
log "server started: kind=${SERVER_KIND} version=${SERVER_VERSION_RUNNING} build=${SERVER_BUILD_RUNNING}"

LOG_FILE="$DATA/logs/latest.log"
BOOT_LOG="$DATA/logs/console.log"
mkdir -p "$DATA/logs"
: > "$BOOT_LOG"

rm -f "$SOCK"
rm -f "$REFUSED_START"
# Exported before the tmux server starts, which hands its environment to the JVM.
export NORDTAL_REFUSED_START="$REFUSED_START"

# The options are set before the session exists. `remain-on-exit` keeps a dead pane, so that its exit
# status can be read. Set later, a JVM that dies at once would report 1. `exit-empty off` keeps the
# server alive without a session, so that the option can be set first.
tmux -S "$SOCK" start-server \; set-option -g exit-empty off \; set-option -wg remain-on-exit on

# One invocation: a separate pipe-pane against a pane that already died fails and loses its output.
tmux -S "$SOCK" new-session -d -s "$SESSION" -c "$DATA" -x 200 -y 50 \
    "exec java ${JVM_OPTS} -jar '${JAR_PATH}' ${JAVA_ARGS[*]:-}" \
  \; pipe-pane -o -t "$SESSION" "cat >> '$BOOT_LOG'"
piping=1
# Mirrors latest.log to stdout for `docker logs`. Never use `pipe-pane > /proc/1/fd/1`: a second handle
# on stdout blocks the container, and SIGTERM never arrives.
tail -n 0 -F "$LOG_FILE" 2>/dev/null &
TAIL_PID=$!

# latest.log lacks everything before Paper creates it, so tmux captures the pane into a boot log in
# the volume. The entrypoint empties the log at each start and stops the capture once latest.log exists.

# SIGTERM goes to the JVM, whose shutdown hook saves on Paper and on Velocity. compose allows 180 s,
# because the default of 10 s cuts the save of a large world short.
shutting_down=0
on_term() {
    [[ $shutting_down -eq 1 ]] && return 0
    shutting_down=1
    local pid
    pid=$(tmux -S "$SOCK" display-message -p -t "$SESSION" '#{pane_pid}' 2>/dev/null || true)
    if [[ -n "$pid" ]]; then
        log "shutdown requested, SIGTERM sent: pid=${pid}"
        kill -TERM "$pid" 2>/dev/null || true
    else
        warn "shutdown requested, server process not found"
    fi
}
trap on_term TERM INT

while :; do
    dead=$(tmux -S "$SOCK" display-message -p -t "$SESSION" '#{pane_dead}' 2>/dev/null || echo 1)
    [[ "$dead" == "1" ]] && break
    # Paper logs for itself now, so the capture of the pane stops.
    if (( piping == 1 )) && [[ -s "$LOG_FILE" ]]; then
        tmux -S "$SOCK" pipe-pane -t "$SESSION" 2>/dev/null || true
        piping=0
    fi
    sleep 1 & wait $! || true
done

status=$(tmux -S "$SOCK" display-message -p -t "$SESSION" '#{pane_dead_status}' 2>/dev/null || echo 1)
sleep 1 & wait $! || true
kill "$TAIL_PID" 2>/dev/null || true

# The JVM died before latest.log existed, so the boot capture is the only record. Print it.
if [[ ! -s "$LOG_FILE" && -s "$BOOT_LOG" ]]; then
    warn "server stopped before logging, boot console follows: file=${BOOT_LOG}"
    cat "$BOOT_LOG" >&2
fi

tmux -S "$SOCK" kill-server 2>/dev/null || true
log "server exited: status=${status:-1}"

# A restart would refuse again, and unless-stopped restarts on any status. Held, the container turns
# unhealthy and Steward alerts. steward-agent reads this line as the last of the log.
reason=$(refusal)
if [[ -n "$reason" ]]; then
    printf '[nordtal] REFUSED: %s\n' "$reason" >&2
    while (( shutting_down == 0 )); do
        sleep 1 & wait $! || true
    done
    exit 0
fi
exit "${status:-1}"
