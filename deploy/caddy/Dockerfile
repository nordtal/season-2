# Caddy with the layer 4 app, because 25565 is not HTTP.
#
#   docker build -t ghcr.io/nordtal/caddy:<tag> deploy/caddy
#
# Why a custom image at all. `caddy:2-alpine` speaks HTTP and TLS and nothing else; a Minecraft
# connection is a raw TCP stream and Simple Voice Chat is raw UDP beside it. `caddy-l4` is Matt
# Holt's own layer 4 app for exactly this, and it is a Go module rather than a plugin that can be
# dropped into a running Caddy - so the module list is decided at build time, which is what `xcaddy`
# in the official builder image is for.
#
# What it is for. The guard in front of 25565 hands the stream to `proxy` and, while that one is not
# answering, to `proxy-standby`, keeping the port reachable through a proxy restart. The
# configuration for that is not here: it is the `caddyfile` config in compose.yml, next to every
# other thing about this deployment.
#
# The version is `2`, like every other image in this deployment, and for the same reason
# compose.yml gives at the top: a pin written down here is a pin that goes stale where nobody looks.
FROM caddy:2-builder-alpine AS builder

# Two, deliberately: this host runs three Minecraft servers and a database beside the build, and a
# Go toolchain that takes every core competes with them for it.
# `/root/.gradle/gradle.properties` caps Gradle for the same reason.
ENV GOMAXPROCS=2

RUN xcaddy build --with github.com/mholt/caddy-l4

FROM caddy:2-alpine

COPY --from=builder /usr/bin/caddy /usr/bin/caddy

# Proof the module is in the binary rather than a promise that it is: a build that silently produced
# a stock Caddy would otherwise only be noticed by a dead port on this host.
RUN caddy list-modules | grep -q '^layer4$' \
    && caddy list-modules | grep -q '^layer4.handlers.proxy$'
