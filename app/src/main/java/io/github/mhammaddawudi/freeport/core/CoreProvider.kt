package io.github.mhammaddawudi.freeport.core

/**
 * Picks the core implementation.
 *
 * The native core is used whenever `libwarp.so` is packaged in the apk;
 * otherwise the app runs on the mock so it stays fully usable while the Rust
 * side is being cross-compiled. [forceMock] pins the mock regardless, for
 * screenshots and UI work.
 */
object CoreProvider {

    var forceMock: Boolean = false

    val isNative: Boolean get() = !forceMock && NativeWarpCore.loaded

    val core: WarpCore by lazy {
        if (isNative) NativeWarpCore().also { it.prepareLogging() } else MockWarpCore()
    }
}
