#!/usr/bin/env bash
#
# Restores one backup archive. It runs on the host, since the interface is inside the stack it restores.
#
#   sudo bash deploy/restore.sh --list                                  what is on the disk
#   sudo bash deploy/restore.sh nordtal-s2_mc-smp-20260913T031500Z.tar.zst
#   sudo bash deploy/restore.sh nordtal-20260913T031500Z.dump
#
# A volume archive REPLACES the volume's directory (or, on an older host, the named volume), so you
# type the volume's name to confirm. A database dump goes into a NEW database beside the live one.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DEFAULT_ENV_FILE="/etc/nordtal/season-2.env"
DEFAULT_PROJECT="nordtal-s2"
BACKUPS_SUFFIX="_steward-backups"

log()  { printf '\033[36m[restore]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[restore]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m[restore]\033[0m %s\n' "$*" >&2; exit 1; }

# Decisions without side effects, above the source guard so deploy/restore-test.sh can drive them.

# The stamp steward writes into every name: UTC, fixed width, no separator a glob would mind.
STAMP_PATTERN='[0-9]{8}T[0-9]{6}Z'

# What this file is: `volume`, `database`, `partial` (an interrupted backup), `mark` (an
# `<archive>.unverified` note) or `unknown`.
archive_kind() {
    local name="$1"
    [[ "$name" == *.partial ]]                                  && { echo partial;  return; }
    [[ "$name" == *.unverified ]]                               && { echo mark;     return; }
    [[ "$name" =~ ^.+-${STAMP_PATTERN}\.tar\.zst$ ]]            && { echo volume;   return; }
    [[ "$name" =~ ^nordtal-${STAMP_PATTERN}\.dump$ ]]           && { echo database; return; }
    echo unknown
}

# The volume an archive belongs to, project prefix included; split at the last hyphen before the stamp.
volume_of() {
    local name="$1"
    [[ "$(archive_kind "$name")" == volume ]] || return 1
    sed -E "s/-${STAMP_PATTERN}\.tar\.zst$//" <<<"$name"
}

# The installation directory for a volume: its name without the project prefix. The caller checks
# that it exists.
directory_for() {
    local volume="$1" project="$2" root="$3"
    [[ -n "$root" && -n "$project" ]] || return 1
    [[ "$volume" == "${project}_"* ]] || return 1
    printf '%s/%s' "${root%/}" "${volume#"${project}_"}"
}

# The stamp of an archive name. `sed -n 1p` reads to EOF, where `head` would fail the pipeline with 141.
stamp_of() {
    local name="$1"
    grep -oE "$STAMP_PATTERN" <<<"$name" | sed -n '1p'
}

# Whether a typed confirmation is exactly the name; an empty name confirms nothing.
restore_confirmed() {
    local wanted="$1" typed="$2"
    [[ -n "$wanted" && "$typed" == "$wanted" ]]
}

# sourced rather than executed
[[ "${BASH_SOURCE[0]}" == "${0}" ]] || return 0

# arguments
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
        -*)                die "unknown argument: $1 (try --help)" ;;
        *)                 [[ -z "$ARCHIVE" ]] || die "one archive at a time."; ARCHIVE="$1"; shift ;;
    esac
done

cd "$ROOT"

command -v docker >/dev/null 2>&1 || die "no docker on this host."
docker info >/dev/null 2>&1 || die "this user cannot talk to the docker daemon. The volumes belong
       to Docker, so this needs root."

# The project name, which prefixes every volume.
PROJECT=""
if [[ -f "$ENV_FILE" ]]; then
    PROJECT="$(grep -m1 -E '^[[:space:]]*(export[[:space:]]+)?COMPOSE_PROJECT_NAME=' "$ENV_FILE" \
        | sed -E 's/^[^=]*=//; s/^"(.*)"$/\1/; s/^'"'"'(.*)'"'"'$/\1/' || true)"
fi
PROJECT="${PROJECT:-$DEFAULT_PROJECT}"
# The installation directory; without it, an older host's named volumes are used.
NORDTAL_DIR=""
if [[ -f "$ENV_FILE" ]]; then
    NORDTAL_DIR="$(grep -m1 -E '^[[:space:]]*(export[[:space:]]+)?NORDTAL_DIR=' "$ENV_FILE" \
        | sed -E 's/^[^=]*=//; s/^"(.*)"$/\1/; s/^'"'"'(.*)'"'"'$/\1/' || true)"
