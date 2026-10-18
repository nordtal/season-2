# The image template of every JVM service of ours. MODULE picks the stage and the jar.
# The build context is the repository root, and /.dockerignore admits only what the Dockerfile copies.
#
#   ./gradlew imageContexts
#   docker build -f deploy/jvm/Dockerfile --build-arg MODULE=steward -t ghcr.io/nordtal/steward:dev .
#
# A new module needs a stage named after it, a line in /.dockerignore, a service in compose.yml and a
# step in release.yml. The Minecraft image does not use this template, because it runs Paper and Velocity.
ARG MODULE

FROM docker:28-cli AS docker-cli

FROM eclipse-temurin:25-jre AS jvm
WORKDIR /app
COPY deploy/jvm/entrypoint.sh deploy/jvm/secrets.sh /app/
ENTRYPOINT ["/app/entrypoint.sh"]

# Every service except steward-agent runs as an account of its own. The `user:` of compose.yml names the same uid.
# steward-agent runs as root. It holds the Docker socket, which is root on the host whatever the uid.
FROM jvm AS discord-bot
RUN groupadd --gid 10001 discord-bot \
    && useradd --uid 10001 --gid 10001 --home-dir /app --no-create-home --shell /usr/sbin/nologin discord-bot
USER discord-bot

# `steward` is also a command: `docker exec nordtal-s2-steward-1 steward forget-factors <discord-id>`.
FROM jvm AS steward
RUN printf '#!/bin/sh\nexec /app/entrypoint.sh "$@"\n' > /usr/local/bin/steward \
    && chmod 0755 /usr/local/bin/steward \
    && groupadd --gid 10002 steward \
    && useradd --uid 10002 --gid 10002 --home-dir /app --no-create-home --shell /usr/sbin/nologin steward
USER steward
EXPOSE 8080

# The docker CLI and the compose plugin are static binaries. The container mounts the Docker socket
# and /app/env, which holds the secrets of the deployment, and only compose reads the latter.
# zstd compresses the backup tars, restic and ssh copy them off the host, and GNU tar is in the base image.
# `steward-agent` is also a command: `docker exec nordtal-s2-steward-agent-1 steward-agent request UPDATE`.
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
# `serve` runs the stack, `up` the setup script, and `request` and `status` the host.
CMD ["serve"]

# bunq binds the context to the device and the IP that registered it, so the image regenerates the context after a move.
FROM jvm AS steward-bunq
RUN groupadd --gid 10003 steward-bunq \
    && useradd --uid 10003 --gid 10003 --home-dir /app --no-create-home --shell /usr/sbin/nologin steward-bunq \
    && mkdir -p bunq && chown steward-bunq:steward-bunq bunq && chmod 700 bunq
USER steward-bunq
ENV NORDTAL_STEWARD_BUNQ_CONTEXT_PATH=/app/bunq/bunq-config.conf
EXPOSE 8082
VOLUME ["/app/bunq"]

# The jar comes last, because it changes with every build. The image is the version, and no service of ours runs a jar from a volume.
FROM ${MODULE} AS image
ARG MODULE
COPY ${MODULE}/build/image/app.jar /app/app.jar
