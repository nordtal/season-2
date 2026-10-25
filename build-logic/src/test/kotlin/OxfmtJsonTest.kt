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
}
