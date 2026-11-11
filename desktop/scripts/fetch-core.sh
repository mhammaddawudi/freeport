#!/usr/bin/env bash
#
# Puts the warp core where the desktop engine expects it, next to this
# repository: ../warp-src/warp. It is the upstream core (AGPL-3.0, see
# NOTICE.md) at a pinned commit, renamed and with Freeport's patches, the
# same sources the Android build uses.
#
# Usage: desktop/scripts/fetch-core.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CORE_SRC="${CORE_SRC:-$ROOT/../warp-src}"
CORE_REPO="${CORE_REPO:-https://github.com/CluvexStudio/Aether.git}"
# The upstream commit Freeport's core patches are written against.
CORE_COMMIT="${CORE_COMMIT:-d58f9d568083129ce2c767c85054021eb3ee6a38}"

if [ ! -d "$CORE_SRC/.git" ]; then
  git init -q "$CORE_SRC"
  git -C "$CORE_SRC" config core.autocrlf false
  git -C "$CORE_SRC" remote add origin "$CORE_REPO"
  git -C "$CORE_SRC" fetch -q --depth 1 origin "$CORE_COMMIT"
  git -C "$CORE_SRC" checkout -q FETCH_HEAD
fi

# Upstream keeps the crate in aether/; Freeport's paths say warp/.
if [ -d "$CORE_SRC/aether" ] && [ ! -d "$CORE_SRC/warp" ]; then
  mv "$CORE_SRC/aether" "$CORE_SRC/warp"
fi
CRATE="$CORE_SRC/warp"
"$ROOT/scripts/rebrand-core.sh" "$CRATE"

for patch in "$ROOT"/scripts/core-patches/*.patch; do
  if patch -p2 -d "$CRATE" -N --dry-run -s < "$patch" >/dev/null 2>&1; then
    patch -p2 -d "$CRATE" -N -s < "$patch"
  elif patch -p2 -d "$CRATE" -R --dry-run -s < "$patch" >/dev/null 2>&1; then
    echo "   $(basename "$patch") is already in"
  else
    echo "$(basename "$patch") does not apply to this core" >&2
    exit 1
  fi
done
echo "==> core ready at $CRATE"
