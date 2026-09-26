# Runtime-only image, exactly like discord-bot/Dockerfile: the jar is built by Gradle first and
# passed in. A self-contained build stage in a multi-module repo would have to copy the whole tree
# and rebuild every module.
#
#   ./gradlew :steward-worker:shadowJar
#   docker build -t ghcr.io/nordtal/steward-worker:latest steward-worker
#
# The version is not named anywhere in this build, and that is deliberate. A --build-arg fed from a
# variable in .env is a version number written down a second time and therefore a version number
# that can go stale against gradle.properties, which is the single source of truth for it.
#
# release.yml pushes this image: ghcr.io/nordtal/steward-worker, tagged with the version and with
# `latest`, as one of five. A deploy pulls and never builds, so an image existing only in a host's
# local daemon fails with `denied` from the registry.
FROM eclipse-temurin:25-jre

WORKDIR /app

# The jar baked in here is the floor, not the version. steward-worker runs whatever
# steward-worker-*.jar is in /volumes/steward-worker - a volume it fills itself, on the run that
# installs everything else - and this copy is what makes the very first run possible, before that
# volume has anything in it.
# docker-entrypoint.sh says the same thing at length and prints which one it picked on every start.
#
# The .dockerignore is load-bearing here. `shadowJar` leaves two jars side by side and this glob
# matches both; .dockerignore keeps the -thin one out of the context so that exactly one does.
# BuildKit does not fail on several sources into one destination - it writes one of them and says
# nothing, and which one it picks is an ordering accident rather than a guarantee. So the failure
# this line prevents is a silent one: an image carrying the thin jar, which starts and then dies on
# the first class it cannot find.
COPY build/libs/steward-worker-*.jar /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

# zstd, because the backup is a tar piped through it. tar is already in this base image; zstd is
# not. The read-back that verifies the archive after writing it is not optional - a tar truncated
# before its end-of-archive blocks decompresses perfectly and only fails when somebody needs it.
#
# A Java zstd binding was the alternative and was not taken: it would put a native library in the
# jar for something the caller can pipe, and the pipeline here is the same one an operator types by
# hand when restoring.
RUN apt-get update \
    && apt-get install --no-install-recommends -y zstd \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p config bunq \
    && chmod 700 bunq \
    && chmod +x /app/docker-entrypoint.sh

# What this container is for, and what it is not:
#
# It resolves the newest version of every jar the network runs, compares that against the jars in
# the mounted volumes, installs what is asked for, owns the database schema, and stops and starts
# the servers around a run, over the Docker socket itself. What it does not do is create a
# container: a new image or a changed compose.yml is steward-deployer's, and this service has no
# compose file and no way to ask for one.
#
# It is a service and it is also a command. `serve` is the long-running mode compose starts:
#
#   docker compose run --rm steward-worker report     # resolve and report. Writes nothing.
#   docker compose run --rm steward-worker migrate    # the schema, and nothing else.
#   docker compose run --rm steward-worker bootstrap  # migrate, then install. Restarts nothing.
#   docker compose up -d steward-worker               # `serve`, which is what the compose file passes.
#
# `serve` is not a scheduler. It applies the schema once at startup and then does nothing at all
# until somebody writes a row into update_request - no timer, no watch, no "check for updates on
# boot". The first rule of this module is that a crash restart does not move a version: the
# container comes back on exactly the jars it was running, and an update happens because a person
# asked for one.
#
# Configuration - one file, one environment namespace:
#
#   config/steward.yml     NORDTAL_STEWARD_*
#
# Unlike every other config in this repository, its defaults are the real values and a fresh file
# is what you want: the repositories and project ids are facts about this project, not about a
# deployment. The variable is the setting's path with '-' becoming '_', so the release to follow
# is NORDTAL_STEWARD_SEASON_REPO. The environment wins over the file and is never written back.
#
# There is no setting for which release to follow. It is always the newest published one.
ENV NORDTAL_STEWARD_CONFIG_DIR=/app/config

# The bank. This container is the only one in the network that holds a bunq credential, and
# /app/bunq is the `bunq-context` volume. The context file is an installed API key bound to a
# device and an IP, so it is:
#
#   - kept in a Docker-managed volume, never on the host filesystem, mode 700;
#   - regenerated after a move, never copied - a context created by another container will be
#     rejected by bunq, and the failure is an HTTP error inside the poll rather than at startup.
#
#   NORDTAL_STEWARD_BUNQ_API_KEY      the bunq API key
#   NORDTAL_STEWARD_BUNQ_ACCOUNT_ID   the bunq monetary account id
#
# Both or neither. Neither means this container starts, says "bunq is off" in one line, and no
# payment is ever noticed - which is a valid season and is exactly why the two variables carry no
# `:?` in compose.yml.
ENV NORDTAL_STEWARD_BUNQ_CONTEXT_PATH=/app/bunq/bunq-config.conf

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

VOLUME ["/app/config", "/app/bunq"]

ENTRYPOINT ["/app/docker-entrypoint.sh"]
