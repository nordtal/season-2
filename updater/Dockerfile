# Runtime-only image, exactly like discord-bot/Dockerfile: the jar is built by Gradle first and
# passed in. A self-contained build stage in a multi-module repo would have to copy the whole tree
# and rebuild every module.
#
#   ./gradlew :updater:shadowJar
#   docker build -t nordtal/updater:<version> \
#     --build-arg JAR=build/libs/updater-<version>.jar updater
#
# NOTHING PUBLISHES THIS IMAGE. .github/workflows/release.yml pushes the bot image to the GHCR and
# no other image (checked 2026-09-01), so the host builds it from this repository - the same
# arrangement deploy/minecraft/ has.
FROM eclipse-temurin:25-jre

WORKDIR /app

# The jar baked in here is the FLOOR, not the version. The updater runs whatever updater-*.jar is
# in /volumes/updater - a volume it fills itself, on the run that installs everything else - and
# this copy is what makes the very first run possible, before that volume has anything in it.
# docker-entrypoint.sh says the same thing at length and prints which one it picked on every start.
ARG JAR
COPY ${JAR} /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

RUN mkdir -p config \
    && chmod +x /app/docker-entrypoint.sh

# WHAT THIS CONTAINER IS FOR, and what it is not:
#
# It resolves the newest version of every jar the network runs, compares that against the jars in
# the mounted volumes, installs what is asked for, owns the database schema, and restarts the
# stack through Arcane when somebody presses the button.
#
# IT IS A SERVICE AND IT IS ALSO A COMMAND. `serve` is the long-running mode compose starts:
#
#   docker compose run --rm updater report     # resolve and report. Writes nothing.
#   docker compose run --rm updater migrate    # the schema, and nothing else.
#   docker compose run --rm updater apply      # migrate, then install. Restarts nothing.
#   docker compose up -d updater               # `serve`, which is what the compose file passes.
#
# `serve` IS NOT A SCHEDULER. It applies the schema once at startup and then does nothing at all
# until somebody writes a row into update_request - no timer, no watch, no "check for updates on
# boot". The first rule of this module is that a crash restart at three in the morning does not
# move a version: the container comes back on exactly the jars it was running, and an update
# happens because a person asked for one.
#
# CONFIGURATION - one file, one environment namespace:
#
#   config/updater.yml     NORDTAL_UPDATER_*
#
# Unlike every other config in this repository, its defaults are the real values and a fresh file
# is what you want: the repositories and project ids are facts about this project, not about a
# deployment. The variable is the setting's path with '-' becoming '_', so the release to follow
# is NORDTAL_UPDATER_SEASON_RELEASE. The environment wins over the file and is never written back.
ENV NORDTAL_UPDATER_CONFIG_DIR=/app/config

# The updater's own jar lives where it puts it: under volumes-root, beside the four Minecraft
# volumes and the bot's, so that installing it needs no special case anywhere in the code.
ENV JAR_DIR=/volumes/updater
ENV JAR_PREFIX=updater

# The healthcheck compose uses is `test -f /tmp/updater-ready`, written once the schema is current
# and the request loop is about to start. Every other service in the stack waits for it: this
# container is the only one that migrates, and a plugin coming up against a schema older than
# itself is the failure the whole arrangement exists to prevent.
HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=30 \
    CMD test -f /tmp/updater-ready

VOLUME ["/app/config"]

ENTRYPOINT ["/app/docker-entrypoint.sh"]
