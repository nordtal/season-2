#!/usr/bin/env bash
#
# The seeding half of entrypoint.sh, exercised against fixture directories.
#
# WHY THIS EXISTS AT ALL. `seed_level_settings` decides whether a world folder is deleted, and it
# decides it inside a container that starts by itself. There is no second chance to notice it
# decided wrong: the folder is gone, and on the SMP that folder is the season. Everything else in
# this deployment is verified by running it and looking - this is the one piece where looking
# afterwards is too late, so it is the one piece with a test.
#
# It runs on `./gradlew check` (wired in the root build.gradle.kts) and needs nothing but bash: no
# Docker, no network, no server jar. entrypoint.sh is SOURCED, which works because it carries a
# guard at the line where its definitions end and the container's own run begins - see the comment
# there. `$0` is deliberately not the script's path below, which is what makes that guard fire.
#
# WHAT IT CANNOT SAY ANYTHING ABOUT: whether Paper then generates the world these files describe.
# That needs a running container and is a checklist item, not a test.
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENTRYPOINT="$HERE/entrypoint.sh"
[[ -f "$ENTRYPOINT" ]] || { echo "entrypoint.sh not found beside this script" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failed=0
current_case=""

case_begin() { current_case="$1"; }
ok()   { printf '  ok    %s\n' "$1"; }
bad()  { printf '  FAIL  %s: %s\n' "$current_case" "$1" >&2; failed=$(( failed + 1 )); }

# A fresh volume directory for one case. Returns its path on stdout.
volume() {
    local dir
    dir="$WORK/$1"
    mkdir -p "$dir"
    printf '%s' "$dir"
}

# Runs seed_level_settings against a volume, in a subshell, and leaves the exit status in $status
# and everything it printed in $output.
#
# The `bash -c ... seeding-test` at the end is not decoration: it sets $0 to a name that is not the
# entrypoint's path, which is exactly what the source guard in entrypoint.sh compares BASH_SOURCE
# against. Pass the path there instead and the guard would conclude it was executed and would run
# the whole container.
seed() {
    local data="$1" level="$2" seed_value="${3:-}"
    set +e
    output=$(DATA="$data" LEVEL_NAME="$level" LEVEL_SEED="$seed_value" \
        bash -c 'source "$1"; seed_level_settings' seeding-test "$ENTRYPOINT" 2>&1)
    status=$?
    set -e
}

# --- assertions ---------------------------------------------------------------------------------

expect_status() {
    local want="$1"
    if [[ "$status" != "$want" ]]; then
        bad "expected exit status ${want}, got ${status}. Output was:
${output}"
    fi
}

expect_property() {
    local file="$1" key="$2" want="$3" have
    have=$(sed -n "s/^${key}=//p" "$file" 2>/dev/null | head -n1)
    [[ "$have" == "$want" ]] || bad "expected ${key}=${want} in ${file##*/}, found '${have:-nothing}'"
}

expect_no_property() {
    local file="$1" key="$2"
    if [[ -f "$file" ]] && grep -qE "^${key}=" "$file"; then
        bad "${key} should not have been written, but ${file##*/} carries $(grep -E "^${key}=" "$file")"
    fi
}

expect_gone()    { [[ ! -e "$1" ]] || bad "expected ${1} to be gone, it is still there"; }
expect_present() { [[   -e "$1" ]] || bad "expected ${1} to still be there, it is gone"; }

expect_output() {
    [[ "$output" == *"$1"* ]] || bad "expected the output to mention '${1}'. Output was:
${output}"
}

# --- fixtures -----------------------------------------------------------------------------------

# A volume as it comes out of the releases before v0.2.3: Paper generated its default world and
# wrote level-name=world, while LEVEL_NAME already said something else. `datapacks` is in the
# named world because the entrypoint of that era fetched them there - which is the folder that made
# the old `-d` seed test lie.
legacy_volume() {
    local dir target
    dir=$(volume "$1")
    target="$2"
    mkdir -p "$dir/world" "$dir/world_nether" "$dir/world_the_end" "$dir/${target}/datapacks"
    : > "$dir/world/level.dat"
    printf 'level-name=world\nmax-players=20\n' > "$dir/server.properties"
    printf '%s' "$dir"
}

# --- the cases ----------------------------------------------------------------------------------

echo "entrypoint.sh: seeding"

# ------------------------------------------------------------------------------------------------
case_begin "a fresh volume is seeded with both values"
data=$(volume fresh)
seed "$data" nordtal 1837371427
expect_status 0
expect_property "$data/server.properties" level-name nordtal
expect_property "$data/server.properties" level-seed 1837371427
ok "fresh volume"

# ------------------------------------------------------------------------------------------------
# The failure this whole change is about: smp and hunger-games refusing to start on every volume
# the previous release had already run against.
case_begin "a pre-v0.2.3 volume with nobody in it is adopted, and the default world removed"
data=$(legacy_volume legacy-clean nordtal)
seed "$data" nordtal 1837371427
expect_status 0
expect_property "$data/server.properties" level-name nordtal
expect_gone "$data/world"
expect_gone "$data/world_nether"
expect_gone "$data/world_the_end"
expect_present "$data/nordtal/datapacks"
# And the seed reaches the file, which is the half the old `-d` test got wrong: /data/nordtal
# exists here, carrying nothing but the datapacks the previous release fetched into it.
expect_property "$data/server.properties" level-seed 1837371427
ok "adopted, world removed, seed written"

# ------------------------------------------------------------------------------------------------
case_begin "a default world somebody has played in is refused, not deleted"
data=$(legacy_volume legacy-played nordtal)
mkdir -p "$data/world/playerdata"
: > "$data/world/playerdata/069a79f4-44e9-4726-a5be-fca90e38aaf5.dat"
seed "$data" nordtal 1837371427
expect_status 1
expect_present "$data/world"
expect_present "$data/world/playerdata/069a79f4-44e9-4726-a5be-fca90e38aaf5.dat"
expect_property "$data/server.properties" level-name world
expect_output "somebody has played in it"
expect_output "docker volume rm"
ok "refused, nothing deleted"

# ------------------------------------------------------------------------------------------------
case_begin "an empty playerdata folder is not somebody having played"
data=$(legacy_volume legacy-empty-playerdata nordtal)
mkdir -p "$data/world/playerdata"
seed "$data" nordtal 1837371427
expect_status 0
expect_gone "$data/world"
expect_property "$data/server.properties" level-name nordtal
ok "empty playerdata adopted"

# ------------------------------------------------------------------------------------------------
# The property the adoption must never lose: a name a person chose is never overruled.
case_begin "a disagreement between two chosen names stays fatal"
data=$(volume renamed)
mkdir -p "$data/nordtal"
: > "$data/nordtal/level.dat"
printf 'level-name=nordtal\n' > "$data/server.properties"
seed "$data" nordtal_alt 1837371427
expect_status 1
expect_present "$data/nordtal"
expect_property "$data/server.properties" level-name nordtal
expect_output "Refusing to start"
ok "chosen name refused"

# ------------------------------------------------------------------------------------------------
# limbo's shape: no LEVEL_NAME at all, so it resolves to `world` and matches what Paper wrote.
case_begin "a service with no LEVEL_NAME never triggers any of this"
data=$(volume limbo)
mkdir -p "$data/world"
: > "$data/world/level.dat"
printf 'level-name=world\n' > "$data/server.properties"
seed "$data" world
expect_status 0
expect_present "$data/world"
expect_property "$data/server.properties" level-name world
ok "limbo untouched"

# ------------------------------------------------------------------------------------------------
# The regression the `-d` test caused, isolated: a world DIRECTORY that holds no world.
case_begin "a level-name folder holding only datapacks is not a world, so the seed is written"
data=$(volume datapacks-only)
mkdir -p "$data/nordtal/datapacks"
: > "$data/nordtal/datapacks/Terralith_26.2_v2.6.4.zip"
printf 'level-name=nordtal\n' > "$data/server.properties"
seed "$data" nordtal 1837371427
expect_status 0
expect_property "$data/server.properties" level-seed 1837371427
ok "seed written past a datapacks folder"

# ------------------------------------------------------------------------------------------------
case_begin "a generated world keeps its own seed, and the disagreement is a warning"
data=$(volume generated)
mkdir -p "$data/nordtal"
: > "$data/nordtal/level.dat"
printf 'level-name=nordtal\nlevel-seed=99\n' > "$data/server.properties"
seed "$data" nordtal 1837371427
expect_status 0
expect_property "$data/server.properties" level-seed 99
expect_output "Terrain is never re-rolled"
ok "existing world's seed left alone"

# ------------------------------------------------------------------------------------------------
case_begin "no LEVEL_SEED writes no level-seed"
data=$(volume seedless)
seed "$data" hunger_games
expect_status 0
expect_property "$data/server.properties" level-name hunger_games
expect_no_property "$data/server.properties" level-seed
ok "seedless volume"

# ------------------------------------------------------------------------------------------------
case_begin "seeding twice changes nothing the second time"
data=$(volume idempotent)
seed "$data" nordtal 1837371427
before=$(cat "$data/server.properties")
seed "$data" nordtal 1837371427
expect_status 0
[[ "$(cat "$data/server.properties")" == "$before" ]] \
    || bad "a second run rewrote server.properties"
ok "idempotent"

# ------------------------------------------------------------------------------------------------

if (( failed > 0 )); then
    printf '\n%d case(s) failed\n' "$failed" >&2
    exit 1
fi
echo "all cases passed"
