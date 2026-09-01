#!/bin/sh
# Pick the jar to run: the one in the volume if there is one, the one baked into the image if
# there is not.
#
# WHY BOTH. Since 2026-09-01 the updater owns this container's jar the same way it owns every
# plugin jar - it downloads it, verifies it, puts it in the volume and deletes the one it
# supersedes. That is what makes this module roll back the way everything else does, and it is why
# `docker compose build` is no longer part of updating anything.
#
# The baked jar is what makes a FIRST deployment possible at all. The volume is empty before the
# first `updater apply`, and a container that refused to start on an empty volume could never be
# the thing that fills it (the updater) or the thing an operator needs in order to run the
# bootstrap at all. So the image still carries a jar, and it is a floor and not a version: what
# actually runs is printed on every start, and `docker compose run --rm updater` reports it too.
#
# THE COST, STATED PLAINLY: the baked jar goes stale. An image built from v0.2.0 keeps carrying
# v0.2.0 forever, and after the volume has been filled once nothing ever reads it again. It is not
# what is running and it must not be read as what is running.
set -eu

: "${JAR_DIR:?JAR_DIR must be set in the Dockerfile}"
: "${JAR_PREFIX:?JAR_PREFIX must be set in the Dockerfile}"

BAKED=/app/app.jar
jar=""

if [ -d "$JAR_DIR" ]; then
    # sort -V, not sort: 0.10.0 is newer than 0.9.0 and lexical order says otherwise.
    #
    # There should only ever be one. The updater deletes the jar it supersedes right after it
    # places the new one, so two at once means either an apply caught mid-swap - a window of
    # milliseconds - or somebody put one there by hand. Taking the newest is the right answer to
    # both, and the warning is what makes the second one findable.
    jar="$(ls -1 "$JAR_DIR/$JAR_PREFIX"-*.jar 2>/dev/null | sort -V | tail -n 1 || true)"
    count="$(ls -1 "$JAR_DIR/$JAR_PREFIX"-*.jar 2>/dev/null | wc -l | tr -d ' ')"
    if [ "${count:-0}" -gt 1 ]; then
        echo "[entrypoint] WARNING: $count ${JAR_PREFIX} jars in $JAR_DIR. Running the newest;" >&2
        echo "[entrypoint]          the others are not being used by anything. Delete them." >&2
    fi
fi

if [ -n "$jar" ]; then
    echo "[entrypoint] running $jar (from the volume, which is where the updater puts it)"
elif [ -f "$BAKED" ]; then
    echo "[entrypoint] no ${JAR_PREFIX}-*.jar in $JAR_DIR, so this is the jar baked into the image."
    echo "[entrypoint] That is a first deployment, not an error. Fill the volume with:"
    echo "[entrypoint]   docker compose run --rm updater apply"
    jar="$BAKED"
else
    echo "[entrypoint] no ${JAR_PREFIX}-*.jar in $JAR_DIR and no jar baked into this image." >&2
    echo "[entrypoint] There is nothing to run. This image was built wrong." >&2
    exit 1
fi

# exec, so the JVM is PID 1 and receives SIGTERM directly: a redeploy has to be able to shut this
# down cleanly, and a shell in between would swallow the signal.
exec java ${JAVA_OPTS:-} -jar "$jar" "$@"