fi

# The `docker run -v` source for a volume: its directory, else the named volume, else nothing.
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
    || die "there is no $BACKUPS_VOLUME on this host - neither a directory under
       '${NORDTAL_DIR:-(no NORDTAL_DIR in $ENV_FILE)}' nor a Docker volume - so there are no
       archives to restore from. --backups-volume names another one; --env-file points at the
       environment file whose COMPOSE_PROJECT_NAME decides the prefix (this run used '$PROJECT')."

# The image that wrote the archives, for the same tar and zstd; every run overrides its entrypoint.
TOOLS="${STEWARD_IMAGE:-ghcr.io/nordtal/steward:latest}"
in_backups() {
    docker run --rm --entrypoint sh \
        -v "$BACKUPS_SOURCE:/backups:ro" "$TOOLS" -c "$1"
}

if $LIST_ONLY; then
    log "archives in $BACKUPS_SOURCE:"
    in_backups 'ls -lh /backups 2>/dev/null || echo "(empty)"'
    # Prints every `.unverified` mark, which may be a reason to pick another archive.
    in_backups 'for m in /backups/*.unverified; do
        [ -e "$m" ] || continue
        printf "\n%s\n" "$(basename "$m")"
        sed "s/^/    /" "$m"
    done'
    exit 0
fi

[[ -n "$ARCHIVE" ]] || die "name an archive. \`--list\` shows what is there, and
       /operations/restore in the interface builds this whole command for you."
