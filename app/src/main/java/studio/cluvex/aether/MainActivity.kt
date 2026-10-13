package studio.cluvex.aether

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import studio.cluvex.aether.ui.AetherRoot
import studio.cluvex.aether.ui.theme.AetherTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(studio.cluvex.aether.data.Locales.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // auto() picks the icon tint from the system theme, so the bars stay
        // readable over both the off-white and the near-black ground.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val language = studio.cluvex.aether.data.SettingsRepository.savedLanguage(this)
        setContent {
            AetherTheme(language = language) {
                AetherRoot()
            }
        }
    }
}
