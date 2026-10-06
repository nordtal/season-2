#!/usr/bin/env bash
# Tests the seeding half of entrypoint.sh against fixture directories, with nothing but bash.
# entrypoint.sh is sourced; `$0` differs from its path so its source guard returns early.
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

# `ok` stays silent when its case has already failed.
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
pick() {
    local cache="$1" kind="$2"
    set +e
    output=$(bash -c 'source "$1"; newest_server_jar "$2" "$3"' seeding-test "$ENTRYPOINT" "$cache" "$kind" 2>&1)
    status=$?
    set -e
}

# Runs remove_superseded_jars against a cache directory, and leaves what it printed in $output.
sweep() {
    local cache="$1" kind="$2" keep="$3"
    set +e
    output=$(bash -c 'source "$1"; remove_superseded_jars "$2" "$3" "$4"' \
        seeding-test "$ENTRYPOINT" "$cache" "$kind" "$keep" 2>&1)
    status=$?
    set -e
}

# Runs seed_level_settings against a volume, and leaves its exit status in $status and output in $output.
# `bash -c ... seeding-test` sets $0 to a name the source guard does not take for the entrypoint.
seed() {
    local data="$1" level="$2" seed_value="${3:-}"
    set +e
    output=$(DATA="$data" LEVEL_NAME="$level" LEVEL_SEED="$seed_value" \
        bash -c 'source "$1"; seed_level_settings' seeding-test "$ENTRYPOINT" 2>&1)
    status=$?
    set -e
}

# Runs seed_velocity_config against a volume, and leaves its exit status in $status and output in $output.
seed_velocity() {
    local data="$1" servers="$2" try="${3:-}"
    set +e
    output=$(DATA="$data" VELOCITY_SERVERS="$servers" VELOCITY_TRY="$try" \
        bash -c 'source "$1"; seed_velocity_config' seeding-test "$ENTRYPOINT" 2>&1)
    status=$?
    set -e
}

# Runs the proxy config repair against an existing velocity.toml.
ensure_transfers() {
    local data="$1"
    set +e
    output=$(DATA="$data" \
        bash -c 'source "$1"; ensure_velocity_transfers' transfers-test "$ENTRYPOINT" 2>&1)
    status=$?
    set -e
}

# A velocity.toml as Velocity writes it, with no [advanced] table.
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

# assertions

expect_status() {
    local want="$1"
    if [[ "$status" != "$want" ]]; then
        bad "expected exit status ${want}, got ${status}. Output was:
${output}"
    fi
}

