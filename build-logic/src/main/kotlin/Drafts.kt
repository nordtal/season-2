package eu.nordtal.season.build

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File

/**
 * The drafts of a clone's pack: changes against the last commit that no pull request holds yet. A draft is one texture
 * with its source, `.mcmeta`, model, item definition, entries in `glyphs.json` and the [Texts] that belong to it; a
 * font's or block's fields, a text that belongs to no asset and any other file are drafts of their own.
 */
object Drafts {
    private const val GLYPHS = "glyphs.json"
    private const val REVIEWS = "nordtal-designer/reviews.json"
    private const val BASES = "nordtal-designer/bases.json"
    private const val MAIN = "origin/main"

    /**
     * One draft. [id] is its texture's id, `font <font>`, `block <font> <range>`, `text <key>` or a file's path. [state]
     * is `added`, `changed` or `deleted`, [files] its files except the language files, whose changed [keys] it holds.
     * [review] is the pull request that holds an older version of it, and [conflict] what main changed too.
     */
    data class Draft(
        val id: String,
        val label: String,
        val state: String,
        val files: List<String>,
        val review: Int?,
        val conflict: Conflict? = null,
        val keys: List<String> = emptyList(),
    )

    /**
     * What the clone and main both changed of one draft since its base: the [files], with `glyphs.json` for an entry
     * that one side deleted and the other changed, and the [fields] of its entries and texts.
     */
    data class Conflict(
        val files: List<String>,
        val fields: List<Field>,
    )

    /** A field of an entry that the clone and main changed each its own way, with each side's value as JSON, or null. */
    data class Field(
        val name: String,
        val clone: String?,
        val main: String?,
    )

    /**
     * A pull request that a publish opened: its [number], [url] and [branch], the [drafts] it holds and the [commit] that
     * was pushed last. [decision], [checks] and [comments] are what the last sync read of its review.
     */
    data class Review(
        val number: Int,
        val url: String,
        val branch: String,
        val drafts: List<String>,
        val commit: String,
        val decision: String? = null,
        val checks: String? = null,
        val comments: List<Comment> = emptyList(),
    )

    /** A review comment by [author], on the file [path] where it is a line comment, and its [url] on GitHub. */
    data class Comment(
        val author: String,
        val body: String,
        val path: String?,
        val url: String,
    )

    /** The entries of one draft in `glyphs.json` by their key, and the name the designer knows it by. */
    private class Part(
        val label: String,
    ) {
        val entries = linkedMapOf<String, Entry>()
        val content get() = entries.values.map { it.json }
    }

    /**
     * One entry of `glyphs.json`: a font's, a block's, a sheet's or a glyph's [fields], as [json] with its place, in
     * [font] and, for a glyph, [block]. [name] tells it apart from the other entries of its draft.
     */
    internal class Entry(
        val kind: String,
        val name: String,
        val font: Map<*, *>,
        val block: Map<*, *>?,
        val fields: Map<*, *>,
        val json: String,
    )

    /** A three-way merge of one draft's entries: the [entries] it keeps, the [fields] both sides changed, and [whole]. */
    internal class Merge(
        val entries: List<Entry>,
        val fields: List<Field>,
        val whole: Boolean,
    )

