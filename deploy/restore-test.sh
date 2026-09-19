#!/usr/bin/env bash
#
# The decisions in deploy/restore.sh, exercised without Docker and without an archive.
#
# WHY THIS EXISTS: restore.sh empties a volume before it fills it, and one of those volumes is
# nordtal-s2_mc-smp - Nordtal, a hand-built world that is in no repository and in no release. It is
# the same class of thing as `deploy/dev reset`, which is why the confirmation below has the same
# shape, and it is tested for the same reason: running it and looking is too late.
#
# WHAT IT CANNOT SAY ANYTHING ABOUT: whether a restored volume then holds what the archive held.
# That needs Docker and a real archive, and it is a restore drill rather than a test.
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/restore.sh"
[[ -f "$SCRIPT" ]] || { echo "restore.sh not found beside this script" >&2; exit 1; }

failed=0
current_case=""
case_begin() { current_case="$1"; }
ok()  { printf '  ok    %s\n' "$1"; }
bad() { printf '  FAIL  %s: %s\n' "$current_case" "$1" >&2; failed=$(( failed + 1 )); }

# `$0` is this script, not restore.sh, which is what makes its source guard return early.
# shellcheck source=deploy/restore.sh
source "$SCRIPT"

kind_is() {
    local name="$1" expected="$2" actual
    actual="$(archive_kind "$name")"
    [[ "$actual" == "$expected" ]] || bad "'$name' is '$actual', expected '$expected'"
}

# ------------------------------------------------------------------------------------------------
case_begin "the names steward-worker actually writes are recognised"
# Taken from TarSnapshots and DatabaseDump: <volume>-<stamp>.tar.zst and nordtal-<stamp>.dump, the
# stamp being yyyyMMdd'T'HHmmss'Z' in UTC.
kind_is "nordtal-s2_mc-smp-20260913T031500Z.tar.zst"         volume
kind_is "nordtal-s2_mc-smp-plugins-20260913T031500Z.tar.zst" volume
kind_is "nordtal-s2_bot-config-20260913T031500Z.tar.zst"     volume
kind_is "nordtal-20260913T031500Z.dump"                      database
ok "four real names, three volumes and a dump"

case_begin "a volume whose own name has dashes comes back whole"
# The whole reason the split is the LAST dash before the stamp. Get this wrong and the archive is
# restored into a volume called nordtal-s2_mc-smp, which exists - so it would not fail, it would
# overwrite the world with a plugins folder.
[[ "$(volume_of nordtal-s2_mc-smp-plugins-20260913T031500Z.tar.zst)" == "nordtal-s2_mc-smp-plugins" ]] \
    || bad "the -plugins volume lost its suffix"
[[ "$(volume_of nordtal-s2_mc-proxy-20260913T031500Z.tar.zst)" == "nordtal-s2_mc-proxy" ]] \
    || bad "the proxy volume lost part of its name"
ok "the stamp is the split, not the first dash"

case_begin "the stamp is read back for naming and for saying what is lost"
[[ "$(stamp_of nordtal-s2_mc-smp-20260913T031500Z.tar.zst)" == "20260913T031500Z" ]] \
    || bad "the stamp was not found in a volume archive"
[[ "$(stamp_of nordtal-20260913T031500Z.dump)" == "20260913T031500Z" ]] \
    || bad "the stamp was not found in a dump"
ok "both kinds give up their stamp"

# ------------------------------------------------------------------------------------------------
case_begin "a half-written archive is its own answer, not an error to squint at"
# steward-worker writes every archive under .partial and renames it only after reading it back. A
# .partial is therefore the one file in that directory that looks restorable and is not.
kind_is "nordtal-s2_mc-smp-20260913T031500Z.tar.zst.partial" partial
kind_is "nordtal-20260913T031500Z.dump.partial"              partial
ok "both kinds of interrupted backup are refused as partial"

case_begin "the mark beside an archive is not an archive"
# steward-worker writes `<archive>.unverified` next to a backup taken after a stop it could not
# confirm the end of. It sits in the same directory and ends in a name a glob would happily hand to
# a restore, so it has to be told apart from the thing it describes - and told apart as `mark`
# rather than as `unknown`, because the useful answer is "read this, then name the archive".
kind_is "nordtal-s2_mc-smp-20260913T031500Z.tar.zst.unverified" mark
kind_is "nordtal-20260913T031500Z.dump.unverified"              mark
# And the archive it belongs to is still an ordinary archive. The mark must not spread.
kind_is "nordtal-s2_mc-smp-20260913T031500Z.tar.zst" volume
ok "a mark is a mark, and the archive beside it is still restorable"

