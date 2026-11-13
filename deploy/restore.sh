#!/usr/bin/env bash
#
# The emergency restore, for a host where steward-agent does not run. Otherwise a restore is a run
# that an admin asks for on the Backups page of Steward. That run backs up first, counts down and
# replaces the database itself. This script stops nothing gracefully and warns nobody.
#
#   sudo bash deploy/restore.sh --list                                  list the archives on the disk
#   sudo bash deploy/restore.sh nordtal-s2_mc-smp-20260913T031500Z.tar.zst
#   sudo bash deploy/restore.sh nordtal-20260913T031500Z.dump
#
# A volume archive REPLACES the directory of the volume (on an older host, the named volume), so the
# user types the name of the volume to confirm. A database dump goes into a NEW database beside the live one.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DEFAULT_ENV_FILE="/etc/nordtal/season-2.env"
DEFAULT_PROJECT="nordtal-s2"
BACKUPS_SUFFIX="_steward-backups"

log()  { printf '\033[36m[restore]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[restore]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m[restore]\033[0m %s\n' "$*" >&2; exit 1; }

# Pure decisions. They sit above the source guard, so that deploy/restore-test.sh can run them.

# The stamp that steward writes into every name: UTC, fixed width, and no separator that a glob would match.
STAMP_PATTERN='[0-9]{8}T[0-9]{6}Z'

# The kind of a file: `volume`, `database`, `partial` (an interrupted backup), `mark` (an
# `<archive>.unverified` note) or `unknown`.
archive_kind() {
    local name="$1"
    [[ "$name" == *.partial ]]                                  && { echo partial;  return; }
    [[ "$name" == *.unverified ]]                               && { echo mark;     return; }
    [[ "$name" =~ ^.+-${STAMP_PATTERN}\.tar\.zst$ ]]            && { echo volume;   return; }
    [[ "$name" =~ ^nordtal-${STAMP_PATTERN}\.dump$ ]]           && { echo database; return; }
    echo unknown
}

# The volume of an archive, project prefix included. The name is split at the last hyphen before the stamp.
volume_of() {
    local name="$1"
    [[ "$(archive_kind "$name")" == volume ]] || return 1
    sed -E "s/-${STAMP_PATTERN}\.tar\.zst$//" <<<"$name"
}

# The installation directory of a volume: its name without the project prefix. The caller checks that it exists.
directory_for() {
    local volume="$1" project="$2" root="$3"
    [[ -n "$root" && -n "$project" ]] || return 1
    [[ "$volume" == "${project}_"* ]] || return 1
    printf '%s/%s' "${root%/}" "${volume#"${project}_"}"
}

# The stamp of an archive name. `sed -n 1p` reads to EOF, where `head` would end the pipeline with 141.
stamp_of() {
    local name="$1"
    grep -oE "$STAMP_PATTERN" <<<"$name" | sed -n '1p'
}

restore_confirmed() {
    local wanted="$1" typed="$2"
    [[ -n "$wanted" && "$typed" == "$wanted" ]]
}

# Describes a mark in words. steward-agent writes the mark as one line of JSON that holds the run and
# the services whose stop it could not confirm. Any other content is shown as it is.
mark_words() {
    local mark="$1" run services
    run="$(sed -n 's/^{"run":\([0-9]*\),"services":\[.*\]}$/\1/p' <<<"$mark")"
    services="$(sed -n 's/^{"run":[0-9]*,"services":\[\(.*\)\]}$/\1/p' <<<"$mark")"
    if [[ -z "$run" || -z "$services" ]]; then
        printf '%s\n' "$mark"
        return
    fi
    services="${services//\"/}"
    if [[ "$services" == *,* ]]; then
        printf 'run %s stopped %s, and how they ended could not be read back\n' "$run" "${services//,/, }"
    else
        printf 'run %s stopped %s, and how it ended could not be read back\n' "$run" "$services"
    fi
}

