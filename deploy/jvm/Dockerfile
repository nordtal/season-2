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
COPY deploy/jvm/entrypoint.sh deploy/jvm/secrets.sh /app/
ENTRYPOINT ["/app/entrypoint.sh"]

# Each service but steward-agent runs as an account of its own, whose uid compose.yml's `user:` names too.
# steward-agent stays root: it holds the Docker socket, which is root on the host whatever the uid.
FROM jvm AS discord-bot
RUN groupadd --gid 10001 discord-bot \
    && useradd --uid 10001 --gid 10001 --home-dir /app --no-create-home --shell /usr/sbin/nologin discord-bot
USER discord-bot

# `steward` is a command too, for `docker exec nordtal-s2-steward-1 steward forget-factors <discord-id>`.
FROM jvm AS steward
RUN printf '#!/bin/sh\nexec /app/entrypoint.sh "$@"\n' > /usr/local/bin/steward \
    && chmod 0755 /usr/local/bin/steward \
    && groupadd --gid 10002 steward \
    && useradd --uid 10002 --gid 10002 --home-dir /app --no-create-home --shell /usr/sbin/nologin steward
USER steward
EXPOSE 8080

# The docker CLI and compose plugin are static binaries. Mounted, not baked: the Docker socket, the
# whole privilege of this container, and /app/env, the deployment's secrets, read only by compose.
# zstd compresses the backup tars, restic and ssh copy them off the host; GNU tar is in the base image.
# `steward-agent` is a command too, for `docker exec nordtal-s2-steward-agent-1 steward-agent request UPDATE`.
FROM jvm AS steward-agent
RUN apt-get update \
    && apt-get install --no-install-recommends -y zstd restic openssh-client \
    && rm -rf /var/lib/apt/lists/* \
    && printf '#!/bin/sh\nexec /app/entrypoint.sh "$@"\n' > /usr/local/bin/steward-agent \
    && chmod 0755 /usr/local/bin/steward-agent
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-compose \
     /usr/local/libexec/docker/cli-plugins/docker-compose
COPY steward-agent/build/image/compose.yml /app/compose.yml
ENV NORDTAL_STEWARD_AGENT_COMPOSE_FILE=/app/compose.yml \
    NORDTAL_STEWARD_AGENT_ENV_FILE=/app/env/.env \
    NORDTAL_STEWARD_AGENT_PROJECT_DIRECTORY=/app \
    NORDTAL_STEWARD_AGENT_PORT=8081
EXPOSE 8081
# `serve` for the stack, `up` for the setup script, `request` and `status` for the host.
CMD ["serve"]

# bunq binds the context to the device and IP that registered it, so it is regenerated after a move.
FROM jvm AS steward-bunq
RUN groupadd --gid 10003 steward-bunq \
    && useradd --uid 10003 --gid 10003 --home-dir /app --no-create-home --shell /usr/sbin/nologin steward-bunq \
    && mkdir -p bunq && chown steward-bunq:steward-bunq bunq && chmod 700 bunq
USER steward-bunq
ENV NORDTAL_STEWARD_BUNQ_CONTEXT_PATH=/app/bunq/bunq-config.conf
EXPOSE 8082
VOLUME ["/app/bunq"]

# Last, since it changes on every build. The image is the version: no service of ours runs a jar from a volume.
FROM ${MODULE} AS image
ARG MODULE
COPY ${MODULE}/build/image/app.jar /app/app.jar
