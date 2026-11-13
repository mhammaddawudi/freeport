package io.github.mhammaddawudi.freeport.core.configs

import io.github.mhammaddawudi.freeport.core.CoreLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The whole sieve against the real collections and this machine's network.
 * Runs only when XRAY_BIN points at an Xray built for the host.
 */
class ConfigPoolLiveTest {
    @Test
    fun the_public_configs_yield_a_working_pool() = runBlocking {
        val binary = System.getenv("XRAY_BIN")?.let(::File)
        assumeTrue(binary?.canExecute() == true)
        val folder = Files.createTempDirectory("configs").toFile()
        val xray = Xray(binary!!, folder)
        val tester = ConfigTester(xray)
        val pool = ConfigPool(folder, ConfigFeed(folder), tester)
        val started = System.currentTimeMillis()
        val via = System.getenv("FEED_SOCKS")?.toIntOrNull()
        val count = pool.refresh("live", 120_000) { listOfNotNull(via) }
        CoreLog.lines.value.forEach { println(it.text) }
        println("pooled $count in ${(System.currentTimeMillis() - started) / 1000}s")
        assertTrue(count > 0)
        val best = pool.candidates("live").first()
        val port = ConfigTester.freePorts(1).first()
        xray.start(listOf(Xray.Route(best, port)), "session", udp = true).getOrThrow().use {
            println("session through ${best.label}: ${tester.answers(port)}ms")
        }
    }
}