# The values for the volume filter of the daemon, one per line: the name of the volume and, when the
# target is a directory, its path. The daemon does not know a bind-mounted directory as a volume.
holder_filters() {
    local volume="$1" target="$2"
    printf '%s\n' "$volume"
    if [[ -n "$target" && "$target" != "$volume" ]]; then
        printf '%s\n' "$target"
    fi
}

[[ "${BASH_SOURCE[0]}" == "${0}" ]] || return 0

ARCHIVE=""
ENV_FILE="${STEWARD_ENV_FILE:-$DEFAULT_ENV_FILE}"
BACKUPS_VOLUME=""
LIST_ONLY=false

while (( $# > 0 )); do
    case "$1" in
        --list)            LIST_ONLY=true; shift ;;
        --env-file)        ENV_FILE="${2:?--env-file needs a path}"; shift 2 ;;
        --backups-volume)  BACKUPS_VOLUME="${2:?--backups-volume needs a name}"; shift 2 ;;
        -h|--help)         sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*)                die "Unknown argument: $1. --help lists the arguments." ;;
        *)                 [[ -z "$ARCHIVE" ]] || die "More than one archive named. Name one archive per run."; ARCHIVE="$1"; shift ;;
    esac
done

cd "$ROOT"

command -v docker >/dev/null 2>&1 || die "No docker on this host. Install Docker and run this again."
docker info >/dev/null 2>&1 || die "This user cannot reach the docker daemon. Run this again as root."

# The project name, which prefixes every volume.
PROJECT=""
if [[ -f "$ENV_FILE" ]]; then
    PROJECT="$(grep -m1 -E '^[[:space:]]*(export[[:space:]]+)?COMPOSE_PROJECT_NAME=' "$ENV_FILE" \
        | sed -E 's/^[^=]*=//; s/^"(.*)"$/\1/; s/^'"'"'(.*)'"'"'$/\1/' || true)"
fi
PROJECT="${PROJECT:-$DEFAULT_PROJECT}"
# The installation directory. Without it, the script uses the named volumes of an older host.
NORDTAL_DIR=""
if [[ -f "$ENV_FILE" ]]; then
    NORDTAL_DIR="$(grep -m1 -E '^[[:space:]]*(export[[:space:]]+)?NORDTAL_DIR=' "$ENV_FILE" \
        | sed -E 's/^[^=]*=//; s/^"(.*)"$/\1/; s/^'"'"'(.*)'"'"'$/\1/' || true)"
fi

# The `docker run -v` source of a volume: its directory, else the named volume, else nothing.
source_for() {
    local volume="$1" directory
    directory="$(directory_for "$volume" "$PROJECT" "$NORDTAL_DIR" || true)"
    if [[ -n "$directory" && -d "$directory" ]]; then
        printf '%s' "$directory"
        return 0
    fi
    if docker volume inspect "$volume" >/dev/null 2>&1; then
        printf '%s' "$volume"
        return 0
    fi
    return 1
}

BACKUPS_VOLUME="${BACKUPS_VOLUME:-${PROJECT}${BACKUPS_SUFFIX}}"
BACKUPS_SOURCE="$(source_for "$BACKUPS_VOLUME" || true)"
[[ -n "$BACKUPS_SOURCE" ]] \
    || die "No archives found: project $PROJECT has no $BACKUPS_VOLUME, neither under ${NORDTAL_DIR:-a NORDTAL_DIR} nor as a Docker volume. Name the volume with --backups-volume, or the environment file with --env-file."

# The image that wrote the archives, so that tar and zstd match. The container of the agent of the
# project names it, running or not, because images are tagged by release.
TOOLS="${STEWARD_AGENT_IMAGE:-$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT" \
    --filter label=com.docker.compose.service=steward-agent --format '{{.Image}}' | sed -n '1p')}"
