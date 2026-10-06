# One image for all four Minecraft services; SERVER_KIND and SERVER_VERSION tell them apart.
# No jar is baked in: the server and plugin jars live in the volume, and steward owns both.
#
#   docker build -t ghcr.io/nordtal/minecraft:<tag> deploy/minecraft
FROM eclipse-temurin:25-jre

# tmux is the console; curl and jq resolve the server jar through the Fill API.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tmux curl jq ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# The servers run third-party plugins, so never as root. compose.yml's `user:` names the same uid, and
# steward-agent hands every volume to it before a server starts. A login shell, since tmux runs the server
# through the account's shell.
RUN groupadd --gid 10000 minecraft \
    && useradd --uid 10000 --gid 10000 --home-dir /data --no-create-home --shell /bin/bash minecraft \
    && mkdir -p /data /run/mc \
    && chown minecraft:minecraft /data /run/mc

# The world, the server configs and the cached server jar.
WORKDIR /data
VOLUME ["/data"]

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY scripts/ /usr/local/bin/
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/console /usr/local/bin/mc

# The server runs in tmux, since `docker exec` cannot write to PID 1's stdin.
#
#   console            attach to the server console; detach with Ctrl-b d
#   mc <command>       send one command without a TTY
ENV MC_TMUX_SOCKET=/run/mc/tmux.sock \
    MC_TMUX_SESSION=mc

# Healthy means the port accepts connections, which Paper and Velocity open at the end of startup.
# compose.yml replaces this with a stricter check that also reads the season plugin's readiness marker.
HEALTHCHECK --interval=15s --timeout=5s --start-period=180s --retries=4 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-25565}' 2>/dev/null

USER minecraft
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
