# Runtime-only image, exactly like discord-bot/Dockerfile: the jar is built by Gradle first and
# passed in. A self-contained build stage in a multi-module repo would have to copy the whole tree
# and rebuild every module.
#
#   ./gradlew :updater:shadowJar
#   docker build -t nordtal/updater:<version> \
#     --build-arg JAR=build/libs/updater-<version>.jar updater
#
# NOTHING PUBLISHES THIS IMAGE. .github/workflows/release.yml pushes the bot image to the GHCR and
# no other image (checked 2026-09-01), so the host builds it from this repository - the same
# arrangement deploy/minecraft/ has.
FROM eclipse-temurin:25-jre

WORKDIR /app

ARG JAR
COPY ${JAR} /app/app.jar

RUN mkdir -p config

# WHAT THIS CONTAINER IS FOR, and what it is not:
#
# It resolves the newest version of every jar the network runs, compares that against the jars in
# the mounted volumes, and prints the difference. Today it does nothing else - no downloads, no
# database, and the only file it writes is config/updater.yml on a first run. Steps 2 to 6 of
# docs/updater.md add the schema, the swap and the restart.
#
# IT IS A COMMAND, NOT A SERVICE. Run it on demand:
#
#   docker compose --profile updater run --rm updater
#
# It must never be given a restart policy or a schedule. The first rule of this module is that a
# crash restart at three in the morning does not move a version: the network comes back on exactly
# what it was running, and an update happens because somebody asked for one.
#
# CONFIGURATION - one file, one environment namespace:
#
#   config/updater.yml     NORDTAL_UPDATER_*
#
# Unlike every other config in this repository, its defaults are the real values and a fresh file
# is what you want: the repositories and project ids are facts about this project, not about a
# deployment. The variable is the setting's path with '-' becoming '_', so the release to follow
# is NORDTAL_UPDATER_SEASON_RELEASE. The environment wins over the file and is never written back.
ENV NORDTAL_UPDATER_CONFIG_DIR=/app/config

VOLUME ["/app/config"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
