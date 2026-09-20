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
case_failed=0

case_begin() { current_case="$1"; case_failed=0; }

# `ok` stays silent when the case it belongs to has already failed. Every case here calls its
# assertions and then `ok` unconditionally, so without this a failing case printed its FAIL and an
# `ok` directly underneath it. The count and the exit status were right all along; the output read
# as if nothing had happened, which is the half somebody actually looks at.
ok()   { (( case_failed )) || printf '  ok    %s\n' "$1"; }
bad()  { printf '  FAIL  %s: %s\n' "$current_case" "$1" >&2; failed=$(( failed + 1 )); case_failed=1; }

# A fresh volume directory for one case. Returns its path on stdout.
volume() {
    local dir
    dir="$WORK/$1"
    mkdir -p "$dir"
    printf '%s' "$dir"
}

# Runs newest_server_jar against a cache directory and leaves its answer in $output.
#
# Same `bash -c ... seeding-test` trick as seed() below, and for the same reason: $0 has to be a
# name that is not the entrypoint's path, or the source guard concludes it was executed and runs
# the whole container.
pick() {
    local cache="$1" kind="$2"
    set +e
    output=$(bash -c 'source "$1"; newest_server_jar "$2" "$3"' seeding-test "$ENTRYPOINT" "$cache" "$kind" 2>&1)
    status=$?
    set -e
}

