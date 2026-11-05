package io.github.mhammaddawudi.freeport.data

import android.content.Context
import java.io.File

/**
 * Carries files saved under the names versions before 2.3.0 used over to
 * the current ones: settings, remembered edges and, above all, the WARP
 * identities, which cannot always be registered again from inside Iran.
 *
 * Runs before anything opens a preference file. A file is only moved when
 * nothing has been saved under its new name yet, so it is safe every start.
 */
object LegacyStorage {

    /** The prefix older versions saved under. */
    private const val OLD = "aether"
    private const val NEW = "warp"

    fun migrate(context: Context) {
        runCatching {
            val data = context.dataDir ?: return
            moveNamed(File(data, "shared_prefs"), recursive = false)
            moveNamed(context.filesDir, recursive = true)
        }
    }

    private fun moveNamed(dir: File, recursive: Boolean) {
        val children = dir.listFiles() ?: return
        children.forEach { child ->
            if (recursive && child.isDirectory) moveNamed(child, recursive = true)
            if (!child.name.contains(OLD, ignoreCase = true)) return@forEach
            val target = File(dir, child.name.replace(OLD, NEW, ignoreCase = true))
            if (!target.exists()) child.renameTo(target)
        }
    }
}
