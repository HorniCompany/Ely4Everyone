package dev.ely4everyone.mod.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every text key the code uses exists, and English and Russian have the same keys. */
class LangFilesTest {
    // Tests run in mod/versions/<minecraft>/, the sources live in mod/src.
    private val modDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "src/main/resources/assets/ely4everyone/lang").isDirectory }

    private fun keys(language: String): Set<String> {
        val json = File(modDir, "src/main/resources/assets/ely4everyone/lang/$language.json").readText()
        return Regex(""""(ely4everyone\.[^"]+)"\s*:""").findAll(json).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `English and Russian have the same keys`() {
        val english = keys("en_us")
        val russian = keys("ru_ru")
        assertTrue(english.isNotEmpty())
        assertEquals(emptySet(), english - russian, "missing in ru_ru")
        assertEquals(emptySet(), russian - english, "missing in en_us")
    }

    @Test
    fun `every key used in the code is translated`() {
        val used = File(modDir, "src/main/kotlin").walk()
            .filter { it.extension == "kt" }
            .flatMap { Regex("""Lang\.tr\("([^"]+)"""").findAll(it.readText()).map { m -> "ely4everyone." + m.groupValues[1] } }
            .toSet()
        assertTrue(used.size > 30, "found only ${used.size} Lang.tr keys, the scan is broken")
        assertEquals(emptySet(), used - keys("en_us"), "used in code but missing in en_us")
    }
}
