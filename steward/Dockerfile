# Runtime-only image around the jar Gradle built; gradle.properties is the only place the version lives.
#
#   ./gradlew :steward:shadowJar
#   docker build -t ghcr.io/nordtal/steward:latest steward
FROM eclipse-temurin:25-jre

WORKDIR /app

# The baked jar is the floor: the entrypoint prefers the one in /volumes/steward.
# .dockerignore keeps the -thin jar out of the context, so this glob matches exactly one.
COPY build/libs/steward-*.jar /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

# zstd compresses the backup tars; tar is already in the base image.
RUN apt-get update \
    && apt-get install --no-install-recommends -y zstd \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p config \
    && chmod +x /app/docker-entrypoint.sh

# Configuration: config/steward.yml. Any setting can be overridden as NORDTAL_STEWARD_<PATH>, with '.'
# and '-' as '_'; the environment wins and is never written back. The defaults are the real values.
ENV NORDTAL_STEWARD_CONFIG_DIR=/app/config

# Where steward puts its own jar, beside the other volumes it fills.
ENV JAR_DIR=/volumes/steward
ENV JAR_PREFIX=steward

# The interface; compose publishes it on loopback and Caddy carries it outside.
EXPOSE 8080

# Written once the schema is current; every other service waits for it.
HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=30 \
    CMD test -f /tmp/steward-ready

VOLUME ["/app/config"]

# `steward` as a command, for `docker exec nordtal-s2-steward-1 steward forget-factors <discord-id>`.
RUN printf '#!/bin/sh\nexec /app/docker-entrypoint.sh "$@"\n' > /usr/local/bin/steward \
    && chmod 0755 /usr/local/bin/steward

ENTRYPOINT ["/app/docker-entrypoint.sh"]
