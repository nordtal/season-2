package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GlyphAllocationTest {
    @TempDir
    lateinit var pack: File

    private fun problems(glyphs: String): String {
        val file = pack.resolve("glyphs.json")
        file.writeText(
            """
            {"fonts": [{"font": "nordtal:hud", "lists": [{"constant": "BARS"}], "blocks": [
              {"range": "FE000-FE00F", "title": "Bars", "height": 4, "ascent": 3, "glyphs": [$glyphs]}]}]}
            """.trimIndent(),
        )
        return assertThrows<IllegalStateException> { GlyphAllocation.read(file) }.message!!
    }

    @Test
    fun everyGlyphHasAShortTitle() {
        val message =
            problems(
                """
                {"code": "FE000", "list": "BARS", "texture": "nordtal:a.png"},
                {"code": "FE001", "list": "BARS", "texture": "nordtal:b.png", "title": " "},
                {"code": "FE002", "list": "BARS", "texture": "nordtal:c.png", "title": "${"x".repeat(41)}"},
                {"code": "FE003", "list": "BARS", "texture": "nordtal:d.png", "title": "Fine"},
                {"code": "FE004", "list": "BARS", "texture": "nordtal:e.png", "title": "Box [x]"}
                """,
            )

        assertEquals(
            listOf(
                "glyphs.json > nordtal:hud > Bars > FE000: 'title' is missing",
                "glyphs.json > nordtal:hud > Bars > FE001: 'title' is empty",
                "glyphs.json > nordtal:hud > Bars > FE002: 'title' is longer than 40 characters",
                "glyphs.json > nordtal:hud > Bars > FE004: 'title' has a square bracket, which ends the designer's glyph token",
            ),
            message.lines().drop(1).map { it.trim() },
        )
    }
}
