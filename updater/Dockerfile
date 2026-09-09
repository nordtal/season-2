# Runtime-only image, exactly like discord-bot/Dockerfile: the jar is built by Gradle first and
# passed in. A self-contained build stage in a multi-module repo would have to copy the whole tree
# and rebuild every module.
#
#   ./gradlew :updater:shadowJar
#   docker build -t ghcr.io/nordtal/updater:latest updater
#
# THE VERSION IS NOT NAMED ANYWHERE IN THIS BUILD, and that is deliberate. It used to arrive as a
# --build-arg fed from a variable in .env, which is a version number written down a second time and
# therefore a version number that goes stale: .env.example still said 0.2.3 when gradle.properties
# said 0.8.1, and the only thing that produced was a build looking for a jar nobody had built.
#
# release.yml PUSHES THIS IMAGE, and has since 2026-09-02: ghcr.io/nordtal/updater, tagged with the
# version and with `latest`, alongside the bot, the minecraft image and the backup sidecar. The
# comment here said the opposite until 2026-09-09 and was simply out of date - the reason it changed
# is that Arcane deploys by PULLING and never builds, so an image existing only in a host's local
# daemon fails the deploy with `denied` from the registry.
FROM eclipse-temurin:25-jre

WORKDIR /app

# The jar baked in here is the FLOOR, not the version. The updater runs whatever updater-*.jar is
# in /volumes/updater - a volume it fills itself, on the run that installs everything else - and
# this copy is what makes the very first run possible, before that volume has anything in it.
# docker-entrypoint.sh says the same thing at length and prints which one it picked on every start.
#
# THE .dockerignore IS LOAD-BEARING HERE. `shadowJar` leaves two jars side by side and this glob
# matches both; .dockerignore keeps the -thin one out of the context so that exactly one does.
# Measured on 2026-09-09 with Docker 29.7.2: with both in the context BuildKit does NOT fail on
# several sources into one destination - it writes one of them and says nothing. It happened to be
# the right one, because `-thin.jar` sorts before `.jar`, which is an ordering accident and not a
# guarantee. So the failure this line prevents is a silent one: an image carrying the thin jar,
# which starts and then dies on the first class it cannot find.
COPY build/libs/updater-*.jar /app/app.jar
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
#   docker compose run --rm updater bootstrap      # migrate, then install. Restarts nothing.
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
# is NORDTAL_UPDATER_SEASON_REPO. The environment wins over the file and is never written back.
#
# There is no setting for WHICH release to follow. It is always the newest published one.
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
