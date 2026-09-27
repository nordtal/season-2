# Runtime-only image around the jar Gradle built; gradle.properties is the only place the version lives.
#
#   ./gradlew :steward-worker:shadowJar
#   docker build -t ghcr.io/nordtal/steward-worker:latest steward-worker
FROM eclipse-temurin:25-jre

WORKDIR /app

# The baked jar is the floor: the entrypoint prefers the one in /volumes/steward-worker.
# .dockerignore keeps the -thin jar out of the context, so this glob matches exactly one.
COPY build/libs/steward-worker-*.jar /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

# zstd compresses the backup tars; tar is already in the base image.
RUN apt-get update \
    && apt-get install --no-install-recommends -y zstd \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p config bunq \
    && chmod 700 bunq \
    && chmod +x /app/docker-entrypoint.sh

# Configuration: config/steward.yml. Any setting can be overridden as NORDTAL_STEWARD_<PATH>, with '.'
# and '-' as '_'; the environment wins and is never written back. The defaults are the real values.
ENV NORDTAL_STEWARD_CONFIG_DIR=/app/config

# The bunq context is bound to a device and an IP, so it is regenerated after a move, never copied.
# NORDTAL_STEWARD_BUNQ_API_KEY and NORDTAL_STEWARD_BUNQ_ACCOUNT_ID are set together or not at all.
ENV NORDTAL_STEWARD_BUNQ_CONTEXT_PATH=/app/bunq/bunq-config.conf

# Where steward-worker puts its own jar, beside the other volumes it fills.
ENV JAR_DIR=/volumes/steward-worker
ENV JAR_PREFIX=steward-worker

# Written once the schema is current; every other service waits for it.
HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=30 \
    CMD test -f /tmp/steward-worker-ready

VOLUME ["/app/config", "/app/bunq"]

ENTRYPOINT ["/app/docker-entrypoint.sh"]
