# Runtime-only image, exactly like discord-bot/Dockerfile: the jar is built by Gradle first and
# passed in. A self-contained build stage in a multi-module repo would have to copy the whole tree
# and rebuild every module.
#
#   ./gradlew :steward-worker:shadowJar
#   docker build -t ghcr.io/nordtal/steward-worker:latest steward-worker
#
# THE VERSION IS NOT NAMED ANYWHERE IN THIS BUILD, and that is deliberate. It used to arrive as a
# --build-arg fed from a variable in .env, which is a version number written down a second time and
# therefore a version number that goes stale: .env.example still said 0.2.3 when gradle.properties
# said 0.8.1, and the only thing that produced was a build looking for a jar nobody had built.
#
# release.yml PUSHES THIS IMAGE, and has since 2026-09-02: ghcr.io/nordtal/steward-worker, tagged
# with the version and with `latest`, as one of five. The comment here said the opposite until
# 2026-09-09 and was simply out of date - the reason it changed is that A DEPLOY PULLS AND NEVER
# BUILDS, so an image existing only in a host's local daemon fails with `denied` from the registry.
# That was true of the management panel that used to deploy this stack and it is true of
# steward-deployer, which replaced it on 2026-09-13.
FROM eclipse-temurin:25-jre

WORKDIR /app

# The jar baked in here is the FLOOR, not the version. steward-worker runs whatever
# steward-worker-*.jar is in /volumes/steward-worker - a volume it fills itself, on the run that
# installs everything else - and this copy is what makes the very first run possible, before that
# volume has anything in it.
# docker-entrypoint.sh says the same thing at length and prints which one it picked on every start.
#
# THE .dockerignore IS LOAD-BEARING HERE. `shadowJar` leaves two jars side by side and this glob
# matches both; .dockerignore keeps the -thin one out of the context so that exactly one does.
# Measured on 2026-09-09 with Docker 29.7.2: with both in the context BuildKit does NOT fail on
# several sources into one destination - it writes one of them and says nothing. It happened to be
# the right one, because `-thin.jar` sorts before `.jar`, which is an ordering accident and not a
# guarantee. So the failure this line prevents is a silent one: an image carrying the thin jar,
# which starts and then dies on the first class it cannot find.
COPY build/libs/steward-worker-*.jar /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

# zstd, because the backup is a tar piped through it (§9a). tar is already in this base image;
# zstd is not - verified against eclipse-temurin:25-jre on 2026-09-13. Measured on the real SMP
# world the same night: 657 MiB in, 512.9 MiB out, 4.0 s including the read-back that verifies the
# archive. That read-back is a third of the time and is not optional - a tar truncated before its
# end-of-archive blocks decompresses perfectly and only fails when somebody needs it.
#
# A Java zstd binding was the alternative and was not taken: it would put a native library in the
# jar for something the caller can pipe, and the pipeline here is the same one an operator types by
# hand when restoring.
RUN apt-get update \
    && apt-get install --no-install-recommends -y zstd \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p config \
    && chmod +x /app/docker-entrypoint.sh

# WHAT THIS CONTAINER IS FOR, and what it is not:
#
# It resolves the newest version of every jar the network runs, compares that against the jars in
# the mounted volumes, installs what is asked for, owns the database schema, and stops and starts
# the servers around a run - over the Docker socket, itself, since 2026-09-12. What it does NOT do
# is create a container: a new image or a changed compose.yml is steward-deployer's, and this
# service has no compose file and no way to ask for one.
#
# IT IS A SERVICE AND IT IS ALSO A COMMAND. `serve` is the long-running mode compose starts:
#
#   docker compose run --rm steward-worker report     # resolve and report. Writes nothing.
#   docker compose run --rm steward-worker migrate    # the schema, and nothing else.
#   docker compose run --rm steward-worker bootstrap  # migrate, then install. Restarts nothing.
#   docker compose up -d steward-worker               # `serve`, which is what the compose file passes.
#
# `serve` IS NOT A SCHEDULER. It applies the schema once at startup and then does nothing at all
# until somebody writes a row into update_request - no timer, no watch, no "check for updates on
# boot". The first rule of this module is that a crash restart at three in the morning does not
# move a version: the container comes back on exactly the jars it was running, and an update
# happens because a person asked for one.
#
# CONFIGURATION - one file, one environment namespace:
#
#   config/steward.yml     NORDTAL_STEWARD_*
#
# Unlike every other config in this repository, its defaults are the real values and a fresh file
# is what you want: the repositories and project ids are facts about this project, not about a
# deployment. The variable is the setting's path with '-' becoming '_', so the release to follow
# is NORDTAL_STEWARD_SEASON_REPO. The environment wins over the file and is never written back.
#
# There is no setting for WHICH release to follow. It is always the newest published one.
ENV NORDTAL_STEWARD_CONFIG_DIR=/app/config

# The worker's own jar lives where it puts it: under volumes-root, beside the four Minecraft
# volumes and the bot's, so that installing it needs no special case anywhere in the code.
ENV JAR_DIR=/volumes/steward-worker
ENV JAR_PREFIX=steward-worker

# The healthcheck compose uses is `test -f /tmp/steward-worker-ready`, written once the schema is current
# and the request loop is about to start. Every other service in the stack waits for it: this
# container is the only one that migrates, and a plugin coming up against a schema older than
# itself is the failure the whole arrangement exists to prevent.
HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=30 \
    CMD test -f /tmp/steward-worker-ready

VOLUME ["/app/config"]

ENTRYPOINT ["/app/docker-entrypoint.sh"]
