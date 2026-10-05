#!/usr/bin/env bash
# Opens the standby port in nordtal.nft's table while proxy-standby runs and closes it when it stops.
# An update run starts proxy-standby only to hold the network while the proxy is replaced, so the
# port is open for exactly that window. Runs as nordtal-standby-port.service.
set -Euo pipefail

STANDBY_LABEL="eu.nordtal.standby-of=proxy"

log() { printf '%s\n' "$*"; }

# The host port the project $1's Caddy publishes for the standby, PROXY_STANDBY_PORT; empty while
# Caddy is down.
standby_port() {
    local caddy
    caddy="$(docker ps --quiet --filter "label=com.docker.compose.project=$1" \
        --filter label=com.docker.compose.service=caddy | sed -n '1p')"
    [[ -n "$caddy" ]] || return 0
    docker port "$caddy" 25566/tcp | sed -n '1s/.*://p'
}

# Makes the set match the containers as they are now, whatever event led here.
sync() {
    local project port
    project="$(docker ps --filter "label=$STANDBY_LABEL" --format '{{.Label "com.docker.compose.project"}}' \
        | sed -n '1p')"
    if [[ -z "$project" ]]; then
        nft flush set inet nordtal standby && log "closed: proxy-standby is not running"
        return 0
    fi
    port="$(standby_port "$project")"
    if [[ -z "$port" ]]; then
        log "proxy-standby runs, but no Caddy publishes its port, so nothing was opened"
        return 0
    fi
    nft flush set inet nordtal standby \
        && nft add element inet nordtal standby "{ $port }" \
        && log "opened $port: proxy-standby is running"
}

# The events are subscribed to before the first look, so no start or stop falls between the two.
exec {events}< <(docker events --filter "label=$STANDBY_LABEL" --filter event=start --filter event=die \
    --format '{{.Action}}')
sync
while read -r -u "$events" _; do
    sync
done
# The stream ends when the daemon goes away; systemd starts this again.
exit 1
