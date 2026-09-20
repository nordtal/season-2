# Caddy with the layer 4 app, because 25565 is not HTTP (season-2-ops/162).
#
#   docker build -t ghcr.io/nordtal/caddy:<tag> deploy/caddy
#
# WHY A CUSTOM IMAGE AT ALL. `caddy:2-alpine` speaks HTTP and TLS and nothing else; a Minecraft
# connection is a raw TCP stream and Simple Voice Chat is raw UDP beside it. `caddy-l4` is Matt
# Holt's own layer 4 app for exactly this, and it is a Go module rather than a plugin that can be
# dropped into a running Caddy - so the module list is decided at build time, which is what `xcaddy`
# in the official builder image is for. This is the whole of the cost the ticket named.
#
# WHAT IT IS FOR. The guard in front of 25565 hands the stream to `proxy` and, while that one is not
# answering, to `proxy-standby` - the 26 seconds of a proxy restart measured in run 73, during which
# the standby is up and healthy and nobody could reach it. The configuration for that is not here:
# it is the `caddyfile` config in compose.yml, next to every other thing about this deployment.
#
# THE VERSION IS `2`, LIKE EVERY OTHER IMAGE IN THIS DEPLOYMENT, and for the same reason
# compose.yml gives at the top: a pin written down here is a pin that goes stale where nobody looks.
FROM caddy:2-builder-alpine AS builder

# Two, deliberately: this host runs three Minecraft servers and a database beside the build, and a
# Go toolchain that takes every core has already cost this project a running server
# (season-2-ops/114). `/root/.gradle/gradle.properties` caps Gradle for the same reason.
ENV GOMAXPROCS=2

RUN xcaddy build --with github.com/mholt/caddy-l4

FROM caddy:2-alpine

COPY --from=builder /usr/bin/caddy /usr/bin/caddy

# Proof the module is in the binary rather than a promise that it is: a build that silently produced
# a stock Caddy would otherwise only be noticed by a dead port on this host.
RUN caddy list-modules | grep -q '^layer4$' \
    && caddy list-modules | grep -q '^layer4.handlers.proxy$'