[[ "$ARCHIVE" != */* ]] || die "an archive is a file name, not a path: '$ARCHIVE'. Everything is
       read out of $BACKUPS_SOURCE, and a path here would be read inside the container that does
       the reading rather than on this host."

kind="$(archive_kind "$ARCHIVE")"
case "$kind" in
    partial)
        die "'$ARCHIVE' is a .partial file. steward writes every archive under that name and
       renames it only after reading it back, so this one is a backup that was interrupted - there
       is nothing complete in it to restore." ;;
    mark)
        die "'$ARCHIVE' is the mark beside an archive, not the archive. It says why the backup it
       belongs to was taken after a stop nobody could confirm; read it with \`--list\` and then
       name '${ARCHIVE%.unverified}' if you still want it." ;;
    unknown)
        die "'$ARCHIVE' is not a name this deployment writes. A volume archive is
       <volume>-<YYYYMMDDTHHMMSSZ>.tar.zst and a database dump is nordtal-<stamp>.dump." ;;
esac

in_backups "test -f '/backups/$ARCHIVE'" \
    || die "there is no $ARCHIVE in $BACKUPS_VOLUME. \`--list\` shows what is there."

# a database dump: into a new database, never over the live one
if [[ "$kind" == database ]]; then
    # The live database is never overwritten; the dump lands beside it under its own name.
    stamp="$(stamp_of "$ARCHIVE")"
    # sed -n 1p, as in stamp_of.
    container="$(docker ps -q --filter "label=com.docker.compose.project=$PROJECT" \
        --filter "label=com.docker.compose.service=postgres" | sed -n '1p')"
    [[ -n "$container" ]] || die "postgres is not running in project '$PROJECT'. A dump is restored
       BY the database server, so it has to be up - this is the one restore that needs the stack
       working rather than broken."

    target="restore_$stamp"
    log "restoring $ARCHIVE into a NEW database '$target' beside the live one"
    docker exec "$container" sh -c \
        "createdb -U \"\$POSTGRES_USER\" '$target'" \
        || die "could not create '$target'. If it already exists, a previous run made it - drop it
       or restore under another name."
    # The copy is for reading, so the dump's roles and grants are not applied.
    docker exec "$container" sh -c \
        "pg_restore -U \"\$POSTGRES_USER\" -d '$target' --no-owner --no-privileges '/backups/$ARCHIVE'" \
        || warn "pg_restore reported errors. The database '$target' exists and may be incomplete;
       look at it before trusting it."

    log "done. Nothing that was running has changed."
    log "  look inside it:   docker exec -it $container psql -U \"\$POSTGRES_USER\" -d $target"
    log "  throw it away:    docker exec $container dropdb -U \"\$POSTGRES_USER\" $target"
    log "Promoting it over the live database is deliberately not something this script does."
    exit 0
fi

# a volume archive: stop, replace, start
VOLUME="$(volume_of "$ARCHIVE")"

TARGET="$(source_for "$VOLUME" || true)"
if [[ -z "$TARGET" ]]; then
    # Neither exists: the directory is created and named, since a typo looks the same.
    TARGET="$(directory_for "$VOLUME" "$PROJECT" "$NORDTAL_DIR" || true)"
    [[ -n "$TARGET" ]] || die "'$VOLUME' is neither a directory in this installation nor a volume
       on this host, and there is no NORDTAL_DIR in $ENV_FILE to build a directory from. If this
       archive belongs to another deployment, restore it there."
    warn "there is no '$TARGET' yet; it will be created."
    if [[ "$VOLUME" != "$PROJECT"_* ]]; then
        warn "and '$VOLUME' does not start with '${PROJECT}_', so nothing in this deployment"
        warn "mounts it - check the archive name before answering the question below."
    fi
    mkdir -p "$TARGET"
fi

# The containers mounting it, running or not, as the daemon reports them.
mapfile -t holders < <(docker ps -a --format '{{.Names}}' --filter "volume=$VOLUME" | sort)
mapfile -t running < <(docker ps --format '{{.Names}}' --filter "volume=$VOLUME" | sort)

size="$(in_backups "ls -lh '/backups/$ARCHIVE' | awk '{ print \$5 }'")"

# The `.unverified` mark, if any, shown before the confirmation.
mark=""
if in_backups "test -f '/backups/$ARCHIVE.unverified'"; then
    mark="$(in_backups "cat '/backups/$ARCHIVE.unverified'")"
fi

printf '\n'
warn "ABOUT TO REPLACE THE CONTENTS OF A VOLUME."
warn "  archive:    $ARCHIVE  ($size)"
warn "  volume:     $VOLUME"
warn "  restoring:  $TARGET"
warn "  stopping:   ${running[*]:-nothing is running on it}"
warn "  everything in that volume is deleted first. What is in the archive takes its place,"
warn "  and anything created since $(stamp_of "$ARCHIVE") - built houses, edited configs - is gone."
if [[ -n "$mark" ]]; then
    printf '\n'
    warn "  THIS ARCHIVE IS MARKED UNVERIFIED:"
    while IFS= read -r line; do
        warn "    $line"
    done <<<"$mark"
    warn "  It is readable - that was checked when it was written and is checked again below. What"
    warn "  nobody knows is whether the server had finished saving when it was taken."
fi
printf '\n'
printf 'Type the volume name to confirm: '
read -r typed
restore_confirmed "$VOLUME" "$typed" \
    || die "that is not '$VOLUME'. Nothing has been touched."

# The archive is verified before the volume is emptied: `zstd -t` checks the frames, the tar
# traversal checks the archive is complete. They are separate commands, not one pipeline.
log "reading $ARCHIVE through before anything is touched"
in_backups "zstd -t '/backups/$ARCHIVE'" \
    || die "'$ARCHIVE' is not a complete zstd archive - it is truncated or corrupt. Nothing has been
       touched. \`--list\` shows what else is there."
in_backups "zstd -dc '/backups/$ARCHIVE' | tar -tf - >/dev/null" \
    || die "'$ARCHIVE' decompresses but does not hold a complete tar archive. Nothing has been
       touched. \`--list\` shows what else is there."

if (( ${#running[@]} > 0 )); then
    log "stopping ${running[*]}"
    docker stop "${running[@]}" >/dev/null
fi

# `find -mindepth 1 -delete` rather than a glob, which would leave dotfiles behind.
log "replacing $TARGET from $ARCHIVE"
docker run --rm --entrypoint sh \
    -v "$BACKUPS_SOURCE:/backups:ro" \
    -v "$TARGET:/dst" \
    "$TOOLS" -c "set -e
        find /dst -mindepth 1 -delete
        zstd -dc '/backups/$ARCHIVE' | tar -xf - -C /dst" \
    || die "the restore failed. '$TARGET' has been emptied and may be partly filled - do NOT start
       the stack on it. Run this again with the same archive, or with an older one."

if (( ${#running[@]} > 0 )); then
    log "starting ${running[*]} again"
    docker start "${running[@]}" >/dev/null
fi

log "done. $TARGET now holds what $ARCHIVE held."
if (( ${#holders[@]} > ${#running[@]} )); then
    log "these mount it and were not running, so they were left alone: ${holders[*]}"
fi
log "Watch one come back before you trust it: docker compose --env-file $ENV_FILE ps"
