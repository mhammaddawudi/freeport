package io.github.mhammaddawudi.freeport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private fun release(tag: String, vararg assets: String, prerelease: Boolean = false) = """
        {"tag_name":"$tag","name":"Freeport ${tag.removePrefix("v")}","html_url":"https://github.com/x/y/releases/$tag",
         "prerelease":$prerelease,"draft":false,
         "assets":[${assets.joinToString(",") { """{"name":"$it","size":1000,"browser_download_url":"https://github.com/dl/$it"}""" }}]}
    """.trimIndent()

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(UpdateChecker.isNewer("v2.0", "1.5"))
        assertTrue(UpdateChecker.isNewer("1.10", "1.9"))
        assertTrue(UpdateChecker.isNewer("2.0.1", "2.0"))
        assertFalse(UpdateChecker.isNewer("v2.0", "2.0"))
        assertFalse(UpdateChecker.isNewer("1.4", "2.0"))
        assertFalse(UpdateChecker.isNewer("nightly", "2.0"))
    }

    @Test
    fun `the apk for the phone's own abi is chosen first`() {
        val json = release("v2.1", "freeport-2.1-armeabi-v7a.apk", "freeport-2.1-arm64-v8a.apk", "freeport-2.1-universal.apk")
        val update = UpdateChecker.parse(json, "2.0", listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals("freeport-2.1-arm64-v8a.apk", update.apkName)
        assertEquals("2.1", update.version)
        assertEquals("https://github.com/dl/freeport-2.1-arm64-v8a.apk", update.apkUrl)
    }

    @Test
    fun `an abi without its own apk falls back to universal`() {
        val json = release("v2.1", "freeport-2.1-arm64-v8a.apk", "freeport-2.1-universal.apk")
        assertEquals("freeport-2.1-universal.apk", UpdateChecker.parse(json, "2.0", listOf("x86_64"))!!.apkName)
    }

    @Test
    fun `nothing is offered when up to date, prerelease or without an apk`() {
        assertNull(UpdateChecker.parse(release("v2.0", "freeport-2.0-universal.apk"), "2.0", listOf("arm64-v8a")))
        assertNull(UpdateChecker.parse(release("v2.1", "freeport-2.1-universal.apk", prerelease = true), "2.0", listOf("arm64-v8a")))
        assertNull(UpdateChecker.parse(release("v2.1", "notes.txt"), "2.0", listOf("arm64-v8a")))
    }
}
