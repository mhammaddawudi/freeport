# The JNI bridge looks these up by name from native code.
-keep class io.github.mhammaddawudi.freeport.core.NativeAetherCore { *; }
-keepclasseswithmembernames class * { native <methods>; }

# The VpnService is resolved from the manifest by the framework.
-keep class io.github.mhammaddawudi.freeport.vpn.AetherVpnService { *; }

# hev-socks5-tunnel resolves this class by name from JNI_OnLoad.
-keep class hev.htproxy.TProxyService { *; }
