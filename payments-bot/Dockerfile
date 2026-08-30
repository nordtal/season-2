# Runtime-only image. The jar is built by Gradle first (`./gradlew :payments-bot:shadowJar`)
# and passed in, rather than building inside the image: this is a multi-module repo, so a
# self-contained build stage would have to copy the whole tree and rebuild every module.
#
#   ./gradlew :payments-bot:shadowJar
#   docker build -t ghcr.io/nordtal/payments-bot:<version> \
#     --build-arg JAR=build/libs/payments-bot-<version>.jar payments-bot
FROM eclipse-temurin:25-jre

WORKDIR /app

ARG JAR
COPY ${JAR} /app/app.jar

RUN mkdir -p config bunq \
    && chmod 700 bunq

# The bunq API context holds credentials and is kept in a Docker-managed volume, never on
# the host filesystem. Environment variables: BOT_TOKEN, BUNQ_API_KEY, BUNQ_ACCOUNT_ID.
#
# The PostgreSQL connection is NOT an environment variable - it is read from
# /app/config/database.json (jdbc_url, username, password), which lives in the config volume.
# The MARIADB_* variables the season 1 bot used are gone along with Hibernate.
ENV BUNQ_CONFIG_PATH=/app/bunq/bunq-config.conf

VOLUME ["/app/config", "/app/bunq"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
