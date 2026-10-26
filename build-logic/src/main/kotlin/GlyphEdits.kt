package eu.nordtal.season.build

import eu.nordtal.season.build.NewTextures.fonts
import eu.nordtal.season.build.NewTextures.parseRange
import eu.nordtal.season.build.NewTextures.range
import java.io.File
import javax.imageio.ImageIO

/**
 * Renames, edits, resizes and deletes a glyph of the pack at [pack], found by its name or its constant, and gives provisional
 * code points their final ones. Every change goes through [NewTextures.edit], so `glyphs.json` keeps its layout and rules.
 */
object GlyphEdits {
    private val KEYS = listOf("code", "constant", "name", "list", "texture", "height", "ascent", "status", "about")

    private class Found(
        val block: OxfmtJson.Object,
        val glyph: OxfmtJson.Object,
    ) {
        val label get() = (glyph["name"] ?: glyph["constant"] ?: glyph["list"]) as String
    }

    /** Gives [glyph] the name [to], and the constant that follows from it. A glyph without a name takes [to] as its constant. */
    fun rename(
        pack: File,
        repository: File,
        glyph: String,
        to: String,
    ): List<File> =
        NewTextures.edit(pack, null, 0, 0) { root ->
            val found = find(root, glyph)
            refuseUsed(repository, found)
            if (found.glyph["name"] != null) {
                found.glyph["name"] = to
                found.glyph["constant"] = NewTextures.constantOf(to)
            } else {
                found.glyph["constant"] = to
            }
        }

