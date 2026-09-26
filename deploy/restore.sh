#!/usr/bin/env bash
#
# Put one archive back. The other half of §9a, and the half that is never a button.
#
# WHY IT IS A SCRIPT ON THE HOST AND NOT A PAGE IN THE INTERFACE: a backup is needed on the day
# something is broken, and steward-ui runs as a container in the very stack it would be restoring.
# A button there would work in every situation except the one it exists for. So /operations/restore
# builds the command and a person runs it here - which is where they would have to be anyway.
#
# Where it reads and writes: the installation directory, which holds one directory per volume -
# `mc-smp` for `nordtal-s2_mc-smp`. A host installed before that layout still has the named Docker
# volumes instead. It works with either and says which one it is using.
#
#   sudo bash deploy/restore.sh --list                                  what is on the disk
#   sudo bash deploy/restore.sh nordtal-s2_mc-smp-20260913T031500Z.tar.zst
#   sudo bash deploy/restore.sh nordtal-20260913T031500Z.dump
#
# A VOLUME ARCHIVE REPLACES A VOLUME. Not merges - replaces. Everything in that volume that is
# younger than the archive is gone, and on nordtal-s2_mc-smp that is Nordtal, a hand-built world
# that is in no repository and in no release. So this script does what `deploy/dev reset` does: it
# makes you type the name of the thing it is about to overwrite. Not "yes" - the name.
#
# A DATABASE DUMP DOES NOT REPLACE ANYTHING. It is restored into a NEW database beside the live one,
# so you can look inside it before anything points at it. Promoting it is a separate, deliberate act
# and this script does not do it.
#
# Everything here runs from the repository root whatever directory it is called from.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DEFAULT_ENV_FILE="/etc/nordtal/season-2.env"
DEFAULT_PROJECT="nordtal-s2"
BACKUPS_SUFFIX="_steward-backups"

log()  { printf '\033[36m[restore]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[restore]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m[restore]\033[0m %s\n' "$*" >&2; exit 1; }

# decisions, kept apart so they can be tested
# Same arrangement as deploy/nordtal.sh and deploy/dev: everything above the source guard is a question
# with an answer and no side effect, and deploy/restore-test.sh drives it without Docker. The two
# that matter are which kind of file this is and whether a confirmation counts - one decides whether
# a world is overwritten, the other decides whether it happens on a bare Return.

# The stamp steward-worker writes into every name: UTC, fixed width, no separator a glob would mind.
STAMP_PATTERN='[0-9]{8}T[0-9]{6}Z'

# What this file is: `volume`, `database`, `partial`, `mark` or `unknown`.
#
# `partial` is its own answer and not an error message, because a half-written archive is the one
# thing in that directory that LOOKS restorable. steward-worker writes under `.partial` and renames
# only after reading the file back, so a name still carrying it is a backup that was interrupted.
#
# `mark` is the other one that is not an archive but sits beside them: steward-worker writes
# `<archive>.unverified` next to a backup it took after a stop it could not confirm the end of. It
# is a sentence to read, not a thing to restore, and saying that is more use than `unknown`.
archive_kind() {
    local name="$1"
    [[ "$name" == *.partial ]]                                  && { echo partial;  return; }
    [[ "$name" == *.unverified ]]                               && { echo mark;     return; }
    [[ "$name" =~ ^.+-${STAMP_PATTERN}\.tar\.zst$ ]]            && { echo volume;   return; }
    [[ "$name" =~ ^nordtal-${STAMP_PATTERN}\.dump$ ]]           && { echo database; return; }
    echo unknown
}

# The volume an archive belongs to. The name already carries the compose project prefix, because
# that is what the volume is actually called - `nordtal-s2_mc-smp`, not `mc-smp`. A volume name
# cannot contain the stamp, so the last dash before it is the split and a volume whose own name has
# dashes in it comes back whole.
volume_of() {
    local name="$1"
    [[ "$(archive_kind "$name")" == volume ]] || return 1
    sed -E "s/-${STAMP_PATTERN}\.tar\.zst$//" <<<"$name"
}

# Where a volume's contents actually live: a directory under the installation, or a named Docker
# volume on a host installed before that layout.
#
# An archive is still named after the volume it came from - `nordtal-s2_mc-smp` - because that is
# what the backup mounts it as and renaming archives would break every archive already written. The
# installation directory holds a directory per volume, named exactly like the volume without the
# project prefix, and that is not a coincidence to be worked around: it is the mapping. `mc-smp`
# the directory IS `nordtal-s2_mc-smp` the volume, and this function is the whole translation.
#
# It answers nothing about whether that directory exists - the caller checks, and falls back to a
# real Docker volume of that name if it does not, because a deployment that was installed before
# this change still has one.
directory_for() {
    local volume="$1" project="$2" root="$3"
    [[ -n "$root" && -n "$project" ]] || return 1
    [[ "$volume" == "${project}_"* ]] || return 1
    printf '%s/%s' "${root%/}" "${volume#"${project}_"}"
}

# The stamp out of any archive name, for naming the scratch database after the dump it came from.
#
# `sed -n 1p` and not `head -1`, and that is not a style preference:
# head closes the pipe the moment it has its line, grep upstream takes SIGPIPE, and `set -o pipefail`
# then reports 141 for a pipeline that did exactly what it was asked. Measured on this host: about
# one in two thousand such pipelines. sed reads its input to the end, so there is no early close and
# nothing to race. Every pipeline in this file is under pipefail, so this applies to all of them.
stamp_of() {
    local name="$1"
    grep -oE "$STAMP_PATTERN" <<<"$name" | sed -n '1p'
}

