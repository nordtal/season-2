# The one image that carries compose.yml, staged into build/compose/ by :steward-deployer:stageComposeFile.
#
#   ./gradlew :steward-deployer:build
#   docker build -t ghcr.io/nordtal/steward-deployer:latest steward-deployer
#
# The docker CLI and compose plugin come from the official CLI image, already built against musl.
FROM docker:28-cli AS cli

FROM eclipse-temurin:25-jre-alpine

COPY --from=cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=cli /usr/local/libexec/docker/cli-plugins/docker-compose \
     /usr/local/libexec/docker/cli-plugins/docker-compose

WORKDIR /app

# No volume overrides this jar: a new deployer arrives as a new image, put there by the setup script.
COPY build/libs/steward-deployer-*.jar /app/app.jar
COPY build/compose/compose.yml /app/compose.yml

# Mounted, not baked: /var/run/docker.sock, the whole privilege of this container, and
# /app/env/.env, the deployment's secrets, read only by compose. NORDTAL_STEWARD_DEPLOYER_TOKEN is required.
ENV NORDTAL_STEWARD_COMPOSE_FILE=/app/compose.yml \
    NORDTAL_STEWARD_ENV_FILE=/app/env/.env \
    NORDTAL_STEWARD_PROJECT_DIRECTORY=/app \
    NORDTAL_STEWARD_DEPLOYER_PORT=8081

EXPOSE 8081

# `serve` for the stack, `up` for the setup script.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["serve"]
