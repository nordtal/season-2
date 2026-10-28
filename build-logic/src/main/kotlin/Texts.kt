package eu.nordtal.season.build

import java.io.File

/**
 * The texts of the pack: the keys of its language files under `src/assets/<namespace>/lang/`. A key belongs to an
 * asset of the pack when it is that asset's translation key or starts with it and a dot: `item.<namespace>.<path>` for
 * an item definition, `block.<namespace>.<path>` for a texture under `textures/block/`, with each `/` of the path as
 * `.`. Such a key is part of its asset's draft. Any other key is the draft `text <key>` of its own.
 */
object Texts {
    private val FILE = Regex("""src/assets/([^/]+)/lang/([^/]+)\.json""")
    private val ITEM = Regex("""src/assets/([^/]+)/items/(.+)\.json""")
    private val BLOCK = Regex("""src/assets/([^/]+)/textures/block/(.+)\.png""")
    private const val OWN = "text "

    /** The folder whose language file [edit] writes a text into that no file of its language holds yet. */
    const val NEW_FILES = "src/assets/minecraft/lang"

    /** Each language file's keys and values, by the file's path relative to the pack. */
    typealias Files = Map<String, Map<String, String>>

    /** Whether [path], relative to the pack, is a language file. */
    fun isText(path: String): Boolean = FILE.matches(path)

    /** The language of the file [path], such as `en_us`. */
    fun language(path: String): String = FILE.matchEntire(path)!!.groupValues[2]

