# The preview's runtime: the JDK the build runs on, and pg_restore of the server's major version for --dump.
FROM eclipse-temurin:25-jdk-noble@sha256:8599ea2d8f71696f00dcdf46213a4b65fa97ceeaa318671fccba6b0af2727792

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl postgresql-common \
    && /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh -y \
    && apt-get install --yes --no-install-recommends postgresql-client-17 \
    && rm -rf /var/lib/apt/lists/*
