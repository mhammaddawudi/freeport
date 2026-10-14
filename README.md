# Freeport

An Android client for [Aether](https://github.com/CluvexStudio/Aether), the
censorship-circumvention core by CluvexStudio. English, Persian and Kurdish
interface, four transports, per-app routing.

## What it does

| Screen | |
|---|---|
| **Connect** | One button that fills as the connection is made — the scan's real progress while it looks for a route, then a closed green ring once traffic is flowing. The four transports below it. |
| **Settings** | Routing (whole device or chosen apps) · scan mode, obfuscation, IP family · language · advanced core flags, folded away · about |

If a transport stops getting through, the app moves to another on its own
after a couple of failed attempts, and returns to the chosen one once a
connection holds. The order is deliberate: when MASQUE over QUIC is being
dropped, the next thing tried is WireGuard rather than the other MASQUE.
| **Log** | The app's own steps and the core's, in the order they happened |

## Transports

| Card | Core transport | CLI equivalent |
|---|---|---|
| Warp in Warp | nested wireguard | `--gool` |
| MASQUE | `masque` | `--masque` |
| MASQUE H2 | `masque` over HTTP/2 | `--masque --h2` |
| WireGuard | `wg` | `--wg` |

MASQUE and WireGuard are driven step by step through the core's C API
(`aether_identity_open` → `aether_scan_start` → `aether_tunnel_start`), which
is what lets the UI show real scan progress. Warp in Warp has no step-API
equivalent — `Transport` in the core knows only masque and wireguard — so it
is handed to `aether_core_start` with CLI arguments instead.

Every connection scans afresh. The core's `*-lastconn.toml` is removed before
each attempt and `--no-quick-reconnect` is passed: reusing the last gateway
made the first connection instant but pinned the session to whichever edge
answered first, which was regularly a slow one.

## How traffic actually moves

Aether exposes a SOCKS5 proxy; it does not read IP packets. The app adds the
missing half:

1. `VpnService` raises a TUN interface and excludes the app's own uid, so the
   core's sockets reach the edge directly instead of looping into the tunnel.
2. [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) reads raw
   packets off that interface and replays each flow as a SOCKS5 connection.
   UDP rides SOCKS5 UDP ASSOCIATE, which the core implements — without it DNS
   inside the tunnel would have nowhere to go.
3. The tunnel is only declared up once the core's proxy is actually
   listening. Starting tun2socks against a listener that is not up yet gives
   a tunnel that reports success and carries nothing.

Each session takes the first free local port from 1819 upward, because a
cancelled core job does not always release the one it had.

## Building

Needs Android Studio (or the command line tools) with a JDK 17 or 21.

```bash
./gradlew assembleRelease
```

Without `libaether.so` the app runs on a simulated core: the interface works,
nothing is tunnelled, and the connect screen says so.

For the real thing:

```bash
ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/26.1.10909125 scripts/build-core.sh
./gradlew assembleRelease
```

That script builds BoringSSL against the NDK toolchain, then the core, the
JNI shim and tun2socks, for arm64-v8a, armeabi-v7a and x86_64, and drops them
all into `app/src/main/jniLibs/`. Set `ABIS` to narrow it down.

Release builds are signed with the key named in `keystore.properties`, which
is not in version control. Create your own:

```bash
keytool -genkeypair -v -keystore release.jks -alias freeport \
  -keyalg RSA -keysize 2048 -validity 10000
```

```properties
storeFile=release.jks
storePassword=…
keyAlias=freeport
keyPassword=…
```

## Layout

```
app/src/main/
├── cpp/aether_jni.c          JNI shim over the core's C API, plus its log pipe
├── jniLibs/                  the three native libraries (built, not in git)
└── java/io/github/mhammaddawudi/freeport/
    ├── core/                 the seam to the core: job API as suspend calls,
    │                         a simulated implementation, the session engine
    ├── data/                 settings and the enums that mirror the core's
    │                         own wire strings
    ├── vpn/                  VpnService, per-app routing, tun2socks bridge
    └── ui/                   theme, drawn icon set, screens
```

## Licence

Aether is AGPL-3.0, and this app links it, so the combined work is AGPL-3.0
as well: anyone you give a build to is entitled to its source. See NOTICE.md
for the other components.
