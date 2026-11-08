package io.github.mhammaddawudi.freeport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {

    private val body = """
        en: Faster scans on every protocol.
        fa: اسکن سریع‌تر روی همه پروتکل‌ها.
        kmr: Lêgerîna bileztir.
    """.trimIndent()

    @Test
    fun picksTheAppsLanguage() {
        assertEquals("اسکن سریع‌تر روی همه پروتکل‌ها.", ReleaseNotes.summary(body, "fa"))
    }

    @Test
    fun fallsBackToEnglish() {
        assertEquals("Faster scans on every protocol.", ReleaseNotes.summary(body, "ckb"))
    }

    @Test
    fun untaggedNotesLoseTheirMarkdown() {
        assertEquals("Fast edges and a link fix.", ReleaseNotes.summary("## **Fast** edges and a [link](https://x.y) fix.", "en"))
    }

    @Test
    fun longNotesAreCut() {
        val long = "word ".repeat(100)
        val shown = ReleaseNotes.summary(long, "en")
        assertTrue(shown.length <= ReleaseNotes.MAX_CHARS + 1)
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun tagsHiddenInACommentStillReachTheDialog() {
        // GitHub hides the tagged lines inside an HTML comment; the app reads the raw body.
        val release = """
            <!--
            fa: سایفون و تور اضافه شدند.
            en: Adds Psiphon and Tor.
            -->

            Freeport 2.5 is out

            Psiphon and Tor: two new protocols outside Cloudflare
            ---
            فری‌پورت ۲.۵ منتشر شد
        """.trimIndent()
        assertEquals("سایفون و تور اضافه شدند.", ReleaseNotes.summary(release, "fa"))
        assertEquals("Adds Psiphon and Tor.", ReleaseNotes.summary(release, "kmr"))
    }
}