    /**
     * The drafts of [pack], sorted by id, each with its conflict with main. A change that equals its version in a pull
     * request or on main is no draft.
     */
    fun list(pack: File): List<Draft> {
        val head = glyphs(pack, "HEAD")
        val work = pack.resolve(GLYPHS).takeIf { it.isFile }?.let { OxfmtJson.read(it) as Map<*, *> }
        val headParts = parts(head)
        val workParts = parts(work)
        val ids = sortedMapOf<String, MutableList<String>>()
        changedIds(head, work).forEach { ids.getOrPut(it) { mutableListOf() } }
        for (path in changedFiles(pack) - GLYPHS) {
            if (!Texts.isText(path)) ids.getOrPut(idOf(pack, path)) { mutableListOf() } += path
        }
        val headTexts = Texts.at(pack, "HEAD")
        val workTexts = Texts.work(pack)
        val owner = Texts.owner(pack)
        val keys = mutableMapOf<String, MutableSet<String>>()
        for (key in Texts.changedKeys(headTexts, workTexts)) {
            ids.getOrPut(owner(key)) { mutableListOf() }
            keys.getOrPut(owner(key)) { sortedSetOf() } += key
        }
        val committed = git(pack, listOf("ls-tree", "-r", "-z", "--name-only", "HEAD", "--", ".")).split('\u0000').toSet()
        val reviews = reviews(pack)
        val main = commit(pack, MAIN)
        val bases = bases(pack)
        val meeting by lazy { mergeBase(pack) }
        val trees = mutableMapOf<String, Map<String, String>>()
        val roots = mutableMapOf<String, Map<*, *>?>()
        val texts = mutableMapOf<String, TextFiles>()

        fun textsAt(ref: String) = texts.getOrPut(ref) { Texts.at(pack, ref) }
        return ids.mapNotNull { (id, files) ->
            files.sort()
            val own = keys[id].orEmpty()

            fun same(ref: String) = same(pack, ref, id, files, workParts) && Texts.same(textsAt(ref), workTexts, own)
            val review = reviews.lastOrNull { id in it.drafts }
            if (review != null && same(review.commit)) return@mapNotNull null
            if (main != null && same(main)) return@mapNotNull null
            val conflict =
                main?.let { m ->
                    val base = bases[id] ?: meeting ?: return@let null
                    if (base == m) return@let null

                    fun tree(ref: String) = trees.getOrPut(ref) { blobs(pack, ref) }
                    val clone = files.filter { pack.resolve(it).exists() }.let { blobs(pack, it) }
                    val changed = files.filter { tree(base)[it] != tree(m)[it] && clone[it] != tree(m)[it] }
                    val merge =
                        merge(roots.getOrPut(base) { glyphs(pack, base) }, work, roots.getOrPut(m) { glyphs(pack, m) }, id)
                    val fields = if (own.isEmpty()) emptyList() else Texts.merge(textsAt(base), workTexts, textsAt(m), own).second
                    Conflict(changed + listOfNotNull(GLYPHS.takeIf { merge.whole }), merge.fields + fields)
                        .takeIf { it.files.isNotEmpty() || it.fields.isNotEmpty() }
                }
            // An asset of which only texts changed is still there
            val texted = files.isEmpty() && id !in headParts && id !in workParts && Texts.ownKey(id) == null
            val before = texted || id in headParts || files.any { it in committed } || own.any { key -> headTexts.values.any { key in it } }
            val after =
                texted || id in workParts || files.any { pack.resolve(it).exists() } || own.any { key -> workTexts.values.any { key in it } }
            Draft(
                id,
                workParts[id]?.label ?: headParts[id]?.label ?: Texts.ownKey(id) ?: label(id),
                if (!before) {
                    "added"
                } else if (!after) {
                    "deleted"
                } else {
                    "changed"
                },
                files,
                review?.number,
                conflict,
                own.toList(),
            )
        }
    }

    /** The ids of the drafts of [pack], which [PackModel] marks. */
    fun ids(pack: File): Set<String> = list(pack).map { it.id }.toSet()

    /** The changed files of [pack] that belong to the drafts [ids], relative to the pack, without the language files. */
    internal fun files(
        pack: File,
        ids: Set<String>,
    ): List<String> = (changedFiles(pack) - GLYPHS).filter { !Texts.isText(it) && idOf(pack, it) in ids }

    /**
     * Restores the files, entries and texts of the draft [id] in [pack] to the last commit, or to its pull request when
     * one holds it, forgets its base and returns the files it wrote or deleted.
     */
    fun discard(
        pack: File,
        id: String,
    ): List<File> {
        val draft = list(pack).firstOrNull { it.id == id } ?: throw IllegalArgumentException("$id is no draft.")
        val ref = draft.review?.let { number -> "origin/${reviews(pack).last { it.number == number }.branch}" } ?: "HEAD"
        val files = restore(pack, ref, draft.files)
        val source = glyphs(pack, ref)
        val edited =
            if (id in changedIds(source, OxfmtJson.read(pack.resolve(GLYPHS)) as Map<*, *>)) {
                NewTextures.edit(pack, null, 0, 0) { apply(it, source, setOf(id)) }
            } else {
                emptyList()
            }
        val texts = if (draft.keys.isEmpty()) emptyList() else Texts.put(pack, Texts.at(pack, ref), draft.keys.toSet())
        forget(pack, setOf(id))
        return files + edited + texts
    }

