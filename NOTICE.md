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
