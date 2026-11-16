package io.github.mhammaddawudi.freeport.core

import io.github.mhammaddawudi.freeport.data.NoizeProfile
import io.github.mhammaddawudi.freeport.data.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunerTest {
    private var now = 1_000_000L
    private val tuner = Tuner(MemoryTuningStore()) { now }

    @Test
    fun `an unmeasured network starts with the preferred profile`() {
        assertEquals(Recipe(NoizeProfile.Amnezia), tuner.recipeFor("m43211", Protocol.WireGuard, NoizeProfile.Amnezia))
    }

    @Test
    fun `wireguard keeps its disguise even when slow`() {
        tuner.record("m43211", Protocol.WireGuard, Recipe(NoizeProfile.Amnezia), 300)
        assertEquals(Recipe(NoizeProfile.Amnezia), tuner.recipeFor("m43211", Protocol.WireGuard, NoizeProfile.Amnezia))
    }

    @Test
    fun `a blocked default does not send masque to lighter profiles`() {
        tuner.record("m43211", Protocol.MasqueH2, Recipe(NoizeProfile.Firewall), 0)
        assertEquals(Recipe(NoizeProfile.Firewall, fragment = true), tuner.recipeFor("m43211", Protocol.MasqueH2, NoizeProfile.Firewall))
        tuner.record("m43211", Protocol.MasqueH2, Recipe(NoizeProfile.Firewall, fragment = true), 0)
        assertEquals(Recipe(NoizeProfile.Firewall), tuner.recipeFor("m43211", Protocol.MasqueH2, NoizeProfile.Firewall))
    }

    @Test
    fun `a slow masque profile makes way for a lighter one, and the fastest wins`() {
        tuner.record("w", Protocol.MasqueH2, Recipe(NoizeProfile.Firewall), 300)
        assertEquals(Recipe(NoizeProfile.Off), tuner.recipeFor("w", Protocol.MasqueH2, NoizeProfile.Firewall))
        tuner.record("w", Protocol.MasqueH2, Recipe(NoizeProfile.Off), 0)
        assertEquals(Recipe(NoizeProfile.Light), tuner.recipeFor("w", Protocol.MasqueH2, NoizeProfile.Firewall))
        tuner.record("w", Protocol.MasqueH2, Recipe(NoizeProfile.Light), 900)
        tuner.record("w", Protocol.MasqueH2, Recipe(NoizeProfile.Firewall, fragment = true), 200)
        assertEquals(Recipe(NoizeProfile.Light), tuner.recipeFor("w", Protocol.MasqueH2, NoizeProfile.Firewall))
        assertEquals(900L, tuner.speedOf("w", Protocol.MasqueH2))
    }

    @Test
    fun `a good speed stops the search`() {
        tuner.record("w", Protocol.MasqueH2, Recipe(NoizeProfile.Firewall), 2_400)
        assertEquals(Recipe(NoizeProfile.Firewall), tuner.recipeFor("w", Protocol.MasqueH2, NoizeProfile.Firewall))
    }

    @Test
    fun `networks are kept apart and old numbers expire`() {
        tuner.record("w", Protocol.MasqueH2, Recipe(NoizeProfile.Firewall), 2_400)
        assertNull(tuner.speedOf("m43211", Protocol.MasqueH2))
        now += 8L * 24 * 60 * 60 * 1000
        assertNull(tuner.speedOf("w", Protocol.MasqueH2))
    }

    @Test
    fun `a single failure halves a known speed`() {
        tuner.record("w", Protocol.MasqueH2, Recipe(NoizeProfile.Firewall), 2_000)
        tuner.record("w", Protocol.MasqueH2, Recipe(NoizeProfile.Firewall), 0)
        assertEquals(1_000L, tuner.speedOf("w", Protocol.MasqueH2))
    }

    @Test
    fun `http3 also measures a wider packet once its profiles are known`() {
        listOf(NoizeProfile.Firewall, NoizeProfile.Off, NoizeProfile.Light)
            .forEach { tuner.record("w", Protocol.MasqueH3, Recipe(it), 400) }
        assertEquals(Recipe(NoizeProfile.Firewall, Tuner.WIDE_H3_MTU), tuner.recipeFor("w", Protocol.MasqueH3, NoizeProfile.Firewall))
        tuner.record("w", Protocol.MasqueH3, Recipe(NoizeProfile.Firewall, Tuner.WIDE_H3_MTU), 1_200)
        assertEquals(Recipe(NoizeProfile.Firewall, Tuner.WIDE_H3_MTU), tuner.recipeFor("w", Protocol.MasqueH3, NoizeProfile.Firewall))
        assertEquals(1_200L, tuner.speedOf("w", Protocol.MasqueH3))
    }

    @Test
    fun http2AlsoMeasuresASplitClientHello() {
        listOf(NoizeProfile.Firewall, NoizeProfile.Off, NoizeProfile.Light)
            .forEach { tuner.record("h", Protocol.MasqueH2, Recipe(it), 400) }
        val split = Recipe(NoizeProfile.Firewall, fragment = true)
        assertEquals(split, tuner.recipeFor("h", Protocol.MasqueH2, NoizeProfile.Firewall))
        assertEquals("firewall+frag", split.id)
    }
}
