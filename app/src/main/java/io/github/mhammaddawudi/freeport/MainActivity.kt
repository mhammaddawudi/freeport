package io.github.mhammaddawudi.freeport

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mhammaddawudi.freeport.data.Locales
import io.github.mhammaddawudi.freeport.data.SettingsRepository
import io.github.mhammaddawudi.freeport.ui.WarpRoot
import io.github.mhammaddawudi.freeport.ui.theme.WarpTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(Locales.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent bars; WarpTheme sets their icon tint to match the ground.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val language = SettingsRepository.savedLanguage(this)
        val settings = (application as WarpApp).settings.settings
        setContent {
            // The theme switches live; the language rebuilds the activity, so it is read once.
            val current by settings.collectAsStateWithLifecycle()
            WarpTheme(language = language, theme = current.theme) {
                WarpRoot()
            }
        }
    }
}
