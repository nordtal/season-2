# Caddy with the layer 4 app, because port 25565 carries no HTTP.
#
#   docker build -t ghcr.io/nordtal/caddy:<tag> deploy/caddy
#
# `xcaddy` compiles the Go module `caddy-l4` in. compose.yml holds the configuration.
FROM caddy:2-builder-alpine AS builder

# Leaves cores for the servers on the same host.
ENV GOMAXPROCS=2

RUN xcaddy build --with github.com/mholt/caddy-l4

FROM caddy:2-alpine

COPY --from=builder /usr/bin/caddy /usr/bin/caddy

# Fails the build when the binary is a stock Caddy without the layer 4 modules.
RUN caddy list-modules | grep -q '^layer4$' \
    && caddy list-modules | grep -q '^layer4.handlers.proxy$'
