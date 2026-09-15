# The one image in this repository that carries compose.yml (§8b).
#
# Built like every other: Gradle first, the image second. `:steward-deployer:stageComposeFile`
# copies the repository's compose.yml into build/compose/, which is what makes "which compose file
# is live" a question with a version number for an answer instead of a commit hash.
#
#   ./gradlew :steward-deployer:build
#   docker build -t ghcr.io/nordtal/steward-deployer:latest steward-deployer
#
# The docker CLI and the compose plugin are lifted out of the official CLI image rather than
# installed from a package repository: it is the same binary everyone else runs, it is already
# built against musl, and there is no apt cache to keep out of the layer. Checked on this host
# 2026-09-12: docker:28-cli carries docker 28.5.2 and compose v2.40.3 at these paths.
FROM docker:28-cli AS cli

FROM eclipse-temurin:25-jre-alpine

COPY --from=cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=cli /usr/local/libexec/docker/cli-plugins/docker-compose \
     /usr/local/libexec/docker/cli-plugins/docker-compose

WORKDIR /app

# Unlike the bot and steward-worker, this jar is NOT a floor that a volume overrides: nothing
# updates the deployer from inside the stack, because the thing that would have to do it is the
# deployer. A new version of this service arrives as a new image, and the setup script on the host
# is what puts it there (§9c).
COPY build/libs/steward-deployer-*.jar /app/app.jar
COPY build/compose/compose.yml /app/compose.yml

# THE SOCKET AND THE ENV FILE ARE MOUNTED, NOT BAKED:
#
#   /var/run/docker.sock   the daemon this service drives. It is the whole privilege of this
#                          container and the reason steward-ui has none: §3 keeps the interface and
#                          the socket apart, and this is the service the interface asks.
#   /app/env/.env          the deployment's secrets, read by compose, never by this process. It is
#                          mounted read-only and is not in any image or repository.
#
# NORDTAL_STEWARD_DEPLOYER_TOKEN is required and the service refuses to serve without it.
ENV NORDTAL_STEWARD_COMPOSE_FILE=/app/compose.yml \
    NORDTAL_STEWARD_ENV_FILE=/app/env/.env \
    NORDTAL_STEWARD_PROJECT_DIRECTORY=/app \
    NORDTAL_STEWARD_DEPLOYER_PORT=8081

EXPOSE 8081

# `serve` for the stack, `up` for the setup script - see StewardDeployer.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["serve"]
