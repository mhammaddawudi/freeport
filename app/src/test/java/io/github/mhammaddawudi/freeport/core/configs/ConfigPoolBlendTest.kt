package io.github.mhammaddawudi.freeport.core.configs

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigPoolBlendTest {
    @Test
    fun aFreshReadingOutweighsTheOldOne() {
        assertEquals(900L, ConfigPool.blend(0, 900))
        assertEquals(300L, ConfigPool.blend(900, 0))
        assertEquals(700L, ConfigPool.blend(300, 900))
    }
}