case_begin "anything else is refused rather than guessed at"
for wrong in \
    "" \
    "nordtal-s2_mc-smp.tar.zst" \
    "nordtal-s2_mc-smp-2026-09-13.tar.zst" \
    "nordtal-s2_mc-smp-20260913T0315Z.tar.zst" \
    "nordtal-s2_mc-smp-20260913T031500Z.tar" \
    "nordtal-s2_mc-smp-20260913T031500Z.tar.gz" \
    "nordtal-20260913T031500Z.sql" \
    "-20260913T031500Z.tar.zst" \
    "backup.tar.zst" \
    "../../etc/passwd"
do
    kind="$(archive_kind "$wrong")"
    [[ "$kind" == unknown ]] || bad "'$wrong' was accepted as '$kind'"
done
ok "a missing stamp, a short stamp, a dashed date, the wrong suffix and a path are all unknown"

case_begin "a volume archive with no volume name in front of the stamp is not a volume archive"
# `-20260913T031500Z.tar.zst` would otherwise strip to the empty string, and an empty volume name
# is what makes a confirmation succeed on a bare Return - see the last case in this file.
[[ "$(archive_kind "-20260913T031500Z.tar.zst")" == unknown ]] || bad "a nameless archive was accepted"
ok "there has to be a name in front of the stamp"

# ------------------------------------------------------------------------------------------------
case_begin "the confirmation has to be the volume's own name"
restore_confirmed nordtal-s2_mc-smp nordtal-s2_mc-smp || bad "the name typed back was not accepted"
ok "the name is accepted"

# "yes" is what somebody types when they have stopped reading, and a bare Return is what they press
# when they never started. Both are the exact moment this guard is for.
for typed in "" " " "y" "Y" "yes" "YES" "nordtal-s2_mc-smp " "NORDTAL-S2_MC-SMP" \
             "mc-smp" "nordtal-s2_mc-smp-plugins" "*"; do
    if restore_confirmed nordtal-s2_mc-smp "$typed"; then
        bad "'$typed' was accepted as confirmation for nordtal-s2_mc-smp"
    fi
done
ok "yes, an empty line, the unprefixed name, a neighbouring volume and a wildcard are all refused"

case_begin "an empty target confirms nothing"
# If the volume name were ever empty - a parse that stripped too much - an empty answer matching it
# would empty a volume on a bare Return.
restore_confirmed "" ""  && bad "an empty target accepted an empty confirmation"
restore_confirmed "" "y" && bad "an empty target accepted anything at all"
ok "nothing can be confirmed against an empty name"

# ------------------------------------------------------------------------------------------------
case_begin "an archive's volume name is a directory in the installation"
# The mapping season-2-ops/124 rests on: the directory is the volume name without the project
# prefix, and nothing else translates the two. Getting it wrong restores a world into a directory
# nothing mounts, which looks exactly like a restore that worked.
[[ "$(directory_for nordtal-s2_mc-smp nordtal-s2 /srv/nordtal)" == "/srv/nordtal/mc-smp" ]] \
    || bad "the world"
[[ "$(directory_for nordtal-s2_mc-smp-plugins nordtal-s2 /srv/nordtal)" \
    == "/srv/nordtal/mc-smp-plugins" ]] || bad "a volume whose own name has dashes"
[[ "$(directory_for nordtal-s2_postgres-data nordtal-s2 /srv/nordtal/)" \
    == "/srv/nordtal/postgres-data" ]] || bad "a trailing slash on the installation directory"
directory_for nordtal-s2_mc-smp nordtal-s2 "" \
    && bad "an environment file with no NORDTAL_DIR answered anyway"
directory_for mc-smp nordtal-s2 /srv/nordtal \
    && bad "a name without the project prefix answered anyway"
directory_for other_mc-smp nordtal-s2 /srv/nordtal \
    && bad "another deployment's volume answered anyway"
ok "prefix off, directory under the installation; no prefix and no root answer nothing"

# ------------------------------------------------------------------------------------------------

if (( failed > 0 )); then
    printf '\n%d case(s) failed\n' "$failed" >&2
    exit 1
fi
echo "all cases passed"
