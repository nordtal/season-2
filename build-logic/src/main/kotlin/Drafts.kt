package eu.nordtal.season.build

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File

/**
 * The drafts of a clone's pack: changes against the last commit that no pull request holds yet. A draft is one texture
 * with its source, `.mcmeta`, model, item definition and entries in `glyphs.json`; a font's or block's fields and any
 * other file are drafts of their own.
 */
object Drafts {
    private const val GLYPHS = "glyphs.json"
    private const val REVIEWS = "nordtal-designer/reviews.json"

    /**
     * One draft. [id] is its texture's id, `font <font>` or `block <font> <range>` for the fields of a font or block,
     * or the path of a file. [state] is `added`, `changed` or `deleted`, [files] are its files relative to the pack, and
     * [review] is the number of the pull request that holds an older version of it.
     */
    data class Draft(
        val id: String,
        val label: String,
        val state: String,
        val files: List<String>,
        val review: Int?,
    )

    /**
     * A pull request that a publish opened: its [number], [url] and [branch], the [drafts] it holds and the [commit] that
     * was pushed last.
     */
    data class Review(
        val number: Int,
        val url: String,
        val branch: String,
        val drafts: List<String>,
        val commit: String?,
    )

    /** The entries of one draft in `glyphs.json`, each as JSON text, and the name the designer knows it by. */
    private class Part(
        val label: String,
    ) {
        val content = mutableListOf<String>()
    }

    /** The drafts of [pack], sorted by id. A change that equals its version in a pull request is no draft. */
    fun list(pack: File): List<Draft> {
        val head = glyphs(pack, "HEAD")
        val work = pack.resolve(GLYPHS).takeIf { it.isFile }?.let { OxfmtJson.read(it) as Map<*, *> }
        val headParts = parts(head)
        val workParts = parts(work)
        val ids = sortedMapOf<String, MutableList<String>>()
        changedIds(head, work).forEach { ids.getOrPut(it) { mutableListOf() } }
        for (path in changedFiles(pack) - GLYPHS) {
            ids.getOrPut(idOf(pack, path)) { mutableListOf() } += path
        }
        val committed = git(pack, listOf("ls-tree", "-r", "-z", "--name-only", "HEAD", "--", ".")).split('\u0000').toSet()
        val reviews = reviews(pack)
        return ids.mapNotNull { (id, files) ->
            files.sort()
            val review = reviews.lastOrNull { id in it.drafts }
            if (review != null && same(pack, review.commit ?: "origin/${review.branch}", id, files, workParts)) return@mapNotNull null
            val before = id in headParts || files.any { it in committed }
            val after = id in workParts || files.any { pack.resolve(it).exists() }
            Draft(
                id,
                workParts[id]?.label ?: headParts[id]?.label ?: label(id),
                if (!before) {
                    "added"
                } else if (!after) {
                    "deleted"
                } else {
                    "changed"
                },
                files,
                review?.number,
            )
        }
    }

    /** The ids of the drafts of [pack], which [PackModel] marks. */
    fun ids(pack: File): Set<String> = list(pack).map { it.id }.toSet()

    /** The changed files of [pack] that belong to the drafts [ids], relative to the pack. */
    internal fun files(
        pack: File,
        ids: Set<String>,
    ): List<String> = (changedFiles(pack) - GLYPHS).filter { idOf(pack, it) in ids }