# Whether a typed confirmation matches. Deliberately identical in shape to deploy/dev's: the name
# itself, nothing else, and an empty target confirms nothing - otherwise a bare Return on a prompt
# somebody did not read would overwrite a world.
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
        -h|--help)         sed -n '2,23p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*)                die "unknown argument: $1 (try --help)" ;;
        *)                 [[ -z "$ARCHIVE" ]] || die "one archive at a time."; ARCHIVE="$1"; shift ;;
    esac
done

cd "$ROOT"

command -v docker >/dev/null 2>&1 || die "no docker on this host."
docker info >/dev/null 2>&1 || die "this user cannot talk to the docker daemon. The volumes belong
       to Docker, so this needs root."

# Which deployment. The project name is the prefix on every volume, and getting it wrong here means
# restoring into volumes nothing mounts - which fails silently, as a server that comes back empty.
PROJECT=""
if [[ -f "$ENV_FILE" ]]; then
    PROJECT="$(grep -m1 -E '^[[:space:]]*(export[[:space:]]+)?COMPOSE_PROJECT_NAME=' "$ENV_FILE" \
        | sed -E 's/^[^=]*=//; s/^"(.*)"$/\1/; s/^'"'"'(.*)'"'"'$/\1/' || true)"
fi
PROJECT="${PROJECT:-$DEFAULT_PROJECT}"
# The installation directory, which is where every volume in this deployment now is. An environment
# file without it was written before that layout, and everything below then falls back to
# the named volumes it was written for.
NORDTAL_DIR=""
if [[ -f "$ENV_FILE" ]]; then
    NORDTAL_DIR="$(grep -m1 -E '^[[:space:]]*(export[[:space:]]+)?NORDTAL_DIR=' "$ENV_FILE" \
        | sed -E 's/^[^=]*=//; s/^"(.*)"$/\1/; s/^'"'"'(.*)'"'"'$/\1/' || true)"
fi

# What `docker run -v <this>:/dst` should be handed for one of this deployment's volumes: the
# directory if the installation has one, the named volume if it still does, and nothing if neither
# - which is a typo or a deployment that points that volume somewhere this script cannot guess.
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

# The image the archives were written with, so they are read with the same tar and the same zstd.
# Its entrypoint is the worker, so every run below overrides it.
TOOLS="${STEWARD_WORKER_IMAGE:-ghcr.io/nordtal/steward-worker:latest}"
in_backups() {
    docker run --rm --entrypoint sh \
        -v "$BACKUPS_SOURCE:/backups:ro" "$TOOLS" -c "$1"
}

if $LIST_ONLY; then
    log "archives in $BACKUPS_SOURCE:"
    in_backups 'ls -lh /backups 2>/dev/null || echo "(empty)"'
    # And what any of them say about themselves. A `.unverified` file is one line in an `ls` and
    # the whole reason somebody would pick a different archive, so it is printed rather than left
    # for them to notice.
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
        die "'$ARCHIVE' is a .partial file. steward-worker writes every archive under that name and
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
    # THIS DOES NOT RESTORE THE DATABASE, and that is the decision. A dump put straight over the
    # live one destroys the state you would need to work out what went wrong, and it does it before
    # anybody has looked inside the dump. So it lands beside the live database under its own name,
    # and pointing anything at it is a separate act with its own thinking.
    stamp="$(stamp_of "$ARCHIVE")"
    # sed -n 1p rather than head -1, for the reason written at stamp_of: head would close the pipe
    # under docker and pipefail would turn its SIGPIPE into a failed restore.
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
    # --no-owner --no-privileges: the roles in the dump are the ones in the live cluster, and this
    # copy exists to be READ. Re-granting them here would be a second, half-finished deployment.
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
    # Neither a directory nor a volume exists for it. That is the disaster-recovery case rather
    # than a typo, but it is also exactly what a typo looks like, so the directory is named out
    # loud and created rather than conjured quietly.
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

# Which containers hold it, running or not. Asked of the daemon rather than worked out from
# compose.yml: what matters is what is actually mounting the volume on this host right now.
mapfile -t holders < <(docker ps -a --format '{{.Names}}' --filter "volume=$VOLUME" | sort)
mapfile -t running < <(docker ps --format '{{.Names}}' --filter "volume=$VOLUME" | sort)

size="$(in_backups "ls -lh '/backups/$ARCHIVE' | awk '{ print \$5 }'")"

# The mark, if steward-worker left one, and before the confirmation rather than after it. An
# archive taken after an unverified stop is still very probably a good archive - which is exactly
# why it must not be discovered afterwards.
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

# THE ARCHIVE IS READ THROUGH BEFORE ANYTHING IS TOUCHED, and the reason is the sentence in the
# failure message below: `find -mindepth 1 -delete` runs before the extraction, so a truncated or
# corrupt archive was discovered with the volume already empty. The whole point of a restore is that
# the state it replaces was worth keeping until the replacement was known to be good.
#
# Two checks, because one would not do it: `zstd -t` verifies the compressed frames and their
# checksum, and the tar traversal verifies that what is inside them is a complete archive. Only the
# first can be trusted to set the exit status of a pipeline, which is why it is not one.
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

# Unpacked by a container of the same image that wrote it, so the tar and the zstd are the ones the
# archive was made with. `find -mindepth 1 -delete` rather than `rm -rf /dst/*`, because a glob
# misses dotfiles - and a world's `.server` cache and a plugin's dotfiles would then survive a
# restore and mix two states, which is the failure this whole step exists to avoid.
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
