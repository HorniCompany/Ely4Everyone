package dev.ely4everyone.mod.research26

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Research26TraceRecorderTest {
    @Test
    fun `deduplicates repeated trace entries`() {
        val recorder = Research26TraceRecorder(enabled = true)
        recorder.record("joinServer", "https://sessionserver.mojang.com/session/minecraft/join")
        recorder.record("joinServer", "https://sessionserver.mojang.com/session/minecraft/join")

        assertEquals(1, recorder.snapshot().size)
    }

    @Test
    fun `stores classifier metadata with trace entry`() {
        val recorder = Research26TraceRecorder(enabled = true)
        recorder.record("publickeys", "https://api.minecraftservices.com/publickeys")

        val entry = recorder.snapshot().single()
        assertEquals(AuthlibEndpointKind.MINECRAFTSERVICES, entry.classification.kind)
        assertTrue(entry.classification.isMinecraftServices)
    }
}