    /**
     * Restores the files and entries of the draft [id] in [pack] to the last commit, or to its pull request when one
     * holds it, and returns the files it wrote or deleted.
     */
    fun discard(
        pack: File,
        id: String,
    ): List<File> {
        val draft = list(pack).firstOrNull { it.id == id } ?: throw IllegalArgumentException("$id is no draft.")
        val ref = draft.review?.let { number -> "origin/${reviews(pack).last { it.number == number }.branch}" } ?: "HEAD"
        val kept = git(pack, listOf("ls-tree", "-r", "-z", "--name-only", ref, "--", ".")).split('\u0000').toSet()
        val (restored, removed) = draft.files.partition { it in kept }
        if (restored.isNotEmpty()) git(pack, listOf("restore", "--source=$ref", "--worktree", "--") + restored)
        if (removed.isNotEmpty()) AtomicFiles.write(removed.map { pack.resolve(it) }.filter { it.exists() }.associateWith { null })
        val files = draft.files.map { pack.resolve(it) }
        val source = glyphs(pack, ref)
        if (id !in changedIds(source, OxfmtJson.read(pack.resolve(GLYPHS)) as Map<*, *>)) return files
        return files + NewTextures.edit(pack, null, 0, 0) { apply(it, source, setOf(id)) }
    }

    /** Writes the files and entries of [drafts] as [pack] holds them into [target], the pack of another work tree. */
    internal fun copy(
        pack: File,
        target: File,
        drafts: List<Draft>,
    ) {
        AtomicFiles.write(
            drafts
                .flatMap { it.files }
                .associate { path ->
                    val file = pack.resolve(path)
                    target.resolve(path) to if (file.exists()) file.readBytes() else null
                }.filter { (file, bytes) -> bytes != null || file.exists() },
        )
        val work = pack.resolve(GLYPHS).takeIf { it.isFile }?.let { OxfmtJson.read(it) as Map<*, *> }
        val ids = changedIds(glyphs(target, "HEAD"), work).intersect(drafts.map { it.id }.toSet())
        if (ids.isNotEmpty()) NewTextures.edit(target, null, 0, 0) { apply(it, work, ids) }
    }

    /** The ids whose entries differ between [before] and [after], two versions of `glyphs.json`. */
    internal fun changedIds(
        before: Map<*, *>?,
        after: Map<*, *>?,
    ): Set<String> {
        val a = parts(before)
        val b = parts(after)
        return (a.keys + b.keys).filter { a[it]?.content != b[it]?.content }.toSet()
    }

    /** `glyphs.json` of the pack at [ref], or null where it has none. */
    internal fun glyphs(
        pack: File,
        ref: String,
    ): Map<*, *>? = runCatching { git(pack, listOf("show", "$ref:./$GLYPHS")) }.getOrNull()?.let { OxfmtJson.read(it) as Map<*, *> }

    /** The pull requests that publishes of [pack] opened and that are neither merged nor closed. */
    fun reviews(pack: File): List<Review> {
        val file = reviewsFile(pack)
        if (!file.isFile) return emptyList()
        return (JsonSlurper().parse(file) as List<*>).map { it as Map<*, *> }.map {
            Review(
                (it["number"] as Number).toInt(),
                it["url"] as String,
                it["branch"] as String,
                (it["drafts"] as List<*>).map(Any?::toString),
                it["commit"] as String?,
            )
        }
    }

