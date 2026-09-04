# One image for all four Minecraft services - the Velocity proxy and the three Paper backends.
# The difference between them is entirely environment: SERVER_KIND and SERVER_VERSION, plus a
# SERVER_BUILD that only seeds an empty cache. Nothing about a specific server is baked in here,
# and no jar is either: the server jar and the plugin jars live in the volume, and the `updater`
# service owns both (plugins since 2026-09-01, the server jar since 2026-09-02).
#
#   docker build -t ghcr.io/nordtal/minecraft:<tag> deploy/minecraft
#
# WHY NOT itzg/docker-minecraft-server: it does not cover Velocity (that is a second image with
# its own conventions), it would not have solved the proxy console either - Velocity has no RCON -
# and we want the build to move through one mechanism, the updater, rather than through an image's
# own version resolution at start. See ../README.md#why-it-looks-like-this.
FROM eclipse-temurin:25-jre

# tmux is the console (see entrypoint.sh); curl and jq resolve the server jar through PaperMC's
# Fill API, whose download URLs are content-addressed and cannot be constructed by hand.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tmux curl jq ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# The server directory is a named volume: worlds, configs, plugins and the cached server jar.
# Bind mounts are deliberately not used - see ../README.md#why-it-looks-like-this.
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
# Output reaches `docker logs` and Arcane's log view by `tail -F`-ing the server's own
# logs/latest.log - NOT by `tmux pipe-pane > /proc/1/fd/1`, which this comment claimed until
# 2026-09-02 and which ../README.md#never-mirror-the-console-with-tmux-pipe-pane forbids outright:
# a pipe-pane writer holding a second handle on the container's stdout pipe wedges the container so
# completely that SIGTERM never reaches PID 1 and `docker rm -f` fails. The entrypoint does use
# pipe-pane, but into a FILE in the volume, which shares none of that - see the long note there.
ENV MC_TMUX_SOCKET=/run/mc/tmux.sock \
    MC_TMUX_SESSION=mc

# COMPOSE OVERRIDES THIS ONE (since 2026-09-04). A `healthcheck:` on a service replaces the image's
# outright, and the x-minecraft anchor in ../../compose.yml now carries a stricter test: this same
# TCP connect AND a readiness marker no more than 90 seconds old, written by the season plugin at
# the end of its onEnable and refreshed every 30 seconds. That closes the gap named at the bottom of
# this comment - a jar that is present and whose onEnable threw. What is left here is what the image
# answers when it is run WITHOUT that compose file, which is a development case and nothing else; it
# is kept rather than deleted so `docker run` of this image is never silently unmonitored.
#
# HEALTHY MEANS "ACCEPTS PLAYERS", NOT "IS ALIVE" (changed 2026-09-02).
#
# It used to ask tmux whether the session existed and its pane was not dead, and that answered yes
# six seconds after start - while Paper needs 30 to 130 to reach `Done`. So all four containers went
# green immediately, stayed green when a plugin had disabled itself, and stayed green when the
# server was not accepting anybody. Since every other service waits on `depends_on:
# service_healthy`, that made the signal worthless as an ordering primitive as well: the whole stack
# started against backends that were not up.
#
# Liveness is not what this needs to express - `restart: unless-stopped` already handles a JVM that
# exited, and a dead pane cannot hold a port open anyway, so this check is strictly stronger than
# the one it replaces.
#
# A TCP CONNECT AND NOT A LOG GREP. Both Paper and Velocity bind the port only at the end of their
# startup, so "the port answers" is exactly the line between starting and serving - and it needs no
# extra package, no Minecraft protocol, and no assumption about a startup string or a log path that
# differs between the two. `/dev/tcp` is a bash builtin, not a file, and bash is in this base image.
#
# WHAT IT STILL CANNOT SEE: a jar that is present and whose onEnable threw. The port opens and the
# server accepts players onto a world with no season on it. That gap is closed on the other side -
# the season plugins take the server down with them rather than disabling themselves alone - because
# no check from out here can tell those two states apart.
HEALTHCHECK --interval=15s --timeout=5s --start-period=180s --retries=4 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-25565}' 2>/dev/null

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
