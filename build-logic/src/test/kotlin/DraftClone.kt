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

    /** The state that the stub's `gh pr view` prints: OPEN, MERGED or CLOSED. */
    val prState: File = root.resolve("pr-state")

    /** The review decision, checks and reviews that the stub's `gh pr view` prints beside the state, as JSON fields. */
    val prView: File = root.resolve("pr-view")

    /** What the stub prints for the line comments of a pull request, a JSON list. */
    val prComments: File = root.resolve("pr-comments")

    /** The number of the pull request whose `gh pr view` fails, while this file exists. */
    val prViewFails: File = root.resolve("pr-view-fails")

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
              "pr view") [ "${'$'}3" = "${'$'}(cat '${prViewFails.absolutePath}' 2>/dev/null)" ] && { echo "HTTP 502" >&2; exit 1; }
                printf '{"state":"%s",%s}' "${'$'}(cat '${prState.absolutePath}')" "${'$'}(cat '${prView.absolutePath}')" ;;
              "api repos/{owner}/{repo}/pulls/"*) cat '${prComments.absolutePath}' ;;
              *) echo "stub gh: ${'$'}*" >&2; exit 1 ;;
            esac
            """.trimIndent() + "\n",
        )
        gh.setExecutable(true)
        prState.writeText("OPEN")
        prView.writeText(""" "reviewDecision": "", "statusCheckRollup": [], "reviews": [] """)
        prComments.writeText("[]")
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

    fun pngFile(
        badge: String,
        pack: File = this.pack,
    ): File = pack.resolve("src/assets/nordtal/textures/badges/${badge.substringBefore('@')}.png")

    /** Writes the badge's PNG into [pack], 8 by 8 pixels of [color]. */
    fun png(
        badge: String,
        color: Int,
        pack: File = this.pack,
    ) {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 8) for (y in 0 until 8) image.setRGB(x, y, color)
        pngFile(badge, pack).parentFile.mkdirs()
        ImageIO.write(image, "png", pngFile(badge, pack))
    }

    /** Sets the [field] of the glyph [code] in the `glyphs.json` of [pack] to [value], or removes it for null. */
    fun editBadge(
        pack: File,
        code: String,
        field: String,
        value: String?,
    ) {
        val file = pack.resolve("glyphs.json")
        val root = OxfmtJson.read(file) as Map<*, *>
        val glyph =
            NewTextures
                .fonts(root)
                .flatMap { it["blocks"] as List<*> }
                .flatMap { (it as Map<*, *>)["glyphs"] as List<*> }
                .map { it as MutableMap<String, Any?> }
                .single { it["code"] == code }
        if (value == null) glyph.remove(field) else glyph[field] = value
        file.writeText(OxfmtJson.format(root))
    }

    /** The glyph [code] of the `glyphs.json` [text]. */
    fun glyphOf(
        text: String,
        code: String,
    ): Map<*, *> =
        NewTextures
            .fonts(OxfmtJson.read(text) as Map<*, *>)
            .flatMap { it["blocks"] as List<*> }
            .flatMap { (it as Map<*, *>)["glyphs"] as List<*> }
            .map { it as Map<*, *> }
            .single { it["code"] == code }

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

    /**
     * Makes the [nth] move of `HEAD` from now on fail, as a rollback would that cannot write the clone. A move is each
     * reset or merge that writes `ORIG_HEAD`.
     */
    fun failHeadMove(nth: Int) {
        val hooks = root.resolve("hooks")
        hooks.mkdirs()
        clone.resolve(".githooks/commit-msg").copyTo(hooks.resolve("commit-msg"), overwrite = true).setExecutable(true)
        val count = root.resolve("head-moves")
        count.writeText("0\n")
        hooks.resolve("reference-transaction").writeText(
            """
            #!/bin/sh
            [ "${'$'}1" = prepared ] && grep -q ' ORIG_HEAD${'$'}' || exit 0
            n=${'$'}(( ${'$'}(cat '${count.absolutePath}') + 1 ))
            echo ${'$'}n > '${count.absolutePath}'
            [ ${'$'}n -ne $nth ]
            """.trimIndent() + "\n",
        )
        hooks.resolve("reference-transaction").setExecutable(true)
        git(clone, "config", "core.hooksPath", hooks.absolutePath)
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
