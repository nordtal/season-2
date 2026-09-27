#!/bin/sh
# Runs the newest jar in the volume, or the jar baked into the image while the volume is empty.
# steward-worker fills the volume; the baked jar only makes a first deployment possible.
set -eu

: "${JAR_DIR:?JAR_DIR must be set in the Dockerfile}"
: "${JAR_PREFIX:?JAR_PREFIX must be set in the Dockerfile}"

BAKED=/app/app.jar
jar=""

if [ -d "$JAR_DIR" ]; then
    # sort -V, since 0.10.0 is newer than 0.9.0; more than one jar means a swap in progress or a hand edit.
    jar="$(ls -1 "$JAR_DIR/$JAR_PREFIX"-*.jar 2>/dev/null | sort -V | tail -n 1 || true)"
    count="$(ls -1 "$JAR_DIR/$JAR_PREFIX"-*.jar 2>/dev/null | wc -l | tr -d ' ')"
    if [ "${count:-0}" -gt 1 ]; then
        echo "[entrypoint] WARNING: $count ${JAR_PREFIX} jars in $JAR_DIR. Running the newest;" >&2
        echo "[entrypoint]          the others are not being used by anything. Delete them." >&2
    fi
fi

if [ -n "$jar" ]; then
    echo "[entrypoint] running $jar (from the volume, which is where the worker puts it)"
elif [ -f "$BAKED" ]; then
    echo "[entrypoint] no ${JAR_PREFIX}-*.jar in $JAR_DIR, so this is the jar baked into the image."
    echo "[entrypoint] That is a first deployment, not an error. Fill the volume with:"
    echo "[entrypoint]   docker compose run --rm steward-worker bootstrap"
    jar="$BAKED"
else
    echo "[entrypoint] no ${JAR_PREFIX}-*.jar in $JAR_DIR and no jar baked into this image." >&2
    echo "[entrypoint] There is nothing to run. This image was built wrong." >&2
    exit 1
fi

# exec, so the JVM is PID 1 and receives SIGTERM directly.
exec java ${JAVA_OPTS:-} -jar "$jar" "$@"
