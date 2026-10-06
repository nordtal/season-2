#!/usr/bin/env bash
# Tests deploy/jvm/secrets.sh under the image's own /bin/sh, without Docker.
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
READER="$HERE/secrets.sh"
[[ -f "$READER" ]] || { echo "secrets.sh not found beside this script" >&2; exit 1; }

failed=0
current_case=""
case_begin() { current_case="$1"; }
ok()  { printf '  ok    %s\n' "$1"; }
bad() { printf '  FAIL  %s: %s\n' "$current_case" "$1" >&2; failed=$(( failed + 1 )); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# What one variable is after the reader ran on $1, in the shell the entrypoint runs in.
exported() {
    local file="$1" name="$2"
    env -i PATH="$PATH" READER="$READER" FILE="$file" NAME="$name" \
        sh -c '. "$READER"; read_secrets "$FILE" 2>/dev/null; printenv "$NAME" || true'
}

# What the reader says on stderr for $1.
said() {
    env -i PATH="$PATH" READER="$READER" FILE="$1" sh -c '. "$READER"; read_secrets "$FILE" 2>&1 >/dev/null'
}

mkdir -p "$WORK/secrets"
cat > "$WORK/secrets/secrets.env" <<'FIXTURE'
# a comment
PLAIN=value
QUOTED="in double quotes"
SINGLE='in single quotes'
export EXPORTED=exported
EQUALS=a=b==
DANGEROUS=$(touch "$WORK/executed")
SPACES=two words
EMPTY=
not a line
9STARTS_WITH_A_DIGIT=x
LAST=without a newline
FIXTURE
printf 'NO_NEWLINE=at the end' >> "$WORK/secrets/secrets.env"

case_begin "a value is exported as written, one layer of quotes taken off"
[[ "$(exported "$WORK/secrets/secrets.env" PLAIN)"    == "value" ]]            || bad "PLAIN"
[[ "$(exported "$WORK/secrets/secrets.env" QUOTED)"   == "in double quotes" ]] || bad "QUOTED kept its quotes"
[[ "$(exported "$WORK/secrets/secrets.env" SINGLE)"   == "in single quotes" ]] || bad "SINGLE kept its quotes"
[[ "$(exported "$WORK/secrets/secrets.env" EXPORTED)" == "exported" ]]         || bad "an exported assignment"
[[ "$(exported "$WORK/secrets/secrets.env" EQUALS)"   == "a=b==" ]]            || bad "a value with = in it"
[[ "$(exported "$WORK/secrets/secrets.env" SPACES)"   == "two words" ]]        || bad "a value with a space"
[[ "$(exported "$WORK/secrets/secrets.env" NO_NEWLINE)" == "at the end" ]]     || bad "the last line without a newline"
ok "plain, quoted, exported, with = and spaces, the last line"

case_begin "reading the file never runs it"
[[ "$(exported "$WORK/secrets/secrets.env" DANGEROUS)" == '$(touch "$WORK/executed")' ]] \
    || bad "a command substitution did not stay text"
[[ -e "$WORK/executed" ]] && bad "a value containing a command substitution was executed"
ok "a command substitution is text"

case_begin "a line that is no assignment is skipped and never repeated"
[[ -z "$(exported "$WORK/secrets/secrets.env" 9STARTS_WITH_A_DIGIT)" ]] || bad "a name starting with a digit"
message="$(said "$WORK/secrets/secrets.env")"
grep -q "is skipped" <<<"$message" || bad "the skipped lines were not said"
grep -q "not a line" <<<"$message" && bad "a skipped line was repeated"
ok "skipped, said, not repeated"

case_begin "a missing file is said only where the directory is mounted"
[[ "$(said "$WORK/secrets/absent.env")" == *"starts without its own secrets"* ]] \
    || bad "a missing file under a mounted directory was not said"
[[ -z "$(said "$WORK/nowhere/secrets.env")" ]] || bad "a service without a mount was told about it"
ok "said under a mount, silent without one"

case_begin "the entrypoint reads the file before it starts the JVM"
read_line="$(grep -n '^read_secrets ' "$HERE/entrypoint.sh" | cut -d: -f1)"
exec_line="$(grep -n '^exec java ' "$HERE/entrypoint.sh" | cut -d: -f1)"
[[ -n "$read_line" && -n "$exec_line" ]] && (( read_line < exec_line )) \
    || bad "entrypoint.sh does not call read_secrets before exec java"
ok "read_secrets, then exec java"

if (( failed > 0 )); then
    printf '%d check(s) failed\n' "$failed" >&2
    exit 1
fi
printf 'every check passed\n'
