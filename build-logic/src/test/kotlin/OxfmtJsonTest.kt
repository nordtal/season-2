package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class OxfmtJsonTest {
    private val repository = File(System.getProperty("nordtal.resourcePack")).parentFile

    @Test
    fun everyJsonFileThatOxfmtChecksIsWrittenBackUnchanged() {
        val ignored = Regex("""(^|/)build/|node_modules|^resource-pack/src/|/src/test/resources/|package-lock\.json|\.gen\.json$""")
        val files = trackedFiles(repository, listOf("*.json")).filterNot { ignored.containsMatchIn(it) }
        // A file with comments is JSONC, which this reader leaves to oxfmt.
        val strict = files.mapNotNull { path -> runCatching { path to OxfmtJson.read(repository.resolve(path)) }.getOrNull() }

        assertTrue(strict.size > 200, "${strict.size} files read")
        strict.forEach { (path, value) ->
            val source = repository.resolve(path).readText().lines()
            val written = OxfmtJson.format(value).lines()
            val line = source.indices.firstOrNull { source[it] != written.getOrNull(it) } ?: written.size.takeIf { it != source.size }
            assertEquals(null, line?.let { "$path:${it + 1}\n${source.getOrNull(it)}\n${written.getOrNull(it)}" })
        }
    }

    @Test
    fun aLongArrayOfNumbersFillsItsLinesAsOxfmtDoes() {
        val shifts = OxfmtJson.Object().apply { put("shifts", (0 until 40).map { it * 3 }) }

        assertEquals(
            """
            {
              "shifts": [
                0, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30, 33, 36, 39, 42, 45, 48, 51, 54, 57, 60, 63, 66, 69, 72, 75, 78, 81, 84, 87,
                90, 93, 96, 99, 102, 105, 108, 111, 114, 117
              ]
            }
            """.trimIndent() + "\n",
            OxfmtJson.format(shifts),
        )
    }

    @Test
    fun anObjectWrittenAsOneLineStaysOneLineWhileItFits() {
        val cell = linkedMapOf("code" to "FE110", "constant" to "SPAWN")
        val block =
            OxfmtJson.Object().apply {
                put("title", "Icons")
                put("glyphs", listOf(cell, cell))
            }

        assertEquals(
            """
            {
              "title": "Icons",
              "glyphs": [
                { "code": "FE110", "constant": "SPAWN" },
                { "code": "FE110", "constant": "SPAWN" }
              ]
            }
            """.trimIndent() + "\n",
            OxfmtJson.format(block),
        )
    }

    @Test
    fun aLineOverOneHundredAndTwentyColumnsBreaks() {
        val chars = (1..12).map { "ABCDEFGH" }

        val text = OxfmtJson.format(linkedMapOf("chars" to chars))

        assertTrue(text.lines().all { it.length <= 120 }, text)
        assertEquals(chars, (OxfmtJson.read(text) as Map<*, *>)["chars"])
    }

    @Test
    fun anEmptyLineStaysBeforeItsMemberWhenAnotherIsInsertedOrRemoved() {
        val long = "a".repeat(62) to "b".repeat(70)
        val text = "[\n  \"${long.first}\",\n\n  \"${long.second}\"\n]\n"
        val array = OxfmtJson.read(text) as OxfmtJson.Array

        array.add(0, "c")
        array.removeAt(0)

        assertEquals(text, OxfmtJson.format(array))
    }
}
