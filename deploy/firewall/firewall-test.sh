#!/usr/bin/env bash
# Tests that nordtal.nft admits by interface only loopback, Docker's bridges and the tailnet, and the
# tailnet only on SSH, HTTPS, T3 Code and ntfy. It reads the file and loads nothing.
# The rules are nft text, where $name is a define of the table, so they stay in single quotes.
# shellcheck disable=SC2016
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TABLE="$HERE/nordtal.nft"
[[ -f "$TABLE" ]] || { echo "nordtal.nft not found beside this script" >&2; exit 1; }

failed=0
ok()  { printf '  ok    %s\n' "$1"; }
bad() { printf '  FAIL  %s\n' "$1" >&2; failed=$(( failed + 1 )); }

# The rules of one chain, one per line, without leading tabs.
chain() { sed -n "/^	chain $1 {/,/^	}/p" "$TABLE" | sed -e 's/^[[:space:]]*//' -e '/^chain /d' -e '/^}/d'; }

mapfile -t input < <(chain input)
mapfile -t forward < <(chain forward)
mapfile -t defines < <(sed -n 's/^define //p' "$TABLE")

expected_input=(
    'iif lo accept'
    'iifname $docker accept'
    'iifname $tailscale tcp dport { $ssh, $https, $t3, $ntfy } accept'
)
admitted=()
for rule in "${input[@]}"; do
    if [[ "$rule" =~ (^|[[:space:]])(meta[[:space:]]+)?iif(name)?[[:space:]] ]]; then admitted+=("$rule"); fi
done
if [[ "$(printf '%s\n' "${admitted[@]}")" == "$(printf '%s\n' "${expected_input[@]}")" ]]; then
    ok "input interface admission checked: interfaces=loopback,docker,tailscale services=ssh,https,t3,ntfy"
else
    bad "input admits by interface: $(printf '[%s] ' "${admitted[@]}")"
fi

admitted=()
for rule in "${forward[@]}"; do
    if [[ "$rule" =~ (^|[[:space:]])(meta[[:space:]]+)?iif(name)?[[:space:]] ]]; then admitted+=("$rule"); fi
done
if [[ "$(printf '%s\n' "${admitted[@]}")" == 'iifname $docker accept' ]]; then
    ok "forward admits by interface only Docker's bridges"
else
    bad "forward admits by interface: $(printf '[%s] ' "${admitted[@]}")"
fi

for define in 'tailscale = "tailscale0"' 'https = 443' 't3 = 3773' 'ntfy = 8443'; do
    found=0
    for line in "${defines[@]}"; do
        if [[ "$line" == "$define" ]]; then found=1; fi
    done
    if (( found )); then ok "define $define"; else bad "no define $define"; fi
done

# The tailnet rule comes before the SSH rate limit, which a tailnet login does not count against, and
# after the drop of invalid packets.
position() {
    local i
    for i in "${!input[@]}"; do
        if [[ "${input[$i]}" == "$1"* ]]; then echo "$i"; return; fi
    done
    echo -1
}
tailnet=$(position 'iifname $tailscale')
invalid=$(position 'ct state invalid drop')
limit=$(position 'tcp dport $ssh ct state new')
if (( invalid >= 0 && invalid < tailnet && tailnet < limit )); then
    ok "the tailnet rule sits between the drop of invalid packets and the SSH rate limit"
else
    bad "the tailnet rule is at $tailnet, the invalid drop at $invalid, the SSH limit at $limit"
fi

if (( failed )); then
    echo "$failed failed" >&2
    exit 1
fi
