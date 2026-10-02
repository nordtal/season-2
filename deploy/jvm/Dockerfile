# The one image template for every JVM service of ours; MODULE picks the stage and the jar.
# The context is the repository root, and /.dockerignore lets in only what is copied below.
#
#   ./gradlew imageContexts
#   docker build -f deploy/jvm/Dockerfile --build-arg MODULE=steward -t ghcr.io/nordtal/steward:dev .
#
# A module adds a stage named after itself, a line in /.dockerignore, a service in compose.yml and a
# step in release.yml. The Minecraft image is not one of these: it runs Paper and Velocity, not our jars.
ARG MODULE

FROM docker:28-cli AS docker-cli

FROM eclipse-temurin:25-jre AS jvm
WORKDIR /app
COPY deploy/jvm/entrypoint.sh /app/entrypoint.sh
ENTRYPOINT ["/app/entrypoint.sh"]

# Configuration in config/: bot.yml, database.yml and access.yml. JAR_DIR is where steward puts its jar.
FROM jvm AS discord-bot
RUN mkdir -p config lib
ENV JAR_DIR=/app/lib
VOLUME ["/app/config", "/app/lib"]

# `steward` is a command too, for `docker exec nordtal-s2-steward-1 steward forget-factors <discord-id>`.
FROM jvm AS steward
RUN mkdir -p config \
    && printf '#!/bin/sh\nexec /app/entrypoint.sh "$@"\n' > /usr/local/bin/steward \
    && chmod 0755 /usr/local/bin/steward
ENV NORDTAL_STEWARD_CONFIG_DIR=/app/config \
    JAR_DIR=/volumes/steward
EXPOSE 8080
VOLUME ["/app/config"]

# The docker CLI and compose plugin are static binaries. Mounted, not baked: the Docker socket, the
# whole privilege of this container, and /app/env, the deployment's secrets, read only by compose.
# zstd compresses the backup tars; GNU tar is already in the base image.
FROM jvm AS steward-agent
RUN apt-get update \
    && apt-get install --no-install-recommends -y zstd \
    && rm -rf /var/lib/apt/lists/*
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-compose \
     /usr/local/libexec/docker/cli-plugins/docker-compose
COPY steward-agent/build/compose/compose.yml /app/compose.yml
ENV NORDTAL_STEWARD_AGENT_COMPOSE_FILE=/app/compose.yml \
    NORDTAL_STEWARD_AGENT_ENV_FILE=/app/env/.env \
    NORDTAL_STEWARD_AGENT_PROJECT_DIRECTORY=/app \
    NORDTAL_STEWARD_AGENT_PORT=8081
EXPOSE 8081
# `serve` for the stack, `up` for the setup script.
CMD ["serve"]

# bunq binds the context to the device and IP that registered it, so it is regenerated after a move.
FROM jvm AS steward-bunq
RUN mkdir -p bunq && chmod 700 bunq
ENV NORDTAL_STEWARD_BUNQ_CONTEXT_PATH=/app/bunq/bunq-config.conf
EXPOSE 8082
VOLUME ["/app/bunq"]

# Last, since it changes on every build. .dockerignore keeps the -thin jar out, so the glob matches one.
FROM ${MODULE} AS image
ARG MODULE
ENV JAR_PREFIX=${MODULE}
COPY ${MODULE}/build/libs/${MODULE}-*.jar /app/app.jar
