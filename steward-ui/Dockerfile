# The web interface. Runtime-only, like every other image here: Gradle builds the jar, the image
# carries it.
#
#   ./gradlew :steward-ui:build        # the frontend is inside this - see steward-ui/build.gradle.kts
#   docker build -t ghcr.io/nordtal/steward-ui:latest steward-ui
#
# THE FRONTEND IS IN THE JAR, not in this image as files. `:steward-ui:build` runs vite and
# processResources copies build/frontend-dist in under `web/`, which Javalin serves from the
# classpath. There is no Node in this image and no node_modules: a web server that needs a package
# manager at runtime is a web server with a supply chain at runtime.
FROM eclipse-temurin:25-jre

WORKDIR /app

# One jar, picked by glob. .dockerignore keeps the -thin one out of the context so exactly one
# matches - see steward-worker/.dockerignore for what two matches actually do (nothing visible).
COPY build/libs/steward-ui-*.jar /app/app.jar

# curl for the healthcheck, and for nothing else. Measured against eclipse-temurin:25-jre on
# 2026-09-13: the image carries neither curl nor wget, so a healthcheck written with either would
# have been permanently "unhealthy" on a service that was answering perfectly. steward-worker sidesteps
# this with a marker file, which cannot work here: a marker says the process started once, and what
# this container has to prove is that it is still serving.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/config /configs

# NOT ROOT, and this is the container it matters most on: it is the only one on the public
# internet. It has no docker socket (see below), so root inside it was never root on the host - but
# "the process that a stranger reaches first runs as UID 0" is not a sentence to leave standing
# when the fix is three lines.
#
# A fixed UID rather than whatever the next base image hands out: /app/config is a named volume,
# and a volume remembers the numeric owner. An image that renumbered its user on a rebuild would
# come back unable to write its own configuration, which is the failure this kind of change is
# infamous for. 10001 is outside the range Debian hands to system accounts.
#
# /configs is the other services' config volumes and is mounted read-only, so it is deliberately
# NOT chowned: being unable to write there is the arrangement, not an accident.
RUN groupadd --gid 10001 steward \
    && useradd --uid 10001 --gid 10001 --home-dir /app --no-create-home --shell /usr/sbin/nologin steward \
    && chown -R 10001:10001 /app

# THIS CONTAINER HAS NO DOCKER SOCKET AND MUST NEVER BE GIVEN ONE (concept §3).
#
# It is the part of the stack an attacker reaches first - it is the only one on the public internet
# - so it is the one that can do the least. Everything it knows about a container it asks
# steward-worker for over the internal network, with a shared secret; creating containers is
# steward-deployer's and nobody else's. Mounting the socket here would collapse both boundaries at
# once and nothing in the code would notice, which is why it is written on the container rather
# than only in the concept.
#
# CONFIGURATION - one file, one environment namespace:
#
#   config/steward-ui.yml   NORDTAL_STEWARD_UI_*
#
# The secrets are environment variables and are never written back into the file:
# NORDTAL_STEWARD_UI_DISCORD_CLIENT_SECRET and NORDTAL_STEWARD_UI_WORKER_TOKEN.
ENV NORDTAL_STEWARD_UI_CONFIG_DIR=/app/config

# Where the other services' config volumes are mounted, one directory per service. Empty here and
# filled by compose; an unmounted root is a normal state and the page says so rather than failing.
ENV NORDTAL_STEWARD_UI_CONFIGS_ROOT=/configs

EXPOSE 8080

# /api/health answers without a session, which is the whole reason it exists: a healthcheck has no
# cookie. It reports whether steward-worker is reachable as a field and is still `ok` when it is
# not - this container being up and the daemon being down are two different facts, and a
# healthcheck that conflated them would take the interface away exactly when it is needed to say
# what is wrong.
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
    CMD curl -fsS http://127.0.0.1:8080/api/health >/dev/null || exit 1

VOLUME ["/app/config"]

# `steward-ui` as a word on the command line, so that the documented way back is the command that
# actually works:
#
#   docker exec nordtal-s2-steward-ui-1 steward-ui forget-factors <discord-id>
#
# Three lines rather than telling an operator to type `java -jar /app/app.jar`. A procedure that
# needs a path and a flag is one that gets mistyped on the evening it is needed, and this one is
# only ever needed on that evening.
RUN printf '#!/bin/sh\nexec java -jar /app/app.jar "$@"\n' > /usr/local/bin/steward-ui \
    && chmod 0755 /usr/local/bin/steward-ui

USER 10001:10001

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
