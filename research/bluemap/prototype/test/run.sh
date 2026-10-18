#!/usr/bin/env bash
# Builds both variants and screenshots them against a test world.
# Requires Java 25, Node 22, Playwright, a BlueMap checkout at $BLUEMAP and a world folder at $WORLD.
# BLUEMAP_ACCEPT_DOWNLOAD=true accepts the Minecraft EULA, so BlueMap downloads the client jar for its textures.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="${WORK:-$(mktemp -d)}"

# Build the CLI with the unchanged webapp inside.
(cd "$BLUEMAP" && time ./gradlew :cli:release)
cli="$(ls "$BLUEMAP"/build/release/*-cli.jar)"

# The first start writes config/, then BlueMap renders the test world into web/.
mkdir -p "$work/overlay" && cd "$work/overlay"
java -jar "$cli" || true
sed -i "s/^accept-download: false/accept-download: ${BLUEMAP_ACCEPT_DOWNLOAD:-false}/" config/core.conf
rm -f config/maps/*.conf
sed "s#\${world}#$WORLD#" "$here/test/nordtal.conf" >config/maps/nordtal.conf
cat "$here/overlay/webapp.conf" >>config/webapp.conf
# A static file server cannot set Content-Encoding, so the webapp decompresses the tiles itself.
echo "client-decompression: true" >>config/webapp.conf
time java -jar "$cli" -r -g

# Variant without a fork: the overlay files in the webroot.
mkdir -p web/nordtal
cp "$here"/overlay/{nordtal.css,nordtal.js,balloon.png,logo.png} web/nordtal/
cp "$here/overlay/lang-settings.conf" web/lang/settings.conf
java -jar "$cli" -s --markers

# Variant with a fork: the patched webapp over a copy of the same webroot.
cp -r "$work/overlay" "$work/fork"
git -C "$BLUEMAP" apply "$here/fork/0001-nordtal-header.patch"
(cd "$BLUEMAP/common/webapp" && npm ci && time npm run build)
cp -r "$BLUEMAP"/common/webapp/dist/* "$work/fork/web/"

python3 -m http.server -d "$work/overlay/web" 8101 >/dev/null 2>&1 &
python3 -m http.server -d "$work/fork/web" 8102 >/dev/null 2>&1 &
trap 'kill $(jobs -p)' EXIT
sleep 1
cd "$here/screenshots"
node "$here/test/screenshot.mjs" overlay=http://127.0.0.1:8101/ fork=http://127.0.0.1:8102/
