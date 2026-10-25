package eu.nordtal.season.build

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EditPackSourcesTest {
    @TempDir
    lateinit var repository: File

    private val pack get() = repository.resolve("resource-pack")

    private fun badges(vararg codes: String) =
        """
        {
          "fonts": [
            {
              "font": "minecraft:default",
              "blocks": [
                {
                  "range": "FE000-FE00F",
                  "title": "Badges",
                  "height": 8,
                  "ascent": 6,
                  "glyphs": [
                    ${codes.joinToString(
            ",\n",
        ) { """{ "code": "$it", "constant": "B$it", "name": "b${it.lowercase()}", "texture": "nordtal:b${it.lowercase()}.png" }""" }}
                  ]
                }
              ]
            }
          ]
        }

        """.trimIndent()

    private fun git(vararg args: String) = git(repository, args.toList())

    private fun task(
        kind: String,
        vararg options: Pair<String, String>,
    ): EditPackSources {
        val project = ProjectBuilder.builder().withProjectDir(repository).build()
        val task = project.tasks.register("edit", EditPackSources::class.java).get()
        task.kind.set(kind)
        task.options.set(options.toMap())
        task.pack.set(pack)
        task.repository.set(repository)
        return task
    }

    @Test
    fun codePointsReadsGlyphsJsonOfMainThroughGitShow() {
        pack.mkdirs()
        pack.resolve("glyphs.json").writeText(badges("FE000", "FE001"))
        git("init", "-q", "-b", "main")
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "start")
        git("add", "-A")
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "main")
        pack.resolve("glyphs.json").writeText(badges("FE000", "FE002"))
        git("switch", "-q", "-c", "draft")
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-am", "draft")
        git("switch", "-q", "main")
        pack.resolve("glyphs.json").writeText(badges("FE000", "FE001", "FE002").replace("bfe002", "other"))
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-am", "more")
        git("switch", "-q", "draft")

        task("codePoints", "main" to "main").create()

        val codes =
            GlyphAllocation
                .read(pack.resolve("glyphs.json"))
                .fonts[0]
                .glyphs
                .map { it.codePoint }
        assertEquals(listOf(0xFE000, 0xFE003), codes)
    }

    @Test
    fun aRefusalAndAFailedGitShowFailWithTheirReason() {
        pack.mkdirs()
        pack.resolve("glyphs.json").writeText(badges("FE000"))
        git("init", "-q", "-b", "main")

        val refusal = assertThrows<GradleException> { task("delete", "glyph" to "comet").create() }
        val show = assertThrows<GradleException> { task("codePoints", "main" to "nowhere").create() }

        assertEquals("glyphs.json has no glyph named comet or with the constant comet.", refusal.message)
        assertTrue(show.message!!.startsWith("`git show nowhere:resource-pack/glyphs.json` failed"), show.message)
        assertTrue("fatal:" in show.message!!, show.message)
    }
}
