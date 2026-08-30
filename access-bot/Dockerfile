# Runtime-only image. The jar is built by Gradle first (`./gradlew :access-bot:shadowJar`)
# and passed in, rather than building inside the image: this is a multi-module repo, so a
# self-contained build stage would have to copy the whole tree and rebuild every module.
#
#   ./gradlew :access-bot:shadowJar
#   docker build -t ghcr.io/nordtal/access-bot:<version> \
#     --build-arg JAR=build/libs/access-bot-<version>.jar access-bot
FROM eclipse-temurin:25-jre

WORKDIR /app

ARG JAR
COPY ${JAR} /app/app.jar

RUN mkdir -p config bunq \
    && chmod 700 bunq

# The bunq API context holds credentials and is kept in a Docker-managed volume, never on
# the host filesystem.
#
# CONFIGURATION (changed with jcore 3.0.0 - the old variable names are no longer read):
#
#   config/bot.yml                 config/database.yml          config/payment-processing.yml
#   NORDTAL_BOT_TOKEN              NORDTAL_DATABASE_JDBC_URL    NORDTAL_PAYMENT_PROCESSING_*
#   NORDTAL_BOT_BUNQ_API_KEY       NORDTAL_DATABASE_USERNAME
#   NORDTAL_BOT_BUNQ_ACCOUNT_ID    NORDTAL_DATABASE_PASSWORD
#
# Every setting in every file can be overridden this way: the variable is the setting's path
# with '.' and '-' turned into '_'. The environment always wins over the file and is never
# written back into it, so a secret handed to this container cannot leak into the config volume.
#
# Renamed from the season 1 / jcore 1.x names:
#   BOT_TOKEN        -> NORDTAL_BOT_TOKEN
#   BUNQ_API_KEY     -> NORDTAL_BOT_BUNQ_API_KEY
#   BUNQ_ACCOUNT_ID  -> NORDTAL_BOT_BUNQ_ACCOUNT_ID
#   BUNQ_CONFIG_PATH -> NORDTAL_BOT_BUNQ_CONTEXT_PATH
#   POSTGRES_URL     -> NORDTAL_DATABASE_JDBC_URL
#   POSTGRES_USER    -> NORDTAL_DATABASE_USERNAME
#   POSTGRES_PASSWORD-> NORDTAL_DATABASE_PASSWORD
#
# An existing config/*.json from jcore 1.x is converted to YAML once, on the first start, and
# kept as *.json.migrated. The MARIADB_* variables the season 1 bot used are long gone.
#
# The bot REFUSES TO START on a config it cannot read, a setting that does not exist, or an
# empty credential. It no longer falls back to defaults.
ENV NORDTAL_BOT_BUNQ_CONTEXT_PATH=/app/bunq/bunq-config.conf

VOLUME ["/app/config", "/app/bunq"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
