#!/bin/sh
# Runs the jar baked into the image. The image is the version, tagged with its release; a new version
# arrives as a new image, never as a file in a volume.
set -eu

# exec, so the JVM is PID 1 and receives SIGTERM directly.
exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