    internal fun writeReviews(
        pack: File,
        reviews: List<Review>,
    ) {
        val file = reviewsFile(pack)
        file.parentFile.mkdirs()
        file.writeText(
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    reviews.map {
                        linkedMapOf(
                            "number" to it.number,
                            "url" to it.url,
                            "branch" to it.branch,
                            "drafts" to it.drafts,
                            "commit" to it.commit,
                        )
                    },
                ),
            ) +
                "\n",
        )
    }

    /** The record of the pull requests, in the clone's git directory, so no commit ever holds it. */
    private fun reviewsFile(pack: File): File =
        File(git(pack, listOf("rev-parse", "--git-path", REVIEWS)).trim()).let { if (it.isAbsolute) it else pack.resolve(it) }

    /**
     * Moves the entries of [ids] from [source] into [target], two versions of `glyphs.json`: each entry of an id leaves
     * [target], and each entry that [source] holds for it takes its place. A glyph whose block or font [target] lacks
     * brings that block's or font's fields along, and a block or font that [source] dropped goes once it is empty.
     */
    internal fun apply(
        target: OxfmtJson.Object,
        source: Map<*, *>?,
        ids: Set<String>,
    ) {
        val fonts = target["fonts"] as MutableList<Any?>
        for (font in fonts.map { it as MutableMap<String, Any?> }) {
            val id = font["font"] as String
            val blocks = font["blocks"] as MutableList<Any?>
            removeWhere(blocks) { (it as Map<*, *>)["texture"] in ids }
            for (block in blocks.map { it as Map<*, *> }) {
                (block["glyphs"] as MutableList<Any?>?)?.let { glyphs -> removeWhere(glyphs) { glyphId(id, it as Map<*, *>) in ids } }
            }
        }

        fun targetFont(from: Map<*, *>): OxfmtJson.Object =
            fonts.firstOrNull { (it as Map<*, *>)["font"] == from["font"] } as OxfmtJson.Object?
                ?: copy(from, "blocks").also { fonts += it }

        fun targetBlock(
            font: OxfmtJson.Object,
            from: Map<*, *>,
        ): OxfmtJson.Object {
            val blocks = font["blocks"] as MutableList<Any?>
            return blocks.firstOrNull {
                blockId(
                    font["font"] as String,
                    it as Map<*, *>,
                ) == blockId(font["font"] as String, from)
            } as OxfmtJson.Object?
                ?: copy(from, "glyphs").also { place(blocks, it) }
        }
        for (font in source?.let(NewTextures::fonts).orEmpty()) {
            val id = font["font"] as String
            if ("font $id" in ids) replace(targetFont(font), font, "blocks")
            for (block in (font["blocks"] as List<*>).map { it as Map<*, *> }) {
                if (block["texture"] != null) {
                    if (block["texture"] in ids) place(targetFont(font)["blocks"] as MutableList<Any?>, block)
                    continue
                }
                if (blockId(id, block) in ids) replace(targetBlock(targetFont(font), block), block, "glyphs")
                for (glyph in (block["glyphs"] as List<*>).map { it as Map<*, *> }.filter { glyphId(id, it) in ids }) {
                    val glyphs = targetBlock(targetFont(font), block)["glyphs"] as MutableList<Any?>
                    val code = (glyph["code"] as String).toInt(16)
                    val index = glyphs.indexOfFirst { ((it as Map<*, *>)["code"] as String).toInt(16) > code }
                    glyphs.add(if (index < 0) glyphs.size else index, glyph)
                }
            }
        }

        val kept = parts(source).keys
        for (font in fonts.map { it as Map<*, *> }) {
            val id = font["font"] as String
            removeWhere(font["blocks"] as MutableList<Any?>) {
                val block = it as Map<*, *>
                blockId(id, block) in ids && blockId(id, block) !in kept && (block["glyphs"] as List<*>?).isNullOrEmpty()
            }
        }
        removeWhere(fonts) {
            val font = it as Map<*, *>
            "font ${font["font"]}" in ids && "font ${font["font"]}" !in kept && (font["blocks"] as List<*>).isEmpty()
        }
    }

    /** The parts of `glyphs.json` by the id of the draft they belong to. */
    private fun parts(root: Map<*, *>?): Map<String, Part> {
        val parts = linkedMapOf<String, Part>()

        fun add(
            id: String,
            label: Any?,
            value: Any?,
        ) {
            parts.getOrPut(id) { Part(label.toString()) }.content += JsonOutput.toJson(value)
        }
        for (font in root?.let(NewTextures::fonts).orEmpty()) {
            val id = font["font"] as String
            add("font $id", id, font - "blocks")
            for (block in (font["blocks"] as List<*>).map { it as Map<*, *> }) {
                val texture = block["texture"] as String?
                if (texture != null) {
                    add(texture, block["title"], listOf(id, block))
                    continue
                }
                add(blockId(id, block), block["title"], listOf(id, block - "glyphs"))
                for (glyph in (block["glyphs"] as List<*>?).orEmpty().map { it as Map<*, *> }) {
                    add(glyphId(id, glyph), glyph["name"] ?: glyph["constant"] ?: glyph["code"], listOf(id, block["range"], glyph))
                }
            }
        }
        return parts
    }

    private fun blockId(
        font: String,
        block: Map<*, *>,
    ) = blockId(font, block["range"] ?: block["title"])

    /** The id of the draft of a block's own fields: its font's id and its range, or its title where it has none. */
    internal fun blockId(
        font: String,
        key: Any?,
    ) = "block $font $key"

    private fun glyphId(
        font: String,
        glyph: Map<*, *>,
    ) = glyph["texture"] as String? ?: "$font ${glyph["code"]}"

    /** The id of the draft that the changed file [path] belongs to: its texture's, or its own path. */
    private fun idOf(
        pack: File,
        path: String,
    ): String {
        Regex(
            """src/assets/([^/]+)/textures/(.+\.png)(\.mcmeta)?""",
        ).matchEntire(path)?.let { return "${it.groupValues[1]}:${it.groupValues[2]}" }
        Regex(
            """art/assets/([^/]+)/textures/(.+)\.aseprite""",
        ).matchEntire(path)?.let { return "${it.groupValues[1]}:${it.groupValues[2]}.png" }
        val file = pack.resolve(path).takeIf { it.isFile } ?: return path
        val json = runCatching { JsonSlurper().parse(file) as? Map<*, *> }.getOrNull() ?: return path
        Regex("""src/assets/([^/]+)/models/.+\.json""").matchEntire(path)?.let {
            val textures = json["textures"] as? Map<*, *>
            val texture = (textures?.get("layer0") ?: textures?.values?.firstOrNull()) as? String ?: return path
            return (if (':' in texture) texture else "minecraft:$texture") + ".png"
        }
        if (Regex("""src/assets/[^/]+/items/.+\.json""").matches(path)) {
            return PackModel.itemTexture(json["model"] as? Map<*, *>, pack.resolve("src/assets")) ?: path
        }
        return path
    }

    /** The name of a draft that no entry names: its texture's or its file's. */
    private fun label(id: String): String = id.substringAfterLast('/').substringAfterLast(':').removeSuffix(".png")

    /** Whether the files and entries of the draft [id] in [pack] equal those at [ref]. */
    private fun same(
        pack: File,
        ref: String,
        id: String,
        files: List<String>,
        work: Map<String, Part>,
    ): Boolean {
        val there = runCatching { parts(glyphs(pack, ref))[id]?.content }.getOrElse { return false }
        if (there != work[id]?.content) return false
        return files.all { path ->
            val blob = runCatching { git(pack, listOf("rev-parse", "$ref:./$path")).trim() }.getOrNull()
            val file = pack.resolve(path)
            if (!file.exists()) blob == null else blob == git(pack, listOf("hash-object", "--", path)).trim()
        }
    }

    private fun copy(
        from: Map<*, *>,
        list: String,
    ): OxfmtJson.Object =
        OxfmtJson.Object().also { copy ->
            from.forEach { (key, value) -> copy[key as String] = if (key == list) OxfmtJson.Array() else value }
        }

    /** Gives [target] the fields of [from] in their order, but keeps its own [list]. */
    private fun replace(
        target: OxfmtJson.Object,
        from: Map<*, *>,
        list: String,
    ) {
        val kept = target[list]
        target.clear()
        from.forEach { (key, value) -> target[key as String] = if (key == list) kept else value }
    }

    private fun place(
        blocks: MutableList<Any?>,
        block: Map<*, *>,
    ) {
        if (block["range"] == null) blocks += block else NewTextures.place(blocks, block)
    }

    private fun removeWhere(
        list: MutableList<Any?>,
        predicate: (Any?) -> Boolean,
    ) {
        for (i in list.indices.reversed()) if (predicate(list[i])) list.removeAt(i)
    }
}
