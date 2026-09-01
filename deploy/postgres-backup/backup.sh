#!/bin/sh
# One dump of the season's database, on a schedule, into a volume of its own.
#
# Why this exists rather than pointing Arcane's volume backup at `postgres-data`:
# Arcane snapshots a volume with rustic and stops the containers using it ONLY when the backup
# policy's `StopContainers` flag is set (read from backend/internal/volume/backup.go, 2026-09-01).
# For a database both settings are wrong. With the flag off it tars a live PGDATA, which produces a
# torn copy that raises no error at backup time and is a broken cluster at restore time. With it on,
# PostgreSQL goes down for the length of the tar every night, and every process in this stack fails
# fast on an unreachable database - so that is a nightly login outage.
#
# `pg_dump` has neither problem: it takes an MVCC snapshot, so the dump is consistent as of the
# moment it started, and nothing stops. Arcane's policy then points at `postgres-dumps`, where
# `StopContainers` can safely stay off because nothing holds that volume open between runs, and
# what travels to S3 is a few megabytes rather than a whole data directory.
#
# The image is the SAME postgres image the server runs, so pg_dump is never older than the server
# it dumps. That is not cosmetic: an older pg_dump refuses a newer server outright.
set -eu

DUMP_DIR="${DUMP_DIR:-/dumps}"
KEEP="${BACKUP_KEEP:-14}"
AT="${BACKUP_AT:-04:00}"
RUN_ON_START="${BACKUP_RUN_ON_START:-true}"

log() {
    # Timestamped, unbuffered, on stdout - this is what the operator reads in Arcane's log view.
    echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') backup: $*"
}

fail() {
    log "ERROR: $*"
    # Recorded in the volume as well as the log, because a log rotates and this must not. Its own
    # failure must not mask the failure it is reporting - if the directory is unwritable, the log
    # line above is the whole of the evidence and has already been printed.
    echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') FAILED: $*" > "${DUMP_DIR}/LAST_RESULT" 2>/dev/null || true
}

dump_once() {
    stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
    final="${DUMP_DIR}/nordtal-${stamp}.dump"
    # Dumped under a partial name and renamed only once it is complete and readable. A half-written
    # file that looks like every other dump in the directory is worse than no file at all: it is the
    # one the retention sweep keeps and the restore picks.
    partial="${final}.partial"

    log "dumping ${PGDATABASE} from ${PGHOST}:${PGPORT} as ${PGUSER}"
    if ! pg_dump --format=custom --compress=9 --file="${partial}"; then
        rm -f "${partial}"
        fail "pg_dump failed - no dump was written for ${stamp}"
        return 1
    fi

    # Cheap integrity check: read the archive's own table of contents back. It does not prove the
    # dump restores, which only the drill in ../../todo.md can, but it does catch a truncated file
    # while there is still something to be done about it.
    if ! pg_restore --list "${partial}" > /dev/null 2>&1; then
        rm -f "${partial}"
        fail "the dump for ${stamp} is not a readable archive - discarded"
        return 1
    fi

    mv "${partial}" "${final}"
    size="$(du -h "${final}" | cut -f1)"
    log "wrote $(basename "${final}") (${size})"
    echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') OK $(basename "${final}") ${size}" > "${DUMP_DIR}/LAST_RESULT"

    prune
    return 0
}

prune() {
    # Newest first, drop everything past KEEP. Only files this script itself named are considered,
    # so nothing an operator put in the directory by hand is ever deleted.
    count=0
    for file in $(ls -1t "${DUMP_DIR}"/nordtal-*.dump 2>/dev/null); do
        count=$((count + 1))
        if [ "${count}" -gt "${KEEP}" ]; then
            log "pruning $(basename "${file}") (keeping ${KEEP})"
            rm -f "${file}"
        fi
    done
    # A leftover .partial means a previous run was killed mid-dump. It is never a backup.
    rm -f "${DUMP_DIR}"/nordtal-*.dump.partial
}

# Strips a leading zero so that "08" is eight and not an invalid octal literal, which is the one
# way this arithmetic can fail and it fails for two hours a day.
decimal() {
    printf '%s' "${1#0}" | sed 's/^$/0/'
}

seconds_until() {
    # Seconds from now until the next occurrence of HH:MM, in the container's own timezone.
    target_hour="$(decimal "${1%%:*}")"
    target_minute="$(decimal "${1##*:}")"
    now_hour="$(decimal "$(date '+%H')")"
    now_minute="$(decimal "$(date '+%M')")"
    now_second="$(decimal "$(date '+%S')")"

    now_seconds=$(( now_hour * 3600 + now_minute * 60 + now_second ))
    target_seconds=$(( target_hour * 3600 + target_minute * 60 ))

    delta=$(( target_seconds - now_seconds ))
    if [ "${delta}" -le 0 ]; then
        delta=$(( delta + 86400 ))
    fi
    echo "${delta}"
}

mkdir -p "${DUMP_DIR}"

case "${AT}" in
    [0-9][0-9]:[0-9][0-9]) ;;
    *)
        # Fail closed and say why. A schedule nobody can parse must not silently become "never".
        echo "FATAL: BACKUP_AT must be HH:MM (24-hour), was '${AT}'" >&2
        exit 1
        ;;
esac

log "started - dumping ${PGDATABASE} daily at ${AT} (${TZ:-UTC}), keeping ${KEEP}, into ${DUMP_DIR}"
prune

if [ "${RUN_ON_START}" = "true" ]; then
    # So that a freshly deployed stack has a backup within the minute rather than at 04:00
    # tomorrow, and so that a broken credential is discovered now instead of tonight.
    dump_once || true
fi

# This script is PID 1, so `docker stop` sends SIGTERM here. A shell blocked in a FOREGROUND child
# does not act on it until that child returns - and the child here is a `sleep` of up to 24 hours,
# which would mean every restart of this container waited out the grace period and ended in a
# SIGKILL. The sleep therefore runs in the background and is waited on, because `wait` is what a
# trap can interrupt. Same lesson as ../minecraft/entrypoint.sh, different container.
trap 'log "SIGTERM - stopping"; [ -n "${sleep_pid:-}" ] && kill "${sleep_pid}" 2>/dev/null; exit 0' TERM INT

while true; do
    wait_seconds="$(seconds_until "${AT}")"
    log "next dump in $(( wait_seconds / 3600 ))h $(( (wait_seconds % 3600) / 60 ))m"
    sleep "${wait_seconds}" &
    sleep_pid=$!
    wait "${sleep_pid}" || true
    # Deliberately not `set -e` fatal: one failed night must not stop every following night. The
    # failure is loud in the log and recorded in LAST_RESULT.
    dump_once || true
done
