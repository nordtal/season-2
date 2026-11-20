# Caddy with the layer 4 app, because port 25565 carries no HTTP.
#
#   docker build -t ghcr.io/nordtal/caddy:<tag> deploy/caddy
#
# `xcaddy` compiles the Go module `caddy-l4` in. compose.yml holds the configuration.
#
# The tag is a hash of this directory, so a release that leaves it alone keeps the running Caddy and every
# player connected through it. Every input is pinned for the same reason. After a change here, `check`
# fails and names the new tag for compose.yml.
FROM caddy:2.11.9-builder-alpine@sha256:646fee578e680989ca5c7fd1ce6f1de574d19fd71ef2221810cc0510a9f40c86 AS builder

# Leaves cores for the servers on the same host.
ENV GOMAXPROCS=2

RUN xcaddy build --with github.com/mholt/caddy-l4@v0.1.3

FROM caddy:2.11.9-alpine@sha256:003a97808e781aa9f7e75cc2f43d34d461099031faf5e20faefb9f61126836f0

COPY --from=builder /usr/bin/caddy /usr/bin/caddy

# Fails the build when the binary is a stock Caddy without the layer 4 modules.
RUN caddy list-modules | grep -q '^layer4$' \
    && caddy list-modules | grep -q '^layer4.handlers.proxy$'

# The website's icon, which Discord's embeds show. A copy of Steward's large icon.
COPY website/ /srv/website/
