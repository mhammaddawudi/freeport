package io.github.mhammaddawudi.freeport.core.configs

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The daily census the `configs` workflow runs: every config in the public
 * collections is knocked on and tested through Xray, and those that answer
 * are written to CENSUS_OUT, quickest first. The app fetches that short list
 * before the collections themselves. Runs only when CENSUS_OUT and XRAY_BIN are set.
 */
class ConfigCensusTest {
    @Test
    fun census() = runBlocking {
        val out = System.getenv("CENSUS_OUT")?.let(::File)
        val binary = System.getenv("XRAY_BIN")?.let(::File)
        assumeTrue(out != null && binary?.canExecute() == true)
        val folder = Files.createTempDirectory("census").toFile()
        val tester = ConfigTester(Xray(binary!!, folder))
        val links = ConfigFeed(folder, includeCensus = false).load()
        val open = tester.knock(links, links.size, KNOCK_BUDGET_MS)
        println("census: ${links.size} configs, ${open.size} servers answered")
        val healthy = open.chunked(BATCH).flatMap { tester.probe(it) }.sortedBy { it.delayMs }.take(KEEP)
        println("census: ${healthy.size} configs work")
        out!!.writeText(healthy.joinToString("\n") { it.link.raw } + "\n")
    }

    private companion object {
        const val KNOCK_BUDGET_MS = 180_000L
        const val BATCH = 200
        const val KEEP = 1_500
    }
}
