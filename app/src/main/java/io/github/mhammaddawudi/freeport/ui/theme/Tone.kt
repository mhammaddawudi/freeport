package io.github.mhammaddawudi.freeport.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.github.mhammaddawudi.freeport.core.ConnectionState

/** The two colours a state paints its accents with. */
@Immutable
data class Tone(val primary: Color, val secondary: Color)

object Tones {
    @Composable
    fun of(state: ConnectionState): Tone {
        val p = LocalAetherPalette.current
        return when (state) {
            ConnectionState.Idle -> Tone(p.violet, p.indigo)
            is ConnectionState.Connected -> Tone(p.mint, p.aqua)
            is ConnectionState.Failed -> Tone(p.rose, p.coral)
            else -> Tone(p.amber, p.coral)
        }
    }
}