    /** Writes [files] of [pack] as [ref] holds them, or deletes those it lacks, and returns them. */
    private fun restore(
        pack: File,
        ref: String,
        files: List<String>,
    ): List<File> {
        val kept = git(pack, listOf("ls-tree", "-r", "-z", "--name-only", ref, "--", ".")).split('\u0000').toSet()
        val (restored, removed) = files.partition { it in kept }
        if (restored.isNotEmpty()) git(pack, listOf("restore", "--source=$ref", "--worktree", "--") + restored)
        if (removed.isNotEmpty()) AtomicFiles.write(removed.map { pack.resolve(it) }.filter { it.exists() }.associateWith { null })
        return files.map { pack.resolve(it) }
    }

    /**
     * Ends the conflict of the draft [id] with main: [keep] `clone` or `main` chooses the version of every file of it,
     * [fields] the version of each field that both changed. Main's version is the draft's new base, and the files it
     * wrote are returned. With `main`, the clone's version of each file first goes under `build/conflict/yours/`.
     */
    fun resolve(
        pack: File,
        id: String,
        keep: String?,
        fields: Map<String, String>,
    ): List<File> {
        val draft = list(pack).firstOrNull { it.id == id } ?: throw IllegalArgumentException("$id is no draft.")
        val conflict = draft.conflict ?: throw IllegalArgumentException("${draft.label} has no conflict with main.")
        if (conflict.files.isNotEmpty() && keep != "clone" && keep != "main") {
            throw FieldRefusal("keep", "Set -Pkeep to clone or main, the version of ${conflict.files.joinToString()} to keep.")
        }
        val open = conflict.fields.map { it.name }.filter { fields[it] != "clone" && fields[it] != "main" }
        if (open.isNotEmpty()) throw FieldRefusal("fields", "Choose clone or main in -Pfields for ${open.joinToString()}.")
        val main = commit(pack, MAIN)!!
        val base = bases(pack)[id] ?: mergeBase(pack)
        val work = OxfmtJson.read(pack.resolve(GLYPHS)) as Map<*, *>
        val merge = merge(base?.let { glyphs(pack, it) }, work, glyphs(pack, main), id, keep, fields)
        val edited = NewTextures.edit(pack, null, 0, 0) { write(it, merge, id) }
        val keys = draft.keys.toSet()
        val texts =
            if (keys.isEmpty()) {
                emptyList()
            } else {
                val merged = Texts.merge(base?.let { Texts.at(pack, it) }.orEmpty(), Texts.work(pack), Texts.at(pack, main), keys, fields)
                Texts.put(pack, merged.first, keys)
            }
        val written = if (keep == "main") keepYours(pack, draft.files) + restore(pack, main, draft.files) else emptyList()
        writeBases(pack, bases(pack) + (id to main))
        return edited + texts + written
    }

    /** Copies each of [files] that [pack] holds to `build/conflict/yours/` and returns the copies. */
    private fun keepYours(
        pack: File,
        files: List<String>,
    ): List<File> {
        files.forEach { pack.resolve("build/conflict/yours/$it").delete() }
        return files.filter { pack.resolve(it).isFile }.map { pack.resolve(it).copyTo(pack.resolve("build/conflict/yours/$it")) }
    }

