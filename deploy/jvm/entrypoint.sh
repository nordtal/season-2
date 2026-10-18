#!/bin/sh
# Runs the jar that is part of the image. The image is the version and has the tag of its release.
# A new version arrives as a new image, never as a file in a volume.
set -eu

. /app/secrets.sh
read_secrets "$SECRETS_DIRECTORY/secrets.env"

# exec makes the JVM PID 1, so that it receives SIGTERM directly.
exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
