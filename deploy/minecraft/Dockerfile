# One image for all four Minecraft services - the Velocity proxy and the three Paper backends.
# The difference between them is entirely environment: SERVER_KIND, SERVER_VERSION, SERVER_BUILD
# and which plugin jars are pulled. Nothing about a specific server is baked in here.
#
#   docker build -t ghcr.io/nordtal/minecraft:<tag> deploy/minecraft
#
# WHY NOT itzg/docker-minecraft-server: it does not cover Velocity (that is a second image with
# its own conventions), it would not have solved the proxy console either - Velocity has no RCON -
# and we want a pinned server build rather than runtime version resolution. See
# docs/operations.md#why-not-itzgdocker-minecraft-server.
FROM eclipse-temurin:25-jre

# tmux is the console (see entrypoint.sh); curl and jq resolve the server jar through PaperMC's
# Fill API, whose download URLs are content-addressed and cannot be constructed by hand.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tmux curl jq ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# The server directory is a named volume: worlds, configs, plugins and the cached server jar.
# Bind mounts are deliberately not used - see docs/operations.md#deployment.
WORKDIR /data
VOLUME ["/data"]

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
# The source directory is `scripts/`, not `bin/`, because .gitignore has a repo-wide `bin/` rule
# for IDE output - a `bin/` here would be silently untracked and this COPY would fail on a fresh
# clone, on the deployment host, at the worst possible moment.
COPY scripts/ /usr/local/bin/
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/console /usr/local/bin/mc

# THE CONSOLE. Arcane's per-container shell is a `docker exec`, which cannot reach PID 1's stdin,
# so a server started as a plain `java -jar` would have a console you can read and not write.
# The server runs inside a tmux session instead:
#
#   console            attach to the real server console, read and write. Detach with Ctrl-b d.
#   mc <command>       send one command without a TTY. Works from any exec shell, scriptable.
#
# Output is mirrored to stdout with `tmux pipe-pane`, so `docker logs` and Arcane's log view are
# unaffected and still show everything.
ENV MC_TMUX_SOCKET=/run/mc/tmux.sock \
    MC_TMUX_SESSION=mc

# Liveness: the session exists and its process is alive. A dead pane means the JVM exited.
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
    CMD tmux -S "$MC_TMUX_SOCKET" has-session -t "$MC_TMUX_SESSION" 2>/dev/null \
        && [ "$(tmux -S "$MC_TMUX_SOCKET" display-message -p -t "$MC_TMUX_SESSION" '#{pane_dead}')" = "0" ]

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