    /**
     * Writes main's version of each file of the conflict of the draft [id] under `build/conflict/`, after it removes what an
     * earlier read left there of the draft's files, and returns them.
     */
    fun readConflict(
        pack: File,
        id: String,
    ): List<File> {
        val draft = list(pack).firstOrNull { it.id == id } ?: throw IllegalArgumentException("$id is no draft.")
        val conflict = draft.conflict ?: throw IllegalArgumentException("${draft.label} has no conflict with main.")
        draft.files.forEach { pack.resolve("build/conflict/$it").delete() }
        val tree = blobs(pack, MAIN)
        return (conflict.files - GLYPHS).filter { it in tree }.map { path ->
            pack.resolve("build/conflict/$path").also {
                it.parentFile.mkdirs()
                git(pack, listOf("cat-file", "blob", tree.getValue(path)), it)
            }
        }
    }

    /** The commit that [ref] names in the clone of [pack], or null where it names none. */
    private fun commit(
        pack: File,
        ref: String,
    ): String? = runCatching { git(pack, listOf("rev-parse", "-q", "--verify", "$ref^{commit}")).trim() }.getOrNull()

    /** The commit where the clone's history meets main's, or null without main. */
    private fun mergeBase(pack: File): String? = runCatching { git(pack, listOf("merge-base", "HEAD", MAIN)).trim() }.getOrNull()

    /** The commit that the draft [id] in [pack] last took main's changes from, or null without main. */
    internal fun base(
        pack: File,
        id: String,
    ): String? = bases(pack)[id] ?: mergeBase(pack)

    /** The blob of each file of [pack] at [ref] by its path, or of each of the [files] as they are. */
    private fun blobs(
        pack: File,
        ref: String,
    ): Map<String, String> =
        git(pack, listOf("ls-tree", "-r", "-z", ref, "--", "."))
            .split('\u0000')
            .filter { it.isNotBlank() }
            .associate { it.substringAfter('\t') to it.substringBefore('\t').split(' ')[2] }

    private fun blobs(
        pack: File,
        files: List<String>,
    ): Map<String, String> = if (files.isEmpty()) emptyMap() else files.zip(git(pack, listOf("hash-object", "--") + files).lines()).toMap()

    /** The base of each draft whose base is no longer where the clone meets main. */
    private fun bases(pack: File): Map<String, String> {
        val file = record(pack, BASES)
        if (!file.isFile) return emptyMap()
        return (JsonSlurper().parse(file) as Map<*, *>).entries.associate { (id, commit) -> id.toString() to commit.toString() }
    }

    /** Forgets the bases of the drafts [ids], so each takes main's changes from where the clone meets main again. */
    internal fun forget(
        pack: File,
        ids: Set<String>,
    ) = writeBases(pack, bases(pack) - ids)

    /** Makes [commit] the base of the drafts [ids]. */
    internal fun rebase(
        pack: File,
        ids: Set<String>,
        commit: String,
    ) = writeBases(pack, bases(pack) + ids.associateWith { commit })

