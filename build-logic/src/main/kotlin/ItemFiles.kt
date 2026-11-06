package eu.nordtal.season.build

import groovy.json.JsonSlurper
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * Reads and writes the item definitions of the pack at [pack], and imports Blockbench models into it. A reference
 * resolves in the pack's `src/assets/` first and then in the client jar, which [jar] opens when one is needed.
 */
class ItemFiles(
    private val pack: File,
    private val jar: () -> File,
) : AutoCloseable {
    private val assets = pack.resolve("src/assets")
    private var opened: ZipFile? = null
    private val zip get() = opened ?: ZipFile(jar()).also { opened = it }

    override fun close() {
        opened?.close()
    }

    /** The bytes of [path] under `assets/`, from the pack or the client jar, or null when neither has it. */
    fun read(path: String): ByteArray? =
        assets.resolve(path).takeIf { it.isFile }?.readBytes()
            ?: zip.getEntry("assets/$path")?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }

    /** The sprites that an atlas generates rather than reads from a PNG: `missingno` and each paletted permutation. */
    private val generated: Set<String> by lazy {
        val atlases =
            zip
                .entries()
                .asSequence()
                .filter { it.name.matches(ATLAS) }
                .map { entry -> zip.getInputStream(entry).use { it.readBytes() } } +
                assets
                    .walkTopDown()
                    .filter {
                        it.isFile &&
                            "assets/${it.relativeTo(
                                assets,
                            ).invariantSeparatorsPath}".matches(ATLAS)
                    }.map { it.readBytes() }
        val permutations =
            atlases
                .flatMap { bytes -> ((JsonSlurper().parse(bytes) as Map<*, *>)["sources"] as List<*>).map { it as Map<*, *> } }
                .filter { it["type"] == "minecraft:paletted_permutations" || it["type"] == "paletted_permutations" }
                .flatMap { source ->
                    val separator = source["separator"] as String? ?: "_"
                    (source["textures"] as List<*>).flatMap { texture ->
                        (source["permutations"] as Map<*, *>).keys.map { key -> full("$texture$separator$key") }
                    }
                }
        (permutations + "minecraft:missingno").toSet()
    }

    /** Whether [texture] is a PNG in the pack or the client jar, or a sprite that an atlas generates. */
    fun sprite(texture: String): Boolean {
        val path = runCatching { Identifier.resource(texture, "textures", ".png") }.getOrNull() ?: return false
        return assets.resolve(path).isFile || full(texture) in generated || read(path) != null
    }

    private fun full(id: String) = if (':' in id) id else "minecraft:$id"

    /**
     * The definition of the item [id] as one map, the shape that `resource-pack/README.md` documents for `readItem`.
     * A texture is a draft when its id is among [drafts], the ids of the pack's [Drafts].
     */
    fun describe(
        id: String,
        drafts: Set<String>,
    ): Map<String, Any?> {
        val path = Identifier.resource(id, "items", ".json")
        val file = assets.resolve(path).takeIf { it.isFile }
        val bytes = read(path) ?: throw IllegalArgumentException("The item $id is in neither the pack nor the client jar.")
        val definition = OxfmtJson.read(bytes.decodeToString())
        return linkedMapOf(
            "item" to id,
            "source" to if (file != null) "pack" else "vanilla",
            "file" to file?.relativeTo(pack)?.invariantSeparatorsPath,
            "definition" to definition,
            "problems" to ItemDefinitions.problems(definition) + ItemDefinitions.missing(definition, ::read, ::sprite),
            "types" to ItemDefinitions.types,
            "models" to
                ItemDefinitions.models(definition).map {
                    val own = runCatching { assets.resolve(Identifier.resource(it, "models", ".json")).isFile }.getOrDefault(false)
                    linkedMapOf("model" to it, "pack" to own, "textures" to textures(it, drafts))
                },
            "states" to ItemStates.of(full(id), definition),
        )
    }

    /**
     * The textures of [model] and of its parents, each `{ key, texture }`, the child's for a key that both name. A texture
     * whose PNG the pack holds is described as [PackModel] describes an override, any other by its id alone.
     */
    private fun textures(
        model: String,
        drafts: Set<String>,
    ): List<Map<String, Any?>> =
        ItemDefinitions.textures(model, ::read, mutableListOf()).distinctBy { it.key }.map { (from, key, texture) ->
            val png = runCatching { Identifier.resource(texture, "textures", ".png") }.getOrNull()?.let { assets.resolve(it) }
            val id = "${full(texture)}.png"
            if (png?.isFile == true) {
                val described = PackModel.texture(id, pack, PackModel.Marks(drafts))
                linkedMapOf("key" to key, "own" to (from == model), "texture" to described) + PackModel.animation(png, pack, described)
            } else {
                linkedMapOf("key" to key, "own" to (from == model), "texture" to mapOf("id" to id))
            }
        }

    /** Writes [definition], the text of an item definition, as the item [id] in the pack, after every rule passed. */
    fun write(
        id: String,
        definition: String,
    ): File = store(id, OxfmtJson.read(definition), emptyMap()).last()

    /**
     * Writes [json] as the item [id] together with [pending], new files of the pack and null for each file it deletes, all
     * or none, after every rule passed with [pending] in place. Returns every file written or deleted.
     */
    private fun store(
        id: String,
        json: Any?,
        pending: Map<File, ByteArray?>,
    ): List<File> {
        val pendingOrRead = { path: String -> assets.resolve(path).let { if (it in pending) pending[it] else read(path) } }
        val pendingOrSprite = { texture: String ->
            val png = runCatching { assets.resolve(Identifier.resource(texture, "textures", ".png")) }.getOrNull()
            if (png != null && png in pending) pending[png] != null else sprite(texture)
        }
        val problems = ItemDefinitions.problems(json).ifEmpty { ItemDefinitions.missing(json, pendingOrRead, pendingOrSprite) }
        check(problems.isEmpty()) { "The item $id breaks ${problems.size} rule(s):\n" + problems.joinToString("\n") { "    $it" } }
        val file = assets.resolve(Identifier.resource(id, "items", ".json"))
        AtomicFiles.write(pending + (file to OxfmtJson.asset(json).toByteArray()))
        return pending.keys.toList() + file
    }

    /** The definition of the item [id], from the pack or the client jar */
    private fun definition(id: String): MutableMap<String, Any?> {
        val bytes =
            read(Identifier.resource(id, "items", ".json"))
                ?: throw IllegalArgumentException("The item $id is in neither the pack nor the client jar.")

        @Suppress("UNCHECKED_CAST")
        return OxfmtJson.read(bytes.decodeToString()) as? MutableMap<String, Any?>
            ?: throw IllegalArgumentException("The item $id is no JSON object.")
    }

    /**
     * Changes the item [id] at [at] with [ItemDefinitions.edit], where [fields] is the text of a JSON object, and writes it
     * after every rule passed. A vanilla item that the pack lacks is read from the client jar and written as an override.
     */
    fun edit(
        id: String,
        at: String,
        fields: String?,
        add: String?,
        remove: Boolean,
    ): File {
        val definition = definition(id)
        val changes =
            fields?.let {
                runCatching { OxfmtJson.read(it) }.getOrNull() as? Map<*, *>
                    ?: throw FieldRefusal("fields", "-Pfields=$it is no JSON object.")
            }

        @Suppress("UNCHECKED_CAST")
        require(ItemDefinitions.edit(definition, at, changes as Map<String, Any?>?, add, remove)) { "The item $id has nothing at $at." }
        return store(id, definition, emptyMap()).last()
    }

    /**
     * Imports [source], a Blockbench project or a model exported as JSON with its textures beside it, as the model [id]
     * that the item [item] draws at [at], in one write. Each texture goes next to the model's folder under `textures/`,
     * and the model drawn at [at] before goes as [unused] decides. Returns every file written or deleted.
     */
    fun import(
        source: File,
        id: String,
        item: String,
        at: String,
    ): List<File> {
        val (namespace, path) = Identifier.parse(id)
        val (model, images) =
            if (source.extension.equals("bbmodel", ignoreCase = true)) {
                Blockbench.convert(
                    OxfmtJson.read(source) as? Map<*, *> ?: throw IllegalArgumentException("${source.name} is no Blockbench project."),
                    source.name,
                )
            } else {
                exported(source)
            }
        val target = assets.resolve("$namespace/models/$path.json")
        val pending = linkedMapOf<File, ByteArray?>(target to ByteArray(0))
        val definition = definition(item)
        // An import onto the node that draws the model already writes it again, with the textures it names in the pack
        val node =
            at.split('/').filter { it.isNotEmpty() }.fold<String, Any?>(definition) { n, key ->
                when (n) {
                    is Map<*, *> -> n[key]
                    is List<*> -> key.toIntOrNull()?.let { n.getOrNull(it) }
                    else -> null
                }
            } as? Map<*, *>
        val drawn = node?.takeIf { it["type"] == "minecraft:model" || it["type"] == "model" }?.get("model")
        val again = drawn is String && full(drawn) == full(id) && target.isFile
        val owned = if (again) owned(target) else emptyMap()
        val replaceable = if (again) owned.keys + target else emptySet()

        @Suppress("UNCHECKED_CAST")
        val textures = model["textures"] as? MutableMap<String, Any?> ?: mutableMapOf()
        val claimed = mutableMapOf<File, String>()
        for ((key, value) in textures.entries.toList()) {
            val reference = value as? String ?: continue
            if (reference.startsWith("#")) continue
            val image = images[reference]
            val link = (image?.link ?: reference).let(::full)
            val linked = runCatching { Identifier.resource(link, "textures", ".png") }.getOrNull()
            if (image != null && linked != null && read(linked)?.let { samePixels(it, image.png) } == true) {
                // A texture that Blockbench left as it was stays the texture it came from
                textures[key] = link
                continue
            }
            val own = linked?.let { assets.resolve(it) }?.takeIf { it in owned }
            if (image != null && own != null) {
                textures[key] = link
                pending[own] = image.png
                val mcmeta = File(own.path + ".mcmeta")
                if (!mcmeta.isFile) image.mcmeta?.let { pending[mcmeta] = it }
                owned[own]?.let { pending[it] = null }
                continue
            }
            if (image == null) {
                val beside = name(reference) + ".png"
                require(sprite(reference)) {
                    "Texture $key of ${source.name} is $reference, which is neither beside it as $beside nor in the pack or the client jar."
                }
                continue
            }
            require('/' !in image.name && Identifier.problem("$namespace:${image.name}") == null) {
                "Texture $reference of ${source.name} cannot be imported under its name. Rename it in Blockbench to a name of only " +
                    "a-z, 0-9, _, - and . that does not end in ."
            }
            val texture = listOfNotNull(path.substringBeforeLast('/', "").ifEmpty { null }, image.name).joinToString("/")
            textures[key] = "$namespace:$texture"
            val png = assets.resolve("$namespace/textures/$texture.png")
            val other = claimed.putIfAbsent(png, reference)
            require(other == null || other == reference) {
                "Textures $other and $reference of ${source.name} would both be $namespace:$texture."
            }
            pending[png] = image.png
            image.mcmeta?.let { pending[File(png.path + ".mcmeta")] = it }
        }
        // A texture of its own that the model no longer names goes, with its .mcmeta and art/ source
        val kept =
            textures.values
                .filterIsInstance<String>()
                .filterNot { it.startsWith("#") }
                .mapNotNull { reference ->
                    runCatching { assets.resolve(Identifier.resource(full(reference), "textures", ".png")) }.getOrNull()
                }.toSet()
        for (png in owned.keys) {
            if (png in kept || png in pending) continue
            sources(png).forEach { pending[it] = null }
        }
        for (file in pending.keys) {
            if (file in replaceable || pending[file] == null) continue
            val relative = file.relativeTo(assets).invariantSeparatorsPath
            if (file.exists()) throw FieldRefusal("id", "${file.relativeTo(pack).invariantSeparatorsPath} exists already.")
            if (read(relative) != null) {
                throw FieldRefusal("id", "${relative.replaceFirst('/', ':')} is in the client jar, which the import would hide.")
            }
        }
        pending[target] = OxfmtJson.asset(model).toByteArray()
        val before = ItemDefinitions.models(definition).map(::full).toSet()
        require(ItemDefinitions.edit(definition, at, mapOf("type" to "minecraft:model", "model" to id), null, false)) {
            "The item $item has nothing at $at."
        }
        val replaced = before - ItemDefinitions.models(definition).map(::full).toSet()
        val file = assets.resolve(Identifier.resource(item, "items", ".json"))
        pending.putAll(unused(replaced, pending + (file to OxfmtJson.asset(definition).toByteArray())))
        return store(item, definition, pending)
    }

    /**
     * The PNGs of the pack outside `minecraft` that the model [file] names and nothing else of the pack names, each with
     * its `art/` source when it has one. An import of the model again writes them back in place, and the source goes,
     * since it holds the old pixels.
     */
    private fun owned(file: File): Map<File, File?> {
        val textures = ((OxfmtJson.read(file) as? Map<*, *>)?.get("textures") as? Map<*, *>).orEmpty().values.filterIsInstance<String>()
        val others = texts().filterKeys { it != file }.values
        return textures
            .filterNot { it.startsWith("#") }
            .map(::full)
            .filterNot { it.startsWith("minecraft:") }
            .filterNot { id -> others.any { names(it, id) } }
            .mapNotNull { runCatching { Identifier.resource(it, "textures", ".png") }.getOrNull() }
            .map { assets.resolve(it) }
            .filter { it.isFile }
            .associateWith { png ->
                val path = png.relativeTo(assets).invariantSeparatorsPath.removeSuffix(".png")
                pack.resolve("art/assets/$path.aseprite").takeIf { it.isFile }
            }
    }

    /**
     * Deletes the texture [id], such as `nordtal:item/wand` or `minecraft:block/stone.png`, from the pack: its PNG, its
     * `.mcmeta` and its `art/` source. Refused while a file of the pack names it, each listed as a path of [repository] and
     * a line, unless it overrides a texture of the client jar, which shows again. Returns every file deleted.
     */
    fun deleteTexture(
        id: String,
        repository: File,
    ): List<File> {
        val texture = full(id.removeSuffix(".png"))
        val png = assets.resolve(Identifier.resource(texture, "textures", ".png"))
        if (!png.isFile) throw FieldRefusal("texture", "$texture is no texture of the pack.")
        val relative = png.relativeTo(assets).invariantSeparatorsPath
        if (!texture.startsWith("minecraft:") || zip.getEntry("assets/$relative") == null) {
            val places =
                texts().flatMap { (file, text) ->
                    text
                        .lineSequence()
                        .withIndex()
                        .filter { (_, line) -> names(line, texture) }
                        .map { "${file.relativeTo(repository).invariantSeparatorsPath}:${it.index + 1}" }
                        .toList()
                }
            check(places.isEmpty()) { used(texture, places) }
        }
        val deletions = sources(png)
        AtomicFiles.write(deletions.associateWith { null })
        return deletions
    }

    /**
     * Deletes the item [id] with each model, parent model and texture of the pack that nothing else draws, as [unused] finds
     * them. An item outside `minecraft` is refused while main code of [repository] names it, as [uses] finds it, each
     * listed as a path and a line. Returns every file deleted.
     */
    fun deleteItem(
        id: String,
        repository: File,
    ): List<File> {
        val file = assets.resolve(Identifier.resource(id, "items", ".json"))
        if (!file.isFile) throw FieldRefusal("item", "The item $id is not in the pack.")
        val item = full(id)
        if (!item.startsWith("minecraft:")) {
            val uses = uses(item)
            val places =
                trackedFiles(repository, emptyList(), untracked = true)
                    .filterNot { repository.resolve(it).startsWith(assets) || EXAMPLES.containsMatchIn(it) }
                    .flatMap { path ->
                        readText(repository.resolve(path))
                            .orEmpty()
                            .lineSequence()
                            .withIndex()
                            .filter { (_, line) -> uses.containsMatchIn(line) }
                            .map { "$path:${it.index + 1}" }
                            .toList()
                    }
            check(places.isEmpty()) { used("The item $item", places) }
        }
        // The models it draws and each parent of those that the pack holds
        val models = linkedSetOf<String>()
        var next = ItemDefinitions.models(OxfmtJson.read(file)).map(::full)
        while (next.isNotEmpty()) {
            next =
                next
                    .filter { models.add(it) }
                    .mapNotNull { model ->
                        val json = runCatching { assets.resolve(Identifier.resource(model, "models", ".json")) }.getOrNull()
                        (json?.takeIf { it.isFile }?.let { OxfmtJson.read(it) } as? Map<*, *>)?.get("parent") as? String
                    }.map(::full)
        }
        val deletions = linkedMapOf<File, ByteArray?>(file to null)
        deletions.putAll(unused(models, deletions))
        AtomicFiles.write(deletions)
        return deletions.keys.toList()
    }

    /**
     * The uses of the item [id] in a line: its id as a word, or `Key.key` or `new NamespacedKey` with its namespace and
     * path, the forms in which code gives an item its model.
     */
    private fun uses(id: String): Regex {
        val (namespace, path) = id.split(':', limit = 2)
        val key = """(Key\.key|NamespacedKey)\(\s*"${Regex.escape(namespace)}"\s*,\s*"${Regex.escape(path)}"\s*\)"""
        return Regex("""(?<![\w:/.-])${Regex.escape(id)}(?![\w:/-]|\.\w)|$key""")
    }

    /** The refusal of a delete of [label], which [places] still use */
    private fun used(
        label: String,
        places: List<String>,
    ) = "$label is still used in ${places.size} place${if (places.size == 1) "" else "s"}:\n" + places.joinToString("\n") { "    $it" }

    /** Each JSON file of the pack's assets and `glyphs.json`, whose glyphs draw textures too, with its text */
    private fun texts(): MutableMap<File, String> {
        val texts =
            assets
                .walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .associateWith { it.readText() }
                .toMutableMap()
        pack.resolve("glyphs.json").takeIf { it.isFile }?.let { texts[it] = it.readText() }
        return texts
    }

    /** The texture [png] of the pack and, of its `.mcmeta` and `art/` source, each that exists */
    private fun sources(png: File): List<File> {
        val path = png.relativeTo(assets).invariantSeparatorsPath.removeSuffix(".png")
        return listOf(png) + listOf(File(png.path + ".mcmeta"), pack.resolve("art/assets/$path.aseprite")).filter { it.isFile }
    }

    /**
     * Deletions, each a file mapped to null: each model of [replaced] that nothing of the pack draws once [pending], new
     * files and null for each file deleted, is written, and each of its textures that nothing else names, with its
     * `.mcmeta` and `art/` source. Files in `minecraft` stay, since the client jar may draw them.
     */
    private fun unused(
        replaced: Set<String>,
        pending: Map<File, ByteArray?>,
    ): Map<File, ByteArray?> {
        // Every JSON of the pack as it will be
        val texts = texts()
        pending.forEach { (file, bytes) ->
            if (file.extension == "json") {
                if (bytes == null) {
                    texts.remove(file)
                } else {
                    texts[file] = bytes.decodeToString()
                }
            }
        }
        val gone =
            replaced
                .filter { !it.startsWith("minecraft:") }
                .associateBy { assets.resolve(Identifier.resource(it, "models", ".json")) }
                .filterKeys { it.isFile }
                .toMutableMap()
        // A model is drawn by an item, as a model's parent or in a block state. A kept model can keep another one as its
        // parent, so the models that go are found again until none more stays.
        do {
            val drawn = texts.filterKeys { it !in gone }.flatMap { (file, text) -> drawnBy(file, text, gone.values) }.toSet()
            val kept = gone.filterValues { it in drawn }.keys
            gone.keys.removeAll(kept)
        } while (kept.isNotEmpty())
        val named = { id: String -> texts.any { (file, text) -> file !in gone && names(text, id) } }
        val deletions = linkedMapOf<File, ByteArray?>()
        for (file in gone.keys) {
            deletions[file] = null
            val textures = ((OxfmtJson.read(file) as? Map<*, *>)?.get("textures") as? Map<*, *>).orEmpty()
            val ids = textures.values.filterIsInstance<String>()
            for (texture in ids.filterNot { it.startsWith("#") }.map(::full)) {
                if (texture.startsWith("minecraft:")) continue
                val png = runCatching { assets.resolve(Identifier.resource(texture, "textures", ".png")) }.getOrNull() ?: continue
                if (!png.isFile || png in deletions || named(texture)) continue
                sources(png).forEach { deletions[it] = null }
            }
        }
        return deletions
    }

    /** The models among [models] that [file], whose content is [text], draws: as an item, a model's parent or a block state */
    private fun drawnBy(
        file: File,
        text: String,
        models: Collection<String>,
    ): List<String> {
        val path = file.relativeTo(assets).invariantSeparatorsPath
        return when (path.split('/').getOrNull(1)) {
            "items" -> ItemDefinitions.models(OxfmtJson.read(text)).map(::full)
            "models" -> listOfNotNull(((OxfmtJson.read(text) as? Map<*, *>)?.get("parent") as? String)?.let(::full))
            "blockstates" -> models.filter { names(text, it) }
            else -> emptyList()
        }
    }

    /**
     * Whether the PNGs [a] and [b] show the same pixels. A pixel that is fully transparent counts as the same whatever
     * its colour, since an editor that saves the image again may drop it.
     */
    private fun samePixels(
        a: ByteArray,
        b: ByteArray,
    ): Boolean {
        if (a.contentEquals(b)) return true
        val first = runCatching { ImageIO.read(a.inputStream()) }.getOrNull() ?: return false
        val second = runCatching { ImageIO.read(b.inputStream()) }.getOrNull() ?: return false
        if (first.width != second.width || first.height != second.height) return false
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                val p = first.getRGB(x, y)
                val q = second.getRGB(x, y)
                if (p != q && (p ushr 24 != 0 || q ushr 24 != 0)) return false
            }
        }
        return true
    }

    /** Whether [text], a JSON file, names [id] as a string, also by its PNG and, in `minecraft`, without the namespace */
    private fun names(
        text: String,
        id: String,
    ): Boolean {
        val forms = listOfNotNull(id, id.removePrefix("minecraft:").takeIf { id.startsWith("minecraft:") })
        return forms.any { "\"$it\"" in text || "\"$it.png\"" in text }
    }

    /** A model that Blockbench exported as JSON to [source], and each texture beside it by the reference that names it */
    private fun exported(source: File): Pair<MutableMap<String, Any?>, Map<String, Blockbench.Texture>> {
        @Suppress("UNCHECKED_CAST")
        val model =
            OxfmtJson.read(source) as? MutableMap<String, Any?> ?: throw IllegalArgumentException("${source.name} is no model object.")
        val images =
            (model["textures"] as? Map<*, *>)
                .orEmpty()
                .values
                .filterIsInstance<String>()
                .mapNotNull { reference ->
                    val exported = name(reference)
                    val png = source.resolveSibling("$exported.png").takeIf { it.isFile } ?: return@mapNotNull null
                    val mcmeta = File(png.path + ".mcmeta").takeIf { it.isFile }?.readBytes()
                    reference to Blockbench.Texture(exported.lowercase(), png.readBytes(), mcmeta)
                }.toMap()
        return model to images
    }

    /** The name of the file that Blockbench exports a texture [reference] to */
    private fun name(reference: String) = reference.substringAfterLast('/').substringAfterLast(':')

    private companion object {
        val ATLAS = Regex("""assets/[^/]+/atlases/[^/]+\.json""")

        /** The files in which an item id is an example rather than a use: documents and tests */
        val EXAMPLES = Regex("""\.md$|(^|/)src/test/|\.(test|spec)\.[jt]sx?$|(^|/)spec/[^/]+_spec\.lua$""")
    }
}
