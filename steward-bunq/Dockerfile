# The one image that holds the bank key.
#
#   ./gradlew :steward-bunq:shadowJar
#   docker build -t ghcr.io/nordtal/steward-bunq:latest steward-bunq
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# No volume overrides this jar: a new steward-bunq arrives as a new image.
COPY build/libs/steward-bunq-*.jar /app/app.jar

# bunq binds the context to the device and IP that registered it, so it is regenerated after a move, never copied.
RUN mkdir -p bunq && chmod 700 bunq
ENV NORDTAL_STEWARD_BUNQ_CONTEXT_PATH=/app/bunq/bunq-config.conf

# NORDTAL_STEWARD_BUNQ_TOKEN is required; NORDTAL_STEWARD_BUNQ_API_KEY and _ACCOUNT_ID are set together or not at all.
EXPOSE 8082

VOLUME ["/app/bunq"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
