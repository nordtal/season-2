# Runtime-only image. The jar is built by Gradle first (`./gradlew :discord-bot:shadowJar`)
# and passed in, rather than building inside the image: this is a multi-module repo, so a
# self-contained build stage would have to copy the whole tree and rebuild every module.
#
#   ./gradlew :discord-bot:shadowJar
#   docker build -t ghcr.io/nordtal/discord-bot:<version> \
#     --build-arg JAR=build/libs/discord-bot-<version>.jar discord-bot
FROM eclipse-temurin:25-jre

WORKDIR /app

# The jar baked in here is the FLOOR, not the version. Since 2026-09-01 the bot runs whatever
# discord-bot-*.jar is in /app/lib, which is a volume the updater fills; this copy is only what
# makes a first deployment possible, before any updater run has happened. docker-entrypoint.sh
# says the same thing at length and prints which one it picked on every start.
ARG JAR
COPY ${JAR} /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

RUN mkdir -p config bunq lib \
    && chmod 700 bunq \
    && chmod +x /app/docker-entrypoint.sh

# The bunq API context holds credentials and is kept in a Docker-managed volume, never on
# the host filesystem.
#
# CONFIGURATION - three files, one environment namespace each:
#
#   config/bot.yml                 config/database.yml          config/access.yml
#   NORDTAL_BOT_TOKEN              NORDTAL_DATABASE_JDBC_URL    NORDTAL_ACCESS_*
#   NORDTAL_BOT_BUNQ_API_KEY       NORDTAL_DATABASE_USERNAME
#   NORDTAL_BOT_BUNQ_ACCOUNT_ID    NORDTAL_DATABASE_PASSWORD
#   NORDTAL_BOT_BUNQ_ENVIRONMENT
#
# Every setting in every file can be overridden this way: the variable is the setting's path
# with '.' and '-' turned into '_'. So the price of the 30-day tier is
# NORDTAL_ACCESS_TIERS_SHORT_PRICE_CENTS. The environment always wins over the file and is
# never written back into it, so a secret handed to this container cannot leak into the
# config volume.
#
# access.yml has NO usable defaults for the guild id, the role ids or the channel ids - they
# are empty and the bot refuses to start until they are filled in. That is deliberate: a
# default id would be a real channel in somebody's guild.
#
# NORDTAL_ACCESS_PAYMENT_WATERMARK matters on a first deploy. Payments created before it are
# ignored forever; without it the first poll books up to 50 historical payments on the bunq
# account, roles, DMs and public thank-yous included.
#
# The bot REFUSES TO START on a config it cannot read, a setting that does not exist, or an
# empty credential. It never falls back to defaults.
ENV NORDTAL_BOT_BUNQ_CONTEXT_PATH=/app/bunq/bunq-config.conf

# Where the updater puts this container's jar, and what it is called. The same volume is mounted
# into the updater at /volumes/discord-bot, which is where it writes.
ENV JAR_DIR=/app/lib
ENV JAR_PREFIX=discord-bot

VOLUME ["/app/config", "/app/bunq", "/app/lib"]

ENTRYPOINT ["/app/docker-entrypoint.sh"]