    internal fun writeBases(
        pack: File,
        bases: Map<String, String>,
    ) {
        val file = record(pack, BASES)
        file.parentFile.mkdirs()
        file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(bases.toSortedMap())) + "\n")
    }

    /**
     * Writes the files, entries and texts of [drafts] as [pack] holds them into [target], the pack of another work tree.
     * With [merge], each entry and text keeps what only [target] changed since the draft's base.
     */
    internal fun copy(
        pack: File,
        target: File,
        drafts: List<Draft>,
        merge: Boolean = false,
    ) {
        AtomicFiles.write(
            drafts
                .flatMap { it.files }
                .associate { path ->
                    val file = pack.resolve(path)
                    target.resolve(path) to if (file.exists()) file.readBytes() else null
                }.filter { (file, bytes) -> bytes != null || file.exists() },
        )
        val texts = Texts.work(pack)
        for (draft in drafts.filter { it.keys.isNotEmpty() }) {
            val keys = draft.keys.toSet()
            if (!merge) {
                Texts.put(target, texts, keys)
                continue
            }
            val main = Texts.at(target, "HEAD")
            Texts.put(target, Texts.merge(base(pack, draft.id)?.let { Texts.at(pack, it) } ?: main, texts, main, keys).first, keys)
        }
        val work = pack.resolve(GLYPHS).takeIf { it.isFile }?.let { OxfmtJson.read(it) as Map<*, *> }
        val main = glyphs(target, "HEAD")
        val ids = changedIds(main, work).intersect(drafts.map { it.id }.toSet())
        if (ids.isEmpty()) return
        NewTextures.edit(target, null, 0, 0) { root ->
            if (!merge) return@edit apply(root, work, ids)
            ids.forEach { id -> write(root, merge(base(pack, id)?.let { glyphs(pack, it) } ?: main, work, main, id), id) }
        }
    }

    /**
     * Merges the entries of the draft [id] in [clone] and [main], two versions of `glyphs.json`, since [base]: a side's
     * change wins where the other made none. A field both changed takes the side [fields] names, else the clone's; an
     * entry one deleted and the other changed takes the side [keep] names, else the clone's.
     */
    internal fun merge(
        base: Map<*, *>?,
        clone: Map<*, *>?,
        main: Map<*, *>?,
        id: String,
        keep: String? = null,
        fields: Map<String, String> = emptyMap(),
    ): Merge {
        val (b, c, m) = listOf(base, clone, main).map { parts(it)[id]?.entries.orEmpty() }
        val keys = (c.keys + m.keys + b.keys).distinct()
        val entries = mutableListOf<Entry>()
        val both = mutableListOf<Field>()
        var whole = false
        for (key in keys) {
            val (was, mine, theirs) = Triple(b[key], c[key], m[key])
            val kept =
                when {
                    mine?.json == theirs?.json || theirs?.json == was?.json -> {
                        mine
                    }

                    mine?.json == was?.json -> {
                        theirs
                    }

                    mine != null && theirs != null -> {
                        merged(
                            was,
                            mine,
                            theirs,
                            if (keys.size > 1 &&
                                mine.name.isNotEmpty()
                            ) {
                                "${mine.name} "
                            } else {
                                ""
                            },
                            fields,
                            both,
                        )
                    }

                    else -> {
                        (if (keep == "main") theirs else mine).also { whole = true }
                    }
                }
            kept?.let(entries::add)
        }
        return Merge(entries, both, whole)
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
        val file = record(pack, REVIEWS)
        if (!file.isFile) return emptyList()
        return (JsonSlurper().parse(file) as List<*>).map { it as Map<*, *> }.map {
            Review(
                (it["number"] as Number).toInt(),
                it["url"] as String,
                it["branch"] as String,
                (it["drafts"] as List<*>).map(Any?::toString),
                it["commit"] as String,
                it["decision"] as String?,
                it["checks"] as String?,
                (it["comments"] as List<*>?).orEmpty().map { it as Map<*, *> }.map { comment ->
                    Comment(comment["author"] as String, comment["body"] as String, comment["path"] as String?, comment["url"] as String)
                },
            )
        }
    }

    internal fun writeReviews(
        pack: File,
        reviews: List<Review>,
    ) {
        val file = record(pack, REVIEWS)
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
                            "decision" to it.decision,
                            "checks" to it.checks,
                            "comments" to
                                it.comments.map { comment ->
                                    linkedMapOf(
                                        "author" to comment.author,
                                        "body" to comment.body,
                                        "path" to comment.path,
                                        "url" to comment.url,
                                    )
                                },
                        )
                    },
                ),
            ) +
                "\n",
        )
    }

    /** The record [name] in the clone's git directory, such as that of the pull requests, so no commit ever holds it. */
    private fun record(
        pack: File,
        name: String,
    ): File = File(git(pack, listOf("rev-parse", "--git-path", name)).trim()).let { if (it.isAbsolute) it else pack.resolve(it) }

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

    /**
     * Merges the fields of [mine] and [theirs], both versions of one entry, since [was]. A field both changed is added
     * to [both] by its name after [prefix] and takes the side [fields] names, else the clone's. A glyph lies in the
     * block and font of the side whose code it takes.
     */
    private fun merged(
        was: Entry?,
        mine: Entry,
        theirs: Entry,
        prefix: String,
        fields: Map<String, String>,
        both: MutableList<Field>,
    ): Entry {
        val out = OxfmtJson.Object()
        var place = mine
        for (field in (mine.fields.keys + theirs.fields.keys).distinct()) {
            val (b, c, m) = listOf(was, mine, theirs).map { e -> e?.fields?.takeIf { field in it }?.let { JsonOutput.toJson(it[field]) } }
            val name = prefix + field
            val side =
                when {
                    c == m || m == b -> mine
                    c == b -> theirs
                    else -> (if (fields[name] == "main") theirs else mine).also { both += Field(name, c, m) }
                }
            if (field in side.fields) out[field as String] = side.fields[field]
            if (field == "code") place = side
        }
        return Entry(mine.kind, mine.name, place.font, place.block, out, mine.json)
    }

    /** Puts the entries of [merge] in place of those of the draft [id] in [target], a version of `glyphs.json`. */
    internal fun write(
        target: OxfmtJson.Object,
        merge: Merge,
        id: String,
    ) {
        val fonts = mutableListOf<OxfmtJson.Object>()

        fun font(from: Map<*, *>) = fonts.firstOrNull { it["font"] == from["font"] } ?: copy(from, "blocks").also { fonts += it }

        fun blocks(font: Map<*, *>) = font["blocks"] as MutableList<Any?>
        for (entry in merge.entries) {
            val font = font(entry.font)
            when (entry.kind) {
                "font" -> {
                    replace(font, ordered(entry.font, entry.fields, "blocks"), "blocks")
                }

                "sheet" -> {
                    blocks(font) += entry.fields
                }

                "block" -> {
                    blocks(font) += copy(ordered(entry.block!!, entry.fields, "glyphs"), "glyphs")
                }

                else -> {
                    val block =
                        blocks(font).firstOrNull { blockId("", it as Map<*, *>) == blockId("", entry.block!!) } as OxfmtJson.Object?
                            ?: copy(entry.block!!, "glyphs").also { blocks(font) += it }
                    (block["glyphs"] as MutableList<Any?>) += entry.fields
                }
            }
        }
        apply(target, mapOf("fonts" to fonts), setOf(id))
    }

    /** The [fields] in the order of [original], with its [list] in its place. */
    private fun ordered(
        original: Map<*, *>,
        fields: Map<*, *>,
        list: String,
    ): Map<*, *> = (original.keys.filter { it == list || it in fields } + fields.keys).distinct().associateWith { fields[it] }

    /** The parts of `glyphs.json` by the id of the draft they belong to. */
    private fun parts(root: Map<*, *>?): Map<String, Part> {
        val parts = linkedMapOf<String, Part>()

        fun add(
            id: String,
            label: Any?,
            key: String,
            entry: Entry,
        ) {
            val entries = parts.getOrPut(id) { Part(label.toString()) }.entries
            entries[generateSequence(key) { "$it+" }.first { it !in entries }] = entry
        }
        for (font in root?.let(NewTextures::fonts).orEmpty()) {
            val id = font["font"] as String
            val fields = font - "blocks"
            add("font $id", id, "font", Entry("font", "", font, null, fields, JsonOutput.toJson(fields)))
            for (block in (font["blocks"] as List<*>).map { it as Map<*, *> }) {
                val texture = block["texture"] as String?
                if (texture != null) {
                    add(texture, block["title"], "sheet $id", Entry("sheet", "", font, null, block, JsonOutput.toJson(listOf(id, block))))
                    continue
                }
                val own = block - "glyphs"
                add(blockId(id, block), block["title"], "block", Entry("block", "", font, block, own, JsonOutput.toJson(listOf(id, own))))
                for (glyph in (block["glyphs"] as List<*>?).orEmpty().map { it as Map<*, *> }) {
                    add(
                        glyphId(id, glyph),
                        glyph["name"] ?: glyph["constant"] ?: glyph["code"],
                        "glyph $id",
                        Entry("glyph", id, font, block, glyph, JsonOutput.toJson(listOf(id, block["range"], glyph))),
                    )
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
    internal fun idOf(
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
