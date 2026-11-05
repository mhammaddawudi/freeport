#!/usr/bin/env bash
#
# Renames the upstream core to "warp" inside a checkout of its crate: the
# library becomes libwarp.so, its C functions warp_*, its environment
# switches WARP_*, and its log lines say warp. Behaviour is untouched.
# The licence and the upstream repository link in Cargo.toml are kept.
#
# Usage: scripts/rebrand-core.sh <path to the core crate>
# Safe to run more than once.
set -euo pipefail

CRATE="${1:?give the path to the core crate (the folder with Cargo.toml)}"
cd "$CRATE"

for file in src/*.rs; do
  case "$file" in *aether*) git mv -f "$file" "${file//aether/warp}" 2>/dev/null || mv "$file" "${file//aether/warp}" ;; esac
done

perl -pi -e 's/AETHER/WARP/g; s/Aether/Warp/g; s/aether/warp/g' src/*.rs
# Only the crate's own names: the repository line still points upstream.
perl -pi -e 's/^name = "aether"$/name = "warp"/' Cargo.toml
perl -pi -e 's/^name = "aether"$/name = "warp"/' Cargo.lock
if [ -f .cargo/config.toml ]; then perl -pi -e 's/aether\.exe/warp.exe/g' .cargo/config.toml; fi

left="$(grep -rli aether src || true)"
[ -z "$left" ] || { echo "still names the old core: $left" >&2; exit 1; }
