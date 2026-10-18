# Caddy with the layer 4 app, because port 25565 carries no HTTP.
#
#   docker build -t ghcr.io/nordtal/caddy:<tag> deploy/caddy
#
# `xcaddy` compiles the Go module `caddy-l4` in. compose.yml holds the configuration.
#
# The tag is a hash of this directory, so a release that leaves it alone keeps the running Caddy and every
# player connected through it. Every input is pinned for the same reason. After a change here, `check`
# fails and names the new tag for compose.yml.
FROM caddy:2.11.7-builder-alpine@sha256:d6161a2c82fd58b3205048958fb6246d0c5d892beee8a47e90944610c4efd100 AS builder

# Leaves cores for the servers on the same host.
ENV GOMAXPROCS=2

RUN xcaddy build --with github.com/mholt/caddy-l4@v0.1.2

FROM caddy:2.11.7-alpine@sha256:d8542f48d34a9cf4e4c11a478865229840e87e4c96ea3f439101f31a5d35f75f

COPY --from=builder /usr/bin/caddy /usr/bin/caddy

# Fails the build when the binary is a stock Caddy without the layer 4 modules.
RUN caddy list-modules | grep -q '^layer4$' \
    && caddy list-modules | grep -q '^layer4.handlers.proxy$'
