# Caddy with the layer 4 app, because 25565 is not HTTP.
#
#   docker build -t ghcr.io/nordtal/caddy:<tag> deploy/caddy
#
# `caddy-l4` is a Go module, so it is compiled in with `xcaddy`. The configuration is in compose.yml.
FROM caddy:2-builder-alpine AS builder

# Leaves cores for the servers running on the same host.
ENV GOMAXPROCS=2

RUN xcaddy build --with github.com/mholt/caddy-l4

FROM caddy:2-alpine

COPY --from=builder /usr/bin/caddy /usr/bin/caddy

# Fails the build if the binary is a stock Caddy without the layer 4 modules.
RUN caddy list-modules | grep -q '^layer4$' \
    && caddy list-modules | grep -q '^layer4.handlers.proxy$'
