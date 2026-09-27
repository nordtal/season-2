# Runtime-only image around the jar Gradle built; the frontend is inside the jar and served from the classpath.
#
#   ./gradlew :steward-ui:build
#   docker build -t ghcr.io/nordtal/steward-ui:latest steward-ui
FROM eclipse-temurin:25-jre

WORKDIR /app

# .dockerignore keeps the -thin jar out of the context, so this glob matches exactly one.
COPY build/libs/steward-ui-*.jar /app/app.jar

# curl for the healthcheck: the base image carries neither curl nor wget.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/config /configs

# A fixed non-root UID, because the config volume remembers its numeric owner across rebuilds.
# /configs is mounted read-only and deliberately not chowned.
RUN groupadd --gid 10001 steward \
    && useradd --uid 10001 --gid 10001 --home-dir /app --no-create-home --shell /usr/sbin/nologin steward \
    && chown -R 10001:10001 /app

# Never give this container the docker socket: it is the one on the public internet.
# Configuration: config/steward-ui.yml, overridden by NORDTAL_STEWARD_UI_*; secrets stay in the environment.
ENV NORDTAL_STEWARD_UI_CONFIG_DIR=/app/config

# The other services' config volumes, one directory each, mounted by compose; empty is a normal state.
ENV NORDTAL_STEWARD_UI_CONFIGS_ROOT=/configs

EXPOSE 8080

# /api/health needs no session and stays `ok` while steward-worker is unreachable.
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
    CMD curl -fsS http://127.0.0.1:8080/api/health >/dev/null || exit 1

VOLUME ["/app/config"]

# `steward-ui` as a command, for `docker exec nordtal-s2-steward-ui-1 steward-ui forget-factors <discord-id>`.
RUN printf '#!/bin/sh\nexec java -jar /app/app.jar "$@"\n' > /usr/local/bin/steward-ui \
    && chmod 0755 /usr/local/bin/steward-ui

USER 10001:10001

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
