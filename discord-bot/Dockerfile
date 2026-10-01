# Runtime-only image around the jar Gradle built; gradle.properties is the only place the version lives.
#
#   ./gradlew :discord-bot:shadowJar
#   docker build -t ghcr.io/nordtal/discord-bot:latest discord-bot
FROM eclipse-temurin:25-jre

WORKDIR /app

# The baked jar is the floor: the entrypoint prefers the one steward put into /app/lib.
# .dockerignore keeps the -thin jar out of the context, so this glob matches exactly one.
COPY build/libs/discord-bot-*.jar /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

RUN mkdir -p config lib \
    && chmod +x /app/docker-entrypoint.sh

# Configuration: config/bot.yml, config/database.yml and config/access.yml. Any setting can be
# overridden as NORDTAL_<PATH>, with '.' and '-' as '_'; the environment wins and is never written back.
# access.yml has no usable ids by default, and the bot refuses to start until they are set.

# Where steward puts this container's jar.
ENV JAR_DIR=/app/lib
ENV JAR_PREFIX=discord-bot

VOLUME ["/app/config", "/app/lib"]

ENTRYPOINT ["/app/docker-entrypoint.sh"]