    /**
     * Removes [glyph] from `glyphs.json`, and its PNG and `.aseprite` source when no glyph, sheet or item model draws
     * them any more. A sheet keeps its columns, so only its last cell can go.
     */
    fun delete(
        pack: File,
        repository: File,
        glyph: String,
    ): List<File> {
        val root = OxfmtJson.read(pack.resolve("glyphs.json")) as Map<*, *>
        val found = find(root, glyph)
        val cells = found.block["glyphs"] as List<*>
        require(found.block["texture"] == null || cells.last() === found.glyph) {
            "${found.label} is cell ${cells.indexOfFirst {
                it === found.glyph
            } + 1} of ${cells.size} of the sheet ${found.block["title"]}. " +
                "Only its last cell can be deleted, since a later cell would move to another column."
        }
        refuseUsed(repository, found)
        val texture = found.glyph["texture"] as String?
        val drawn = texture != null && (textures(root).count { it == texture } > 1 || texture.removeSuffix(".png") in modelTextures(pack))
        val remove = if (texture == null || drawn) emptyList() else sources(pack, texture).filter { it.exists() }
        return NewTextures.edit(pack, null, 0, 0, remove) { edited ->
            val target = find(edited, glyph)
            val glyphs = target.block["glyphs"] as MutableList<*>
            glyphs.removeAt(glyphs.indexOfFirst { it === target.glyph })
        }
    }

    /** Every texture that `glyphs.json` draws, a sheet's once per block and a glyph's once per glyph. */
    private fun textures(root: Map<*, *>): List<String> =
        fonts(root).flatMap { it["blocks"] as List<*> }.map { it as Map<*, *> }.flatMap { block ->
            listOfNotNull(block["texture"] as String?) +
                (block["glyphs"] as List<*>?).orEmpty().mapNotNull { (it as Map<*, *>)["texture"] as String? }
        }

    /** The textures that the pack's models name, such as `nordtal:item/wand`, with `minecraft` where a name has no namespace. */
    private fun modelTextures(pack: File): Set<String> =
        pack
            .resolve("src/assets")
            .listFiles()
            .orEmpty()
            .flatMap { it.resolve("models").walk().filter { file -> file.extension == "json" } }
            .flatMap { ((OxfmtJson.read(it) as? Map<*, *>)?.get("textures") as? Map<*, *>)?.values.orEmpty() }
            .filterIsInstance<String>()
            .map { if (':' in it) it else "minecraft:$it" }
            .toSet()

    /**
     * Sets the [status], display [height], [ascent] and [about] of [glyph] that are not null. An empty status or about
     * removes it, and a height or ascent equal to the block's leaves the glyph without its own.
     */
    fun edit(
        pack: File,
        glyph: String,
        status: String? = null,
        height: Int? = null,
        ascent: Int? = null,
        about: String? = null,
    ): List<File> =
        NewTextures.edit(pack, null, 0, 0) { root ->
            val target = find(root, glyph)
            require((height == null && ascent == null) || target.block["texture"] == null) {
                "${target.label} is a cell of the sheet ${target.block["title"]}, which sets the display height and ascent of every cell."
            }
            for ((key, value) in listOf("height" to height, "ascent" to ascent)) {
                if (value == null) continue
                if (value == target.block[key]) target.glyph.remove(key) else target.glyph[key] = value
            }
            for ((key, value) in listOf("status" to status, "about" to about)) {
                if (value == null) continue
                if (value.isEmpty()) target.glyph.remove(key) else target.glyph[key] = value
            }
            order(target.glyph)
        }

    /** Puts the keys of [glyph] in the order that `glyphs.json` writes them. */
    private fun order(glyph: OxfmtJson.Object) {
        val entries =
            glyph.entries
                .sortedBy {
                    KEYS.indexOf(it.key).takeIf { index ->
                        index >= 0
                    } ?: KEYS.size
                }.map { it.toPair() }
        glyph.clear()
        glyph.putAll(entries)
    }

    /**
     * Scales the height and ascent of [glyph] by its PNG's pixel height over the pixel height [from] it had, when [coupled].
     *
     * Decoupled, both stay, so the glyph shows more detail at the same size, and nothing is written.
     */
    fun resize(
        pack: File,
        glyph: String,
        from: Int,
        coupled: Boolean,
    ): List<File> {
        require(from > 0) { "The previous pixel height is at least 1, not $from." }
        val found = find(OxfmtJson.read(pack.resolve("glyphs.json")) as Map<*, *>, glyph)
        val texture =
            found.glyph["texture"] as String?
                ?: throw IllegalArgumentException(
                    "${found.label} is a cell of the sheet ${found.block["title"]}, which sets the size of every cell.",
                )
        if (!coupled) return emptyList()
        val png = sources(pack, texture)[0]
        val to =
            png.takeIf { it.isFile }?.let { ImageIO.read(it) }?.height
                ?: throw IllegalArgumentException("${png.relativeTo(pack).invariantSeparatorsPath} is no PNG that can be read.")
        return NewTextures.edit(pack, null, 0, 0) { root ->
            val target = find(root, glyph)
            for (key in listOf("height", "ascent")) {
                val scaled = Math.round((target.glyph[key] ?: target.block[key]) as Int * to / from.toDouble()).toInt()
                if (scaled == target.block[key]) target.glyph.remove(key) else target.glyph[key] = scaled
            }
            order(target.glyph)
        }
    }

    /**
     * Gives each glyph that [main], the text of `glyphs.json` on main, lacks a code point that main has not taken.
     *
     * A glyph keeps a code point that is still free, a new block that overlaps a block of main moves to a free run with
     * its glyphs, and a glyph whose code point main took moves behind the last glyph of its block. Returns each move as
     * `font old new constant`, and writes nothing when nothing moves.
     */
    fun assignCodePoints(
        pack: File,
        main: String,
    ): List<String> {
        val mainRoot = OxfmtJson.read(main) as Map<*, *>
        if (moves(OxfmtJson.read(pack.resolve("glyphs.json")) as Map<*, *>, mainRoot).isEmpty()) return emptyList()
        var moves = emptyList<String>()
        NewTextures.edit(pack, null, 0, 0) { root -> moves = moves(root, mainRoot) }
        return moves
    }

    private fun moves(
        root: Map<*, *>,
        main: Map<*, *>,
    ): List<String> {
        val moves = mutableListOf<String>()
        for (font in fonts(root)) {
            val id = font["font"] as String
            val mainFont =
                fonts(main).firstOrNull { it["font"] == id }
                    ?: mapOf("font" to id, "numbering" to font["numbering"], "blocks" to emptyList<Any>())
            val blocks = font["blocks"] as MutableList<Any?>
            for (block in blocks.toList().map { it as MutableMap<String, Any?> }) {
                val glyphs = (block["glyphs"] as MutableList<*>?)?.map { it as MutableMap<String, Any?> } ?: continue
                val mainBlock =
                    (mainFont["blocks"] as List<*>).map { it as Map<*, *> }.firstOrNull {
                        it["range"] == block["range"] &&
                            it["title"] == block["title"]
                    }

                fun move(
                    glyph: MutableMap<String, Any?>,
                    code: Int,
                ) {
                    moves += "$id ${glyph["code"]} %X ${glyph["constant"] ?: glyph["list"]}".format(code)
                    glyph["code"] = "%X".format(code)
                }
                if (mainBlock == null) {
                    if (block["range"] == null) continue
                    val run = range(block)
                    val taken = NewTextures.taken(main, mainFont)
                    if (taken.none { it.first <= run.last && run.first <= it.last }) continue
                    val moved = NewTextures.freeRun(taken + NewTextures.taken(root, font).filter { it != run }, run.last - run.first + 1)
                    block["range"] = "%X-%X".format(moved.first, moved.last)
                    glyphs.forEach { move(it, code(it) - run.first + moved.first) }
                    blocks.removeAt(blocks.indexOfFirst { it === block })
                    NewTextures.place(blocks, block)
                    continue
                }
                // A sheet's cell is its column, so its code alone names it: a renamed cell keeps its place.
                val sheet = block["texture"] != null
                val identity = { glyph: Map<*, *> -> if (sheet) glyph["code"] else glyph["code"] to glyph["texture"] }
                val mainGlyphs = (mainBlock["glyphs"] as List<*>?).orEmpty().map { it as Map<*, *> }
                val known = mainGlyphs.map(identity).toSet()
                val taken = (mainGlyphs + glyphs.filter { identity(it) in known }).map(::code).toMutableSet()
                for (glyph in glyphs.filter { identity(it) !in known }) {
                    val code = code(glyph).takeIf { it !in taken } ?: (taken.max() + 1)
                    require(code in range(block)) { "Block ${block["title"]} of $id has no free code point left in ${block["range"]}." }
                    if (code != code(glyph)) move(glyph, code)
                    taken += code
                }
                val list = block["glyphs"] as MutableList<Any?>
                if (!sheet && glyphs.zipWithNext().any { (a, b) -> code(a) > code(b) }) {
                    val sorted = list.sortedBy { code(it as Map<*, *>) }
                    sorted.forEachIndexed { index, glyph -> list[index] = glyph }
                }
            }
        }
        return moves
    }

    private fun code(glyph: Map<*, *>) = (glyph["code"] as String).toInt(16)

    /** The places in the repository's files, tracked or not yet, that use [found]: `Glyphs.CONSTANT` in Java or Kotlin, or `<glyph:name>`. */
    private fun refuseUsed(
        repository: File,
        found: Found,
    ) {
        val constant = (found.glyph["constant"] as String?)?.let { Regex("""\bGlyphs\.${Regex.escape(it)}\b""") }
        val tag = (found.glyph["name"] as String?)?.let { "<glyph:$it>" }
        val places =
            trackedFiles(repository, emptyList(), untracked = true).flatMap { path ->
                val code = path.endsWith(".java") || path.endsWith(".kt")
                val text = if (code || tag != null) readText(repository.resolve(path)) else null
                text
                    .orEmpty()
                    .lineSequence()
                    .withIndex()
                    .filter { (_, line) ->
                        (code && constant?.containsMatchIn(line) == true) || (tag != null && tag in line)
                    }.map { "$path:${it.index + 1}" }
                    .toList()
            }
        check(places.isEmpty()) {
            "${found.label} is still used in ${places.size} place${if (places.size == 1) "" else "s"}:\n" +
                places.joinToString("\n") { "    $it" }
        }
    }

    private fun entries(root: Map<*, *>): List<Pair<OxfmtJson.Object, OxfmtJson.Object>> =
        fonts(root).flatMap { font -> (font["blocks"] as List<*>).map { it as OxfmtJson.Object } }.flatMap { block ->
            (block["glyphs"] as List<*>?).orEmpty().map { block to it as OxfmtJson.Object }
        }

    private fun find(
        root: Map<*, *>,
        glyph: String,
    ): Found =
        entries(root).firstOrNull { (_, entry) -> entry["name"] == glyph || entry["constant"] == glyph }?.let { Found(it.first, it.second) }
            ?: throw IllegalArgumentException("glyphs.json has no glyph named $glyph or with the constant $glyph.")

    /** The PNG of [texture] under `src/` and its `.aseprite` source under `art/`. */
    private fun sources(
        pack: File,
        texture: String,
    ): List<File> {
        val (namespace, path) = Identifier.parse(texture, ".png")
        return listOf(
            pack.resolve("src/assets/$namespace/textures/$path"),
            pack.resolve("art/assets/$namespace/textures/${path.removeSuffix(".png")}.aseprite"),
        )
    }
}