[[ -n "$TOOLS" ]] || die "No image to read the archives with: project $PROJECT has no steward-agent container. Name one with STEWARD_AGENT_IMAGE=ghcr.io/nordtal/steward-agent:<version>."
in_backups() {
    docker run --rm --entrypoint sh \
        -v "$BACKUPS_SOURCE:/backups:ro" "$TOOLS" -c "$1"
}

if $LIST_ONLY; then
    log "archives listed: dir=$BACKUPS_SOURCE"
    in_backups 'ls -lh /backups 2>/dev/null || echo "(empty)"'
    # Prints every `.unverified` mark, because a mark can be a reason to pick another archive.
    in_backups 'for m in /backups/*.unverified; do
        [ -e "$m" ] || continue
        printf "%s\t%s\n" "$(basename "$m")" "$(cat "$m")"
    done' | while IFS=$'\t' read -r name record; do
        printf '\n%s\n    %s\n' "$name" "$(mark_words "$record")"
    done
    exit 0
fi

[[ -n "$ARCHIVE" ]] || die "No archive named. Run with --list to see the archives, or copy the command from /operations/restore in Steward."
[[ "$ARCHIVE" != */* ]] || die "$ARCHIVE is a path. Name the archive by its file name alone."

kind="$(archive_kind "$ARCHIVE")"
case "$kind" in
    partial)
        die "$ARCHIVE is an interrupted backup and holds nothing complete. Run with --list and pick another archive." ;;
    mark)
        die "$ARCHIVE is the mark beside an archive. Name ${ARCHIVE%.unverified} to restore that archive." ;;
    unknown)
        die "$ARCHIVE is not an archive name of this deployment. Name a <volume>-<YYYYMMDDTHHMMSSZ>.tar.zst or nordtal-<stamp>.dump file." ;;
esac

in_backups "test -f '/backups/$ARCHIVE'" \
    || die "There is no $ARCHIVE in $BACKUPS_VOLUME. Run with --list to see the archives."

if [[ "$kind" == database ]]; then
    # The dump never overwrites the live database. It lands beside it under its own name.
    stamp="$(stamp_of "$ARCHIVE")"
    # sed -n 1p, as in stamp_of.
    container="$(docker ps -q --filter "label=com.docker.compose.project=$PROJECT" \
        --filter "label=com.docker.compose.service=postgres" | sed -n '1p')"
    [[ -n "$container" ]] || die "Postgres is not running in project $PROJECT. Start it with docker compose --env-file $ENV_FILE up -d postgres and run this again."

    target="restore_$stamp"
    log "database restore started: file=$ARCHIVE database=$target"
    docker exec "$container" sh -c \
        "createdb -U \"\$POSTGRES_USER\" '$target'" \
        || die "Database $target could not be created. If an earlier run made it, drop it with docker exec $container dropdb -U \"\$POSTGRES_USER\" $target and run this again."
    # The copy is for reading, so the script applies no roles and grants of the dump.
    docker exec "$container" sh -c \
        "pg_restore -U \"\$POSTGRES_USER\" -d '$target' --no-owner --no-privileges '/backups/$ARCHIVE'" \
        || warn "pg_restore reported errors, database may be incomplete: database=$target"

    log "database restored beside the live one: database=$target"
    log "  look inside it:   docker exec -it $container psql -U \"\$POSTGRES_USER\" -d $target"
    log "  throw it away:    docker exec $container dropdb -U \"\$POSTGRES_USER\" $target"
    exit 0
fi

VOLUME="$(volume_of "$ARCHIVE")"

TARGET="$(source_for "$VOLUME" || true)"
if [[ -z "$TARGET" ]]; then
    # When neither exists, the script creates and names the directory, because a typo looks the same.
    TARGET="$(directory_for "$VOLUME" "$PROJECT" "$NORDTAL_DIR" || true)"
    [[ -n "$TARGET" ]] || die "$VOLUME is neither a directory of this installation nor a Docker volume, and $ENV_FILE has no NORDTAL_DIR. Restore this archive on the host of its deployment."
    warn "directory missing: dir=$TARGET"
    if [[ "$VOLUME" != "$PROJECT"_* ]]; then
        warn "volume outside this project, mounted by nothing: volume=$VOLUME project=$PROJECT"
    fi
    mkdir -p "$TARGET"
fi

# The containers that mount the volume by name or by path, running or not (`-a`), as the daemon reports them.
holders_of() {
    local filter
    holder_filters "$VOLUME" "$TARGET" | while IFS= read -r filter; do
        docker ps "$@" --format '{{.Names}}' --filter "volume=$filter"
    done | sort -u
}
mapfile -t holders < <(holders_of -a)
mapfile -t running < <(holders_of)

size="$(in_backups "ls -lh '/backups/$ARCHIVE' | awk '{ print \$5 }'")"

mark=""
if in_backups "test -f '/backups/$ARCHIVE.unverified'"; then
    mark="$(mark_words "$(in_backups "cat '/backups/$ARCHIVE.unverified'")")"
fi

printf '\n'
warn "ABOUT TO REPLACE THE CONTENTS OF A VOLUME."
warn "  archive:    $ARCHIVE  ($size)"
warn "  volume:     $VOLUME"
warn "  restoring:  $TARGET"
warn "  stopping:   ${running[*]:-nothing is running on it}"
warn "  everything in it is deleted, and whatever changed since $(stamp_of "$ARCHIVE") is lost."
if [[ -n "$mark" ]]; then
    printf '\n'
    warn "  THIS ARCHIVE IS MARKED UNVERIFIED:"
    while IFS= read -r line; do
        warn "    $line"
    done <<<"$mark"
    warn "  It is readable. Whether the server had finished saving is not known."
fi
printf '\n'
printf 'Type the volume name to confirm: '
read -r typed
restore_confirmed "$VOLUME" "$typed" \
    || die "Not restored: the name typed is not $VOLUME, and nothing was touched. Run this again to restore."

# The script verifies the archive before it empties the volume. `zstd -t` checks the frames, and the
# traversal by tar checks that the archive is complete. They are two commands, not one pipeline.
log "archive check started: file=$ARCHIVE"
in_backups "zstd -t '/backups/$ARCHIVE'" \
    || die "$ARCHIVE is truncated or corrupt, and nothing was touched. Run with --list and pick another archive."
in_backups "zstd -dc '/backups/$ARCHIVE' | tar -tf - >/dev/null" \
    || die "$ARCHIVE holds no complete tar archive, and nothing was touched. Run with --list and pick another archive."

if (( ${#running[@]} > 0 )); then
    log "containers stopping: containers=\"${running[*]}\""
    docker stop "${running[@]}" >/dev/null
fi

# `find -mindepth 1 -delete` instead of a glob, which would leave dotfiles behind.
log "volume replace started: dir=$TARGET file=$ARCHIVE"
docker run --rm --entrypoint sh \
    -v "$BACKUPS_SOURCE:/backups:ro" \
    -v "$TARGET:/dst" \
    "$TOOLS" -c "set -e
        find /dst -mindepth 1 -delete
        zstd -dc '/backups/$ARCHIVE' | tar -xf - -C /dst" \
    || die "Restore failed: $TARGET is emptied and may be partly filled. Do not start the stack on it; run this again with the same archive or an older one."

if (( ${#running[@]} > 0 )); then
    log "containers starting again: containers=\"${running[*]}\""
    docker start "${running[@]}" >/dev/null
fi

log "volume restored: dir=$TARGET file=$ARCHIVE"
if (( ${#holders[@]} > ${#running[@]} )); then
    log "stopped holders left stopped: containers=\"${holders[*]}\""
fi
log "Check that the containers come back: docker compose --env-file $ENV_FILE ps"
