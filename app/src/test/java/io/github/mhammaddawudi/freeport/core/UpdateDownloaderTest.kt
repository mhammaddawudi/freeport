package io.github.mhammaddawudi.freeport.core

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateDownloaderTest {

    @Test
    fun `a release asset name stays a plain apk file name`() {
        assertEquals("freeport-2.4.0-arm64-v8a.apk", UpdateDownloader.safeName("freeport-2.4.0-arm64-v8a.apk"))
        assertEquals(".._.._evil.apk", UpdateDownloader.safeName("../../evil.apk"))
        assertEquals("freeport.apk", UpdateDownloader.safeName(""))
        assertEquals("notes.txt.apk", UpdateDownloader.safeName("notes.txt"))
    }
}
