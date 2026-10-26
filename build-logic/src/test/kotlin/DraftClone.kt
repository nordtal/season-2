package eu.nordtal.season.build

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A clone of a season repository with a resource pack, whose `origin` is a bare repository under [root], and a stub of
 * gh that logs its calls. The pack's `glyphs.json` holds the badges [badges] names, each with its PNG.
 */
class DraftClone(
    val root: File,
    vararg badges: String,
) {
    val origin: File = root.resolve("origin.git")
    val clone: File = root.resolve("clone")
    val pack: File = clone.resolve("resource-pack")
    val gh: File = root.resolve("gh")
    val ghLog: File = root.resolve("gh.log")

    /** What the stub prints for `gh pr view`: OPEN, MERGED or CLOSED. */
    val prState: File = root.resolve("pr-state")

    /** While this file exists, the stub's `gh pr create` fails. */
    val prCreateFails: File = root.resolve("pr-create-fails")

    init {
        clone.mkdirs()
        git(clone, "init", "-q", "-b", "main")
        clone.resolve(".githooks").mkdirs()
        File(System.getProperty("nordtal.commitMsgHook")).copyTo(clone.resolve(".githooks/commit-msg"))
        clone.resolve(".githooks/commit-msg").setExecutable(true)
        git(clone, "config", "core.hooksPath", ".githooks")
        writeGlyphs(*badges)
        badges.forEach { png(it, 0xFF000000.toInt()) }
        commitAll("feat: start")
        git(root, "init", "-q", "--bare", "-b", "main", origin.name)
        git(clone, "remote", "add", "origin", origin.absolutePath)
        git(clone, "push", "-q", "-u", "origin", "main")
        gh.writeText(
            """
            #!/bin/sh
            echo "${'$'}*" >> '${ghLog.absolutePath}'
            case "${'$'}1 ${'$'}2" in
              "api user") echo '{"login":"drawer","id":7,"name":"Dee Drawer","email":null}' ;;
              "pr create") [ -e '${prCreateFails.absolutePath}' ] && { echo "GraphQL: refused" >&2; exit 1; }
                echo "Creating pull request"; echo "https://github.com/nordtal/season-2/pull/42" ;;
              "pr view") cat '${prState.absolutePath}' ;;
              *) echo "stub gh: ${'$'}*" >&2; exit 1 ;;
            esac
            """.trimIndent() + "\n",
        )
        gh.setExecutable(true)
        prState.writeText("OPEN\n")
    }

    fun glyphs(vararg badges: String): String =
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
                    ${badges.joinToString(",\n") { badge(it) }}
                  ]
                }
              ]
            }
          ]
        }

        """.trimIndent()

    /** A badge `name@CODE`, such as `star@FE000`, with an optional `=constant`. */
    private fun badge(spec: String): String {
        val name = spec.substringBefore('@')
        val code = spec.substringAfter('@').substringBefore('=')
        val constant = spec.substringAfter('=', name.uppercase())
        return """{ "code": "$code", "constant": "$constant", "name": "$name", "texture": "nordtal:badges/$name.png" }"""
    }

    fun writeGlyphs(vararg badges: String) {
        pack.mkdirs()
        pack.resolve("glyphs.json").writeText(OxfmtJson.format(OxfmtJson.read(glyphs(*badges))))
    }

    fun pngFile(badge: String): File = pack.resolve("src/assets/nordtal/textures/badges/${badge.substringBefore('@')}.png")

    /** Writes the badge's PNG, 8 by 8 pixels of [color]. */
    fun png(
        badge: String,
        color: Int,
    ) {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 8) for (y in 0 until 8) image.setRGB(x, y, color)
        pngFile(badge).parentFile.mkdirs()
        ImageIO.write(image, "png", pngFile(badge))
    }

    fun commitAll(message: String) {
        git(clone, "add", "-A")
        git(clone, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", message)
    }

    /** Commits on origin's main as another clone would, with [change] made in that clone's pack. */
    fun commitOnMain(
        message: String,
        change: (File) -> Unit,
    ) = commitOn("main", message, change)

    /** Commits on origin's [branch] as another clone would, with [change] made in that clone's pack. */
    fun commitOn(
        branch: String,
        message: String,
        change: (File) -> Unit,
    ) {
        val other = root.resolve("other")
        if (!other.exists()) git(root, "clone", "-q", origin.absolutePath, other.name)
        git(other, "fetch", "-q", "origin")
        git(other, "checkout", "-q", "-B", branch, "origin/$branch")
        change(other.resolve("resource-pack"))
        git(other, "add", "-A")
        git(other, "-c", "user.name=o", "-c", "user.email=o@o", "commit", "-q", "-m", message)
        git(other, "push", "-q", "origin", "HEAD:$branch")
    }

    /** The text of [path] on [ref] of origin. */
    fun onOrigin(
        ref: String,
        path: String,
    ): String = git(origin, "show", "$ref:$path")

    fun git(
        dir: File,
        vararg arguments: String,
    ): String = git(dir, arguments.toList())
}