    /** The language files of [pack] as they are. */
    fun work(pack: File): Files =
        pack
            .resolve("src/assets")
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(pack).invariantSeparatorsPath }
            .filter(::isText)
            .sorted()
            .associateWith { values(pack.resolve(it).readText()) }

    /** The language files of [pack] at [ref], or none where the ref is missing. */
    fun at(
        pack: File,
        ref: String,
    ): Files {
        val paths =
            runCatching { git(pack, listOf("ls-tree", "-r", "-z", "--name-only", ref, "--", "src/assets")) }
                .getOrElse { return emptyMap() }
                .split('\u0000')
                .filter(::isText)
                .sorted()
        return paths.associateWith { values(git(pack, listOf("show", "$ref:./$it"))) }
    }

    private fun values(text: String): Map<String, String> =
        (runCatching { OxfmtJson.read(text) }.getOrNull() as? Map<*, *>).orEmpty().entries.associate { (key, value) ->
            key.toString() to value.toString()
        }

    /** The keys whose value in some language file differs between [before] and [after]. */
    fun changedKeys(
        before: Files,
        after: Files,
    ): Set<String> =
        (before.keys + after.keys)
            .flatMap { path ->
                val (a, b) = before[path].orEmpty() to after[path].orEmpty()
                (a.keys + b.keys).filter { a[it] != b[it] }
            }.toSet()

    /**
     * The id of the draft that a key is part of in [pack]: its asset's as [Drafts] names it, where the work tree or
     * [ref] holds the asset, else `text <key>`. The longest translation key wins where several match.
     */
    fun owner(
        pack: File,
        ref: String = "HEAD",
    ): (String) -> String {
        val committed = runCatching { git(pack, listOf("ls-tree", "-r", "-z", "--name-only", ref, "--", "src/assets")) }.getOrDefault("")
        val present =
            pack
                .resolve("src/assets")
                .walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(pack).invariantSeparatorsPath }
        val assets = linkedMapOf<String, String>()
        for (path in (committed.split('\u0000').asSequence() + present).toSortedSet()) {
            ITEM.matchEntire(path)?.let { assets["item.${it.groupValues[1]}.${it.groupValues[2].replace('/', '.')}"] = path }
            BLOCK.matchEntire(path)?.let { assets["block.${it.groupValues[1]}.${it.groupValues[2].replace('/', '.')}"] = path }
        }
        val ids = mutableMapOf<String, String>()
        return { key ->
            val asset = assets.keys.filter { key == it || key.startsWith("$it.") }.maxByOrNull { it.length }
            asset?.let { ids.getOrPut(it) { Drafts.idOf(pack, assets.getValue(it)) } } ?: "$OWN$key"
        }
    }

    /** The key that the draft [id] of a text of its own is, or null for the draft of an asset. */
    fun ownKey(id: String): String? = id.removePrefix(OWN).takeIf { id.startsWith(OWN) }

    /** Whether each of [keys] has the same value in each language file of [a] and [b]. */
    fun same(
        a: Files,
        b: Files,
        keys: Set<String>,
    ): Boolean = (a.keys + b.keys).all { path -> keys.all { a[path]?.get(it) == b[path]?.get(it) } }

    /**
     * Writes the [keys] into the language files of [pack] as [source] holds them: a key that [source] lacks in a file
     * leaves it. A file that ends up empty and that [source] lacks goes. Returns the files it wrote or deleted.
     */
    fun put(
        pack: File,
        source: Files,
        keys: Set<String>,
    ): List<File> {
        val paths = (work(pack).keys + source.keys).toSortedSet()
        val contents = linkedMapOf<File, ByteArray?>()
        for (path in paths) {
            val file = pack.resolve(path)
            val root = (if (file.isFile) OxfmtJson.read(file) as? OxfmtJson.Object else null) ?: OxfmtJson.Object()
            val before = root.toMap()
            val wanted = source[path].orEmpty()
            for (key in keys) {
                val value = wanted[key]
                if (value == null) root.remove(key) else if (root[key] != value) root[key] = value
            }
            if (root.toMap() == before) continue
            contents[file] = if (root.isEmpty() && path !in source) null else OxfmtJson.format(root).toByteArray()
        }
        AtomicFiles.write(contents.filter { (file, bytes) -> bytes != null || file.exists() })
        return contents.keys.toList()
    }

    /**
     * Merges the [keys] of [clone] and [main] since [base], each file by itself: a side's change wins where the other
     * made none. A value that both changed each their own way is a [Drafts.Field] named `<language> <key>`, which takes
     * the side [fields] names, else the clone's. Returns the merged files and those fields.
     */
    fun merge(
        base: Files,
        clone: Files,
        main: Files,
        keys: Set<String>,
        fields: Map<String, String> = emptyMap(),
    ): Pair<Files, List<Drafts.Field>> {
        val both = mutableListOf<Drafts.Field>()
        val merged =
            (base.keys + clone.keys + main.keys).toSortedSet().associateWith { path ->
                val (b, c, m) = Triple(base[path].orEmpty(), clone[path].orEmpty(), main[path].orEmpty())
                val out = linkedMapOf<String, String>()
                for (key in keys) {
                    val kept =
                        when {
                            c[key] == m[key] || m[key] == b[key] -> c[key]
                            c[key] == b[key] -> m[key]
                            else -> {
                                val name = "${language(path)} $key"
                                both += Drafts.Field(name, c[key]?.let(::quoted), m[key]?.let(::quoted))
                                if (fields[name] == "main") m[key] else c[key]
                            }
                        }
                    kept?.let { out[key] = it }
                }
                out
            }
        return merged to both
    }

    private fun quoted(value: String): String = OxfmtJson.format(value).trimEnd()

    /**
     * Sets the text [key] in the language [language] to [value], or removes it from every language file with [delete].
     * A key that the language holds keeps its file. A new one goes into that language's file under [NEW_FILES].
     */
    fun edit(
        pack: File,
        key: String,
        language: String?,
        value: String?,
        delete: Boolean,
    ): List<File> {
        if (!Regex("""[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)*""").matches(key)) {
            throw FieldRefusal("key", "-Pkey=$key is no translation key: words of letters, digits, _ and - with dots between them.")
        }
        val work = work(pack)
        if (delete) {
            if (work.values.none { key in it }) throw FieldRefusal("key", "No language file holds $key.")
            return put(pack, work.mapValues { (_, values) -> values - key }, setOf(key))
        }
        if (language == null || !Regex("""[a-z]{2,3}_[a-z]{2,3}""").matches(language)) {
            throw FieldRefusal("language", "Set -Planguage to a language such as en_us.")
        }
        val value = value ?: throw FieldRefusal("value", "Set -Pvalue.")
        val path = work.keys.firstOrNull { language(it) == language && key in work.getValue(it) } ?: "$NEW_FILES/$language.json"
        return put(pack, work + (path to work[path].orEmpty() + (key to value)), setOf(key))
    }
}
