# Third-party components

## Warp core — AGPL-3.0
Based on Aether by CluvexStudio: https://github.com/CluvexStudio/Aether

The circumvention core (`libwarp.so`), built from upstream by
`scripts/build-core.sh`, which renames it (`scripts/rebrand-core.sh`) and
applies Freeport's changes (`scripts/core-patches/`). Because the app links it, the whole app is covered
by the AGPL-3.0: if you distribute a build, you have to offer its source to
whoever receives it.

## hev-socks5-tunnel — MIT
https://github.com/heiher/hev-socks5-tunnel
Copyright (c) 2022 hev

Turns the TUN interface into SOCKS5 connections (`libhev-socks5-tunnel.so`).

## BoringSSL — ISC / OpenSSL licence
https://boringssl.googlesource.com/boringssl

Statically linked into the core through the `boring` crate.

## GitHub mark
The GitHub logo in `app/src/main/res/drawable/ic_github.xml` is a trademark
of GitHub, Inc., used only to link to a profile.

## Psiphon — GPL-3.0
https://github.com/Psiphon-Labs/psiphon-tunnel-core

Its console client (`libpsiphon.so`) runs as a separate process, for region
unlock and as the Psiphon protocol. `app/src/main/assets/psiphon-servers.txt`
is Psiphon's own signed public server list, as published when the app was
built.

## Arti — MIT / Apache-2.0
https://gitlab.torproject.org/tpo/core/arti

The Tor Project's Rust client (`libarti.so`), run as a separate process for
the Tor protocol. Built by `scripts/build-tor.sh`.

## Snowflake — BSD-3-Clause
https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake

The Tor Project's Snowflake client (`libsnowflake.so`), started by Arti as
its pluggable transport. Built by `scripts/build-tor.sh`.
