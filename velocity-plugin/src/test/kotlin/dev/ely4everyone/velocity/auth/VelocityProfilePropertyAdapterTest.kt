package dev.ely4everyone.velocity.auth

import dev.ely4everyone.shared.auth.AuthProfileProperty
import kotlin.test.Test
import kotlin.test.assertEquals

class VelocityProfilePropertyAdapterTest {
    @Test
    fun `replaces missing signatures with empty strings`() {
        val adapted = VelocityProfilePropertyAdapter.adapt(
            listOf(
                AuthProfileProperty(
                    name = "textures",
                    value = "base64-value",
                    signature = null,
                ),
            ),
        )

        assertEquals(1, adapted.size)
        assertEquals("textures", adapted.single().name)
        assertEquals("base64-value", adapted.single().value)
        assertEquals("", adapted.single().signature)
    }

    @Test
    fun `drops blank properties`() {
        val adapted = VelocityProfilePropertyAdapter.adapt(
            listOf(
                AuthProfileProperty(name = "", value = "value"),
                AuthProfileProperty(name = "textures", value = ""),
                AuthProfileProperty(name = "textures", value = "ok", signature = "sig"),
            ),
        )

        assertEquals(1, adapted.size)
        assertEquals("textures", adapted.single().name)
        assertEquals("ok", adapted.single().value)
        assertEquals("sig", adapted.single().signature)
    }
}