# Runs remove_superseded_jars against a cache directory, and leaves what it printed in $output.
#
# Same `bash -c ... seeding-test` trick as pick() and seed(), and for the same reason.
sweep() {
    local cache="$1" kind="$2" keep="$3"
    set +e
    output=$(bash -c 'source "$1"; remove_superseded_jars "$2" "$3" "$4"' \
        seeding-test "$ENTRYPOINT" "$cache" "$kind" "$keep" 2>&1)
    status=$?
    set -e
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

# Runs seed_velocity_config against a volume, and leaves its exit status in $status and everything
# it printed in $output. Same `bash -c ... seeding-test` trick as seed() above, same reason.
seed_velocity() {
    local data="$1" servers="$2" try="${3:-}"
    set +e
    output=$(DATA="$data" VELOCITY_SERVERS="$servers" VELOCITY_TRY="$try" \
        bash -c 'source "$1"; seed_velocity_config' seeding-test "$ENTRYPOINT" 2>&1)
    status=$?
    set -e
}

# season-2-ops/160. The other half of the proxy's config, and the half that runs on a volume this
# script did NOT write - so it is given a velocity.toml that already exists and asked what it does
# to it.
ensure_transfers() {
    local data="$1"
    set +e
    output=$(DATA="$data" \
        bash -c 'source "$1"; ensure_velocity_transfers' transfers-test "$ENTRYPOINT" 2>&1)
    status=$?
    set -e
}

# A proxy volume as it really was on the dev host on 2026-09-19: a velocity.toml written before the
# seeding knew about accepts-transfers, normalised once by Velocity itself, and therefore carrying
# no [advanced] table at all.
old_proxy_volume() {
    local dir
    dir=$(volume "$1")
    {
        printf 'config-version = "2.9"\n'
        printf 'bind = "0.0.0.0:25565"\n'
        printf 'player-info-forwarding-mode = "modern"\n\n'
        printf '[servers]\n'
        printf 'limbo = "limbo:25565"\n'
        printf 'try = ["limbo"]\n\n'
        printf '[forced-hosts]\n'
    } > "$dir/velocity.toml"
    printf '%s' "$dir"
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
    # `sed -n 1p` and not `head -n1`: head stops reading, and a reader that stops reading turns a
    # successful pipeline into exit 141 under `pipefail`. season-2-ops/27 is that bug, found in CI.
    have=$(sed -n "s/^${key}=//p" "$file" 2>/dev/null | sed -n '1p')
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

# The line that carries a TOML key, or nothing. Written as "the key under that table" rather than
# "the key anywhere in the file", because a root-level accepts-transfers is exactly the mistake
# this exists to catch: Velocity reads it under [advanced] and nowhere else.
expect_toml_under_table() {
    local file="$1" table="$2" key="$3" want="$4" have
    have=$(awk -v table="$table" -v key="$key" '
        /^\[/ { current = $0; next }
        current == table && $1 == key { print; exit }
    ' "$file" 2>/dev/null)
    [[ "$have" == "$key = $want" ]] \
        || bad "expected '${key} = ${want}' under ${table} in ${file##*/}, found '${have:-nothing}'"
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

echo "entrypoint.sh: the cached server jar"

# A cache directory holding the named files. Returns its path on stdout.
cache() {
    local dir name
    dir="$WORK/cache-$1"
    mkdir -p "$dir"
    shift
    for name in "$@"; do
        : > "$dir/$name"
    done
    printf '%s' "$dir"
}

expect_pick() {
    [[ "$output" == "$1" ]] || bad "expected '${1:-nothing}', got '${output:-nothing}'"
}

# Every name still in the cache directory, sorted, against the names given here.
expect_cache() {
    local dir="$1"; shift
    local want have
    want=$(printf '%s\n' "$@")
    have=$(cd "$dir" && ls -1)
    [[ "$have" == "$want" ]] || bad "expected the cache to hold:
${want}
but it holds:
${have:-nothing}"
}

# ------------------------------------------------------------------------------------------------
case_begin "the highest build of one version wins"
dir=$(cache builds paper-26.2-119.jar paper-26.2-121.jar paper-26.2-9.jar)
pick "$dir" paper
expect_status 0
# 9 beats 121 as text and loses as a number, which is the whole reason this is not a `sort`.
expect_pick paper-26.2-121.jar
ok "highest build"

# ------------------------------------------------------------------------------------------------
# WHY THIS CASE EXISTS. Until 2026-09-09 the glob carried the version - velocity-4.1.1-*.jar - so a
# steward-worker run that moved the proxy to 4.2.0 left a cache this script read as EMPTY, and it
# fetched 4.1.1 back. Every update to the proxy would have been undone by the restart meant to
# apply it.
case_begin "the highest version wins, not the version somebody asked for"
dir=$(cache versions velocity-4.1.1-24.jar velocity-4.2.0-15.jar)
pick "$dir" velocity
expect_status 0
expect_pick velocity-4.2.0-15.jar
ok "highest version"

# ------------------------------------------------------------------------------------------------
case_begin "4.10.0 beats 4.9.0, which sorting text gets wrong"
dir=$(cache numeric velocity-4.9.0-3.jar velocity-4.10.0-1.jar)
pick "$dir" velocity
expect_status 0
expect_pick velocity-4.10.0-1.jar
ok "numeric version comparison"

# ------------------------------------------------------------------------------------------------
case_begin "an empty cache answers with nothing, and does not fail"
dir=$(cache empty)
pick "$dir" paper
expect_status 0
expect_pick ""
ok "empty cache"

# ------------------------------------------------------------------------------------------------
# The answers here are all "skip it", never "guess": a jar this function cannot read the version and
# build out of is one the entrypoint would run without knowing what it is.
case_begin "a file that does not fit the shape is skipped, not guessed at"
dir=$(cache junk \
    paper.jar \
    paper-26.2.jar \
    paper-26.2-rc-2-118.jar \
    paper-26.2-latest.jar \
    paper-26.2-121.jar)
pick "$dir" paper
expect_status 0
expect_pick paper-26.2-121.jar
ok "unmatched files skipped"

# ------------------------------------------------------------------------------------------------
case_begin "only jars of this kind are considered"
dir=$(cache kinds paper-26.2-121.jar velocity-4.1.1-24.jar)
pick "$dir" velocity
expect_status 0
expect_pick velocity-4.1.1-24.jar
ok "kind respected"

# ------------------------------------------------------------------------------------------------
case_begin "a cache holding nothing readable answers with nothing"
dir=$(cache unreadable paper-26.2.jar notes.txt)
pick "$dir" paper
expect_status 0
expect_pick ""
ok "nothing readable"

# ------------------------------------------------------------------------------------------------
# WHY THESE CASES EXIST. The sweep deletes files, and what keeps it from deleting the jar the server
# is about to run is one string comparison. Until 2026-09-16 it was a loop at the call site, which
# no test could reach; the live proof that it works at all was run by hand that day (two paper jars
# in the hunger-games cache, restart, `removed superseded paper-26.2-120.jar` in the log and one jar
# left). These are the parts of that which should not need a container again.
case_begin "the jar this start chose survives, and every other build goes"
dir=$(cache sweep-builds paper-26.2-119.jar paper-26.2-121.jar paper-26.2-124.jar)
sweep "$dir" paper "$dir/paper-26.2-124.jar"
expect_status 0
expect_cache "$dir" paper-26.2-124.jar
ok "older builds removed, the chosen one kept"

# ------------------------------------------------------------------------------------------------
# The proxy's version bump is the case that made this matter: steward-worker supersedes by filename
# prefix, so velocity-4.1.1-24 -> velocity-4.2.0-31 leaves both jars lying there.
case_begin "a superseded version goes too, not only a superseded build"
dir=$(cache sweep-versions velocity-4.1.1-24.jar velocity-4.2.0-31.jar)
sweep "$dir" velocity "$dir/velocity-4.2.0-31.jar"
expect_status 0
expect_cache "$dir" velocity-4.2.0-31.jar
ok "superseded version removed"

# ------------------------------------------------------------------------------------------------
# A jar newest_server_jar refused to read is exactly the kind that would sit in the cache forever,
# so the sweep is deliberately less careful than the picker: it takes the whole `<kind>-*.jar` glob.
case_begin "a jar of this kind that the picker could not read is removed as well"
dir=$(cache sweep-junk paper-26.2-latest.jar paper-26.2-124.jar)
sweep "$dir" paper "$dir/paper-26.2-124.jar"
expect_status 0
expect_cache "$dir" paper-26.2-124.jar
ok "unreadable jar of this kind removed"

# ------------------------------------------------------------------------------------------------
# And the other side of that: the proxy and a Paper server never share a cache today, but the glob
# is the only thing stopping this from being a bad day if they ever do.
case_begin "a jar of another kind is not touched"
dir=$(cache sweep-kinds paper-26.2-124.jar velocity-4.2.0-31.jar notes.txt)
sweep "$dir" paper "$dir/paper-26.2-124.jar"
expect_status 0
expect_cache "$dir" notes.txt paper-26.2-124.jar velocity-4.2.0-31.jar
ok "another kind left alone"

# ------------------------------------------------------------------------------------------------
# The bootstrap branch: one jar was just downloaded and there is nothing to sweep. It must not
# remove the only jar there is, and it must not fail over an empty glob either.
case_begin "a cache holding only the chosen jar is left exactly as it is"
dir=$(cache sweep-single paper-26.2-124.jar)
sweep "$dir" paper "$dir/paper-26.2-124.jar"
expect_status 0
expect_cache "$dir" paper-26.2-124.jar
ok "nothing to sweep"

# ------------------------------------------------------------------------------------------------
# season-2-ops/119. Two things a live proxy swap cannot work without, and both of them are invisible
# when they are wrong: a proxy that does not accept transfers refuses every player the other one
# sends, and a server name that is not in velocity.toml cannot be connected to at all.
case_begin "a seeded velocity.toml accepts transfers, under [advanced]"
dir=$(volume velocity-transfers)
seed_velocity "$dir" "limbo=limbo:25565 limbo-standby=limbo-standby:25565"
expect_status 0
expect_toml_under_table "$dir/velocity.toml" "[advanced]" accepts-transfers true
ok "accepts-transfers = true under [advanced]"

# THIS CASE DOES NOT PROVE limbo-standby IS IN THE DEPLOYMENT - the name is handed in here, and
# what actually puts it there is compose.yml's VELOCITY_SERVERS default, which :steward-worker's
# TopologyTest holds. What it proves is that a HYPHENATED name survives the seeding as a bare TOML
# key under [servers], which is the one thing about `limbo-standby` that is new to this function.
case_begin "every server it is given is in the file, standby included"
dir=$(volume velocity-servers)
seed_velocity "$dir" "limbo=limbo:25565 limbo-standby=limbo-standby:25565 smp=smp:25565" limbo
expect_status 0
expect_toml_under_table "$dir/velocity.toml" "[servers]" limbo-standby '"limbo-standby:25565"'
expect_toml_under_table "$dir/velocity.toml" "[servers]" limbo '"limbo:25565"'
ok "limbo-standby registered"

# ------------------------------------------------------------------------------------------------
# season-2-ops/160. Everything above is about SEEDING a fresh volume. These are about a volume that
# already stood, which is the case that cost the first live test of the proxy swap: the key was
# added by hand to the live proxy's file and to nothing else, and the standby then refused every
# player it was handed with `multiplayer.disconnect.transfers_disabled`.

case_begin "an old velocity.toml with no [advanced] table gets one, at the end"
dir=$(old_proxy_volume velocity-old)
ensure_transfers "$dir"
expect_status 0
expect_toml_under_table "$dir/velocity.toml" "[advanced]" accepts-transfers true
expect_output "appended"
# AND IT IS THE LAST TABLE IN THE FILE. Everything after a table header belongs to that table, so
# an [advanced] written anywhere but the end would swallow the keys below it.
[[ "$(awk '/^\[/ { last = $1 } END { print last }' "$dir/velocity.toml")" == "[advanced]" ]] \
    || bad "[advanced] is not the last table in the file: $(grep '^\[' "$dir/velocity.toml" | tr '\n' ' ')"
# And [servers] still carries what it carried - an append must not disturb the file above it.
expect_toml_under_table "$dir/velocity.toml" "[servers]" limbo '"limbo:25565"'
ok "appended to an old file"

case_begin "an [advanced] table without the key gets the key, not a second table"
dir=$(volume velocity-advanced-empty)
printf 'bind = "0.0.0.0:25565"\n\n[advanced]\ncompression-level = 4\n' > "$dir/velocity.toml"
ensure_transfers "$dir"
expect_status 0
expect_toml_under_table "$dir/velocity.toml" "[advanced]" accepts-transfers true
# A SECOND [advanced] IS NOT A DUPLICATE SETTING, it is a file Velocity refuses to parse - which
# would take the proxy down entirely rather than leave it unable to accept a transfer.
[[ "$(grep -c '^\[advanced\]' "$dir/velocity.toml")" == "1" ]] \
    || bad "the file now has $(grep -c '^\[advanced\]' "$dir/velocity.toml") [advanced] tables; TOML allows one"
expect_toml_under_table "$dir/velocity.toml" "[advanced]" compression-level 4
ok "key added under the existing table"

case_begin "accepts-transfers = false is overruled and said out loud"
dir=$(volume velocity-off)
printf 'bind = "0.0.0.0:25565"\n\n[advanced]\naccepts-transfers = false\n' > "$dir/velocity.toml"
ensure_transfers "$dir"
expect_status 0
expect_toml_under_table "$dir/velocity.toml" "[advanced]" accepts-transfers true
expect_output "carried a different accepts-transfers"
ok "false corrected"

case_begin "a file that already says true is not touched at all"
dir=$(volume velocity-already)
printf 'bind = "0.0.0.0:25565"\n\n[advanced]\naccepts-transfers=true\n' > "$dir/velocity.toml"
before=$(cat "$dir/velocity.toml")
ensure_transfers "$dir"
expect_status 0
# WRITTEN WITHOUT SPACES ON PURPOSE: TOML allows `key=true` and Velocity writes `key = true`. A
# check that knew only one spelling would append a second [advanced] to a file that was already
# right, and that file does not parse.
[[ "$(cat "$dir/velocity.toml")" == "$before" ]] \
    || bad "the file was rewritten although it already accepted transfers:
$(cat "$dir/velocity.toml")"
ok "left alone"

case_begin "a root-level accepts-transfers is not mistaken for the real one"
dir=$(volume velocity-root)
printf 'accepts-transfers = true\nbind = "0.0.0.0:25565"\n\n[servers]\nlimbo = "limbo:25565"\n' > "$dir/velocity.toml"
ensure_transfers "$dir"
expect_status 0
expect_output "ROOT"
expect_toml_under_table "$dir/velocity.toml" "[advanced]" accepts-transfers true
ok "root-level key called out and the real one written"

# ------------------------------------------------------------------------------------------------
# season-2-ops/162. The second key under [advanced], and the one that follows a variable: the guard
# in front of 25565 makes every connection arrive from the guard's address, and `haproxy-protocol`
# is what makes Velocity read the client's real one out of the PROXY header. Wrong in either
# direction it costs every connection, which is why an unset variable changes nothing at all.
ensure_haproxy() {
    local data="$1" wanted="${2-unset}"
    set +e
    if [[ "$wanted" == "unset" ]]; then
        output=$(DATA="$data" \
            bash -c 'source "$1"; ensure_velocity_haproxy' haproxy-test "$ENTRYPOINT" 2>&1)
    else
        output=$(DATA="$data" VELOCITY_HAPROXY="$wanted" \
            bash -c 'source "$1"; ensure_velocity_haproxy' haproxy-test "$ENTRYPOINT" 2>&1)
    fi
    status=$?
    set -e
}

case_begin "no VELOCITY_HAPROXY leaves the file exactly as it is"
dir=$(volume haproxy-unset)
printf 'bind = "0.0.0.0:25565"\n\n[advanced]\naccepts-transfers = true\n' > "$dir/velocity.toml"
before=$(cat "$dir/velocity.toml")
ensure_haproxy "$dir"
expect_status 0
[[ "$(cat "$dir/velocity.toml")" == "$before" ]] \
    || bad "a deployment without a guard had its velocity.toml rewritten:
$(cat "$dir/velocity.toml")"
ok "untouched"

case_begin "VELOCITY_HAPROXY=true adds the key under the table that is already there"
dir=$(volume haproxy-true)
printf 'bind = "0.0.0.0:25565"\n\n[advanced]\naccepts-transfers = true\n' > "$dir/velocity.toml"
ensure_haproxy "$dir" true
expect_status 0
expect_toml_under_table "$dir/velocity.toml" "[advanced]" haproxy-protocol true
expect_toml_under_table "$dir/velocity.toml" "[advanced]" accepts-transfers true
[[ "$(grep -c '^\[advanced\]' "$dir/velocity.toml")" == "1" ]] \
    || bad "the file now has more than one [advanced] table; TOML allows one"
ok "added beside the other key"

case_begin "VELOCITY_HAPROXY=false is enforced too, because the guard can be taken away"
# The mirror image of the failure this key exists for: a proxy that still believes in a guard that
# is gone reads the first packet of every direct connection as a PROXY header and answers nobody.
dir=$(volume haproxy-false)
printf 'bind = "0.0.0.0:25565"\n\n[advanced]\nhaproxy-protocol = true\n' > "$dir/velocity.toml"
ensure_haproxy "$dir" false
expect_status 0
expect_toml_under_table "$dir/velocity.toml" "[advanced]" haproxy-protocol false
expect_output "carried a different haproxy-protocol"
ok "turned back off"

case_begin "a value that is neither true nor false changes nothing and says so"
dir=$(volume haproxy-nonsense)
printf 'bind = "0.0.0.0:25565"\n\n[advanced]\naccepts-transfers = true\n' > "$dir/velocity.toml"
before=$(cat "$dir/velocity.toml")
ensure_haproxy "$dir" yes
expect_status 0
expect_output "neither true nor false"
[[ "$(cat "$dir/velocity.toml")" == "$before" ]] \
    || bad "velocity.toml was rewritten from a value nobody can read"
ok "left alone and warned about"

case_begin "no velocity.toml is not this function's business"
dir=$(volume velocity-none)
ensure_transfers "$dir"
expect_status 0
expect_gone "$dir/velocity.toml"
ok "nothing to enforce"

# ------------------------------------------------------------------------------------------------

if (( failed > 0 )); then
    printf '\n%d case(s) failed\n' "$failed" >&2
    exit 1
fi
echo "all cases passed"