expect_property() {
    local file="$1" key="$2" want="$3" have
    # Not `head -n1`, which stops reading and makes the pipeline exit 141 under `pipefail`.
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

# The line carrying a TOML key under the given table, or nothing.
expect_toml_under_table() {
    local file="$1" table="$2" key="$3" want="$4" have
    have=$(awk -v table="$table" -v key="$key" '
        /^\[/ { current = $0; next }
        current == table && $1 == key { print; exit }
    ' "$file" 2>/dev/null)
    [[ "$have" == "$key = $want" ]] \
        || bad "expected '${key} = ${want}' under ${table} in ${file##*/}, found '${have:-nothing}'"
}

# fixtures

# Paper generated its default world and wrote level-name=world, while LEVEL_NAME says otherwise.
legacy_volume() {
    local dir target
    dir=$(volume "$1")
    target="$2"
    mkdir -p "$dir/world" "$dir/world_nether" "$dir/world_the_end" "$dir/${target}/datapacks"
    : > "$dir/world/level.dat"
    printf 'level-name=world\nmax-players=20\n' > "$dir/server.properties"
    printf '%s' "$dir"
}

# the cases

echo "entrypoint.sh: seeding"

case_begin "a fresh volume is seeded with both values"
data=$(volume fresh)
seed "$data" nordtal 1837371427
expect_status 0
expect_property "$data/server.properties" level-name nordtal
expect_property "$data/server.properties" level-seed 1837371427
ok "fresh volume"

# smp and hunger-games start on a volume where Paper already generated a default world.
case_begin "a volume with a default world and nobody in it is adopted, and the default world removed"
data=$(legacy_volume legacy-clean nordtal)
seed "$data" nordtal 1837371427
expect_status 0
expect_property "$data/server.properties" level-name nordtal
expect_gone "$data/world"
expect_gone "$data/world_nether"
expect_gone "$data/world_the_end"
expect_present "$data/nordtal/datapacks"
# The seed still reaches the file when the world folder holds only datapacks.
expect_property "$data/server.properties" level-seed 1837371427
ok "adopted, world removed, seed written"

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

case_begin "an empty playerdata folder is not somebody having played"
data=$(legacy_volume legacy-empty-playerdata nordtal)
mkdir -p "$data/world/playerdata"
seed "$data" nordtal 1837371427
expect_status 0
expect_gone "$data/world"
expect_property "$data/server.properties" level-name nordtal
ok "empty playerdata adopted"

# A level name somebody chose is never overruled.
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

# A world directory that holds no world, isolated from the adoption case above.
case_begin "a level-name folder holding only datapacks is not a world, so the seed is written"
data=$(volume datapacks-only)
mkdir -p "$data/nordtal/datapacks"
: > "$data/nordtal/datapacks/Terralith_26.2_v2.6.4.zip"
printf 'level-name=nordtal\n' > "$data/server.properties"
seed "$data" nordtal 1837371427
expect_status 0
expect_property "$data/server.properties" level-seed 1837371427
ok "seed written past a datapacks folder"

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

case_begin "no LEVEL_SEED writes no level-seed"
data=$(volume seedless)
seed "$data" hunger_games
expect_status 0
expect_property "$data/server.properties" level-name hunger_games
expect_no_property "$data/server.properties" level-seed
ok "seedless volume"

case_begin "seeding twice changes nothing the second time"
data=$(volume idempotent)
seed "$data" nordtal 1837371427
before=$(cat "$data/server.properties")
seed "$data" nordtal 1837371427
expect_status 0
[[ "$(cat "$data/server.properties")" == "$before" ]] \
    || bad "a second run rewrote server.properties"
ok "idempotent"


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

case_begin "the highest build of one version wins"
dir=$(cache builds paper-26.2-119.jar paper-26.2-121.jar paper-26.2-9.jar)
pick "$dir" paper
expect_status 0
# Build 121 is newer than build 9, which a text sort gets wrong.
expect_pick paper-26.2-121.jar
ok "highest build"

# The jar glob carries no version, so a cache holding only a newer version is not read as empty.
case_begin "the highest version wins, not the version somebody asked for"
dir=$(cache versions velocity-4.1.1-24.jar velocity-4.2.0-15.jar)
pick "$dir" velocity
expect_status 0
expect_pick velocity-4.2.0-15.jar
ok "highest version"

case_begin "4.10.0 beats 4.9.0, which sorting text gets wrong"
dir=$(cache numeric velocity-4.9.0-3.jar velocity-4.10.0-1.jar)
pick "$dir" velocity
expect_status 0
expect_pick velocity-4.10.0-1.jar
ok "numeric version comparison"

case_begin "an empty cache answers with nothing, and does not fail"
dir=$(cache empty)
pick "$dir" paper
expect_status 0
expect_pick ""
ok "empty cache"

# A jar whose version and build cannot be read is skipped, never guessed.
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

case_begin "only jars of this kind are considered"
dir=$(cache kinds paper-26.2-121.jar velocity-4.1.1-24.jar)
pick "$dir" velocity
expect_status 0
expect_pick velocity-4.1.1-24.jar
ok "kind respected"

case_begin "a cache holding nothing readable answers with nothing"
dir=$(cache unreadable paper-26.2.jar notes.txt)
pick "$dir" paper
expect_status 0
expect_pick ""
ok "nothing readable"

# The sweep never deletes the jar the server is about to run.
case_begin "the jar this start chose survives, and every other build goes"
dir=$(cache sweep-builds paper-26.2-119.jar paper-26.2-121.jar paper-26.2-124.jar)
sweep "$dir" paper "$dir/paper-26.2-124.jar"
expect_status 0
expect_cache "$dir" paper-26.2-124.jar
ok "older builds removed, the chosen one kept"

# A version bump leaves both jars, since steward supersedes by filename prefix.
case_begin "a superseded version goes too, not only a superseded build"
dir=$(cache sweep-versions velocity-4.1.1-24.jar velocity-4.2.0-31.jar)
sweep "$dir" velocity "$dir/velocity-4.2.0-31.jar"
expect_status 0
expect_cache "$dir" velocity-4.2.0-31.jar
ok "superseded version removed"

# The sweep takes the whole `<kind>-*.jar` glob, including jars the picker cannot read.
case_begin "a jar of this kind that the picker could not read is removed as well"
dir=$(cache sweep-junk paper-26.2-latest.jar paper-26.2-124.jar)
sweep "$dir" paper "$dir/paper-26.2-124.jar"
expect_status 0
expect_cache "$dir" paper-26.2-124.jar
ok "unreadable jar of this kind removed"

# The sweep leaves another kind's jars alone.
case_begin "a jar of another kind is not touched"
dir=$(cache sweep-kinds paper-26.2-124.jar velocity-4.2.0-31.jar notes.txt)
sweep "$dir" paper "$dir/paper-26.2-124.jar"
expect_status 0
expect_cache "$dir" notes.txt paper-26.2-124.jar velocity-4.2.0-31.jar
ok "another kind left alone"

# With a single jar, the sweep removes nothing and does not fail.
case_begin "a cache holding only the chosen jar is left exactly as it is"
dir=$(cache sweep-single paper-26.2-124.jar)
sweep "$dir" paper "$dir/paper-26.2-124.jar"
expect_status 0
expect_cache "$dir" paper-26.2-124.jar
ok "nothing to sweep"

# A proxy swap needs accepts-transfers and every server name in velocity.toml.
case_begin "a seeded velocity.toml accepts transfers, under [advanced]"
dir=$(volume velocity-transfers)
seed_velocity "$dir" "limbo=limbo:25565 limbo-standby=limbo-standby:25565"
expect_status 0
expect_toml_under_table "$dir/velocity.toml" "[advanced]" accepts-transfers true
ok "accepts-transfers = true under [advanced]"

# A hyphenated server name survives as a bare TOML key under [servers].
case_begin "every server it is given is in the file, standby included"
dir=$(volume velocity-servers)
seed_velocity "$dir" "limbo=limbo:25565 limbo-standby=limbo-standby:25565 smp=smp:25565" limbo
expect_status 0
expect_toml_under_table "$dir/velocity.toml" "[servers]" limbo-standby '"limbo-standby:25565"'
expect_toml_under_table "$dir/velocity.toml" "[servers]" limbo '"limbo:25565"'
ok "limbo-standby registered"

# An existing volume gains accepts-transfers, without which transfers are refused.

case_begin "an old velocity.toml with no [advanced] table gets one, at the end"
dir=$(old_proxy_volume velocity-old)
ensure_transfers "$dir"
expect_status 0
expect_toml_under_table "$dir/velocity.toml" "[advanced]" accepts-transfers true
expect_output "appended"
# [advanced] is appended as the last table, so it swallows no keys below it.
[[ "$(awk '/^\[/ { last = $1 } END { print last }' "$dir/velocity.toml")" == "[advanced]" ]] \
    || bad "[advanced] is not the last table in the file: $(grep '^\[' "$dir/velocity.toml" | tr '\n' ' ')"
# [servers] is unchanged by the append.
expect_toml_under_table "$dir/velocity.toml" "[servers]" limbo '"limbo:25565"'
ok "appended to an old file"

case_begin "an [advanced] table without the key gets the key, not a second table"
dir=$(volume velocity-advanced-empty)
printf 'bind = "0.0.0.0:25565"\n\n[advanced]\ncompression-level = 4\n' > "$dir/velocity.toml"
ensure_transfers "$dir"
expect_status 0
expect_toml_under_table "$dir/velocity.toml" "[advanced]" accepts-transfers true
# A second [advanced] table would make the file unparseable.
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
# `key=true` without spaces is valid TOML too and must be recognised.
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

# `haproxy-protocol` follows its variable; an unset variable changes nothing.
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
# Removing the guard turns `haproxy-protocol` off again.
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

case_begin "a jar's identity is read as JarName reads it, a trailing word included in the version"
# The same names, with the same answers, as JarNameTest.
for pair in \
    "smp-0.2.0.jar:smp" \
    "hunger-games-0.2.0.jar:hunger-games" \
    "packetevents-spigot-2.13.0.jar:packetevents-spigot" \
    "paper-26.2-121.jar:paper-26.2" \
    "velocity-4.1.1-24.jar:velocity-4.1.1" \
    "WorldEditDisplay-2.6.0-paper.jar:WorldEditDisplay" \
    "packetevents-spigot-2.14.0-SNAPSHOT.jar:packetevents-spigot" \
    "voxy-server-side-paper.jar:voxy-server-side"; do
    name="${pair%%:*}"
    expected="${pair#*:}"
    got=$(bash -c 'source "$1"; jar_identity "$2"' seeding-test "$ENTRYPOINT" "$name")
    [[ "$got" == "$expected" ]] || bad "${name} read as '${got}', JarName reads '${expected}'"
done
ok "the shell and JarName agree"

if (( failed > 0 )); then
    printf '\n%d case(s) failed\n' "$failed" >&2
    exit 1
fi
echo "all cases passed"
