package io.github.mhammaddawudi.freeport.ui.components

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Below this much memory a phone is treated as low end and gets the light version of the look. */
private const val LITE_TOTAL_MEMORY = 3L * 1024 * 1024 * 1024

/**
 * True on phones that should get the light version of the look: Android's
 * own low-RAM flag, under 3 GB of memory, or animations switched off in the
 * developer options.
 */
fun isLiteMotion(context: Context): Boolean {
    val scale = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    if (scale == 0f) return true
    val manager = context.getSystemService(ActivityManager::class.java) ?: return false
    if (manager.isLowRamDevice) return true
    val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    return memory.totalMem in 1 until LITE_TOTAL_MEMORY
}

@Composable
fun rememberLiteMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { isLiteMotion(context) }
}

/**
 * A 0..1 phase that loops only while [running]. When it stops it holds where
 * it is, so nothing is redrawn: an idle screen asks for no frames at all,
 * unlike an infinite transition, which ticks every frame whether or not
 * anything uses it.
 */
@Composable
fun rememberLoop(running: Boolean, durationMs: Int, reverse: Boolean = false): State<Float> {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(running, durationMs, reverse) {
        if (!running) return@LaunchedEffect
        while (true) {
            if (reverse && phase.value >= 0.5f) {
                phase.animateTo(0f, tween(((phase.value) * durationMs).toInt().coerceAtLeast(1), easing = LinearEasing))
            } else {
                val left = ((1f - phase.value) * durationMs).toInt().coerceAtLeast(1)
                phase.animateTo(1f, tween(left, easing = LinearEasing))
                if (!reverse) phase.snapTo(0f)
            }
        }
    }
    return phase.asState()
}
