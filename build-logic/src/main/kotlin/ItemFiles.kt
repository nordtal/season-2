package eu.nordtal.season.build

import groovy.json.JsonSlurper
import java.io.File
import java.util.zip.ZipFile

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
     * A texture is a draft when its PNG or its source is among [drafts], the files that differ from the last commit.
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
            "file" to file?.absolutePath,
            "definition" to definition,
            "problems" to ItemDefinitions.problems(definition) + ItemDefinitions.missing(definition, ::read, ::sprite),
            "types" to ItemDefinitions.types,
            "models" to ItemDefinitions.models(definition).map { linkedMapOf("model" to it, "textures" to textures(it, drafts)) },
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
        ItemDefinitions.textures(model, ::read, mutableListOf()).distinctBy { it.key }.map { (_, key, texture) ->
            val png = runCatching { Identifier.resource(texture, "textures", ".png") }.getOrNull()?.let { assets.resolve(it) }
            val id = "${full(texture)}.png"
            if (png?.isFile == true) {
                val described = PackModel.texture(id, pack, drafts)
                linkedMapOf("key" to key, "texture" to described) + PackModel.animation(png, pack, described)
            } else {
                linkedMapOf("key" to key, "texture" to mapOf("id" to id))
            }
        }

    /** Writes [definition], the text of an item definition, as the item [id] in the pack, after every rule passed. */
    fun write(
        id: String,
        definition: String,
    ): File = store(id, OxfmtJson.read(definition))

    private fun store(
        id: String,
        json: Any?,
    ): File {
        val problems = ItemDefinitions.problems(json).ifEmpty { ItemDefinitions.missing(json, ::read, ::sprite) }
        check(problems.isEmpty()) { "The item $id breaks ${problems.size} rule(s):\n" + problems.joinToString("\n") { "    $it" } }
        val file = assets.resolve(Identifier.resource(id, "items", ".json"))
        AtomicFiles.write(mapOf(file to OxfmtJson.asset(json).toByteArray()))
        return file
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
        val bytes =
            read(Identifier.resource(id, "items", ".json"))
                ?: throw IllegalArgumentException("The item $id is in neither the pack nor the client jar.")

        @Suppress("UNCHECKED_CAST")
        val definition =
            OxfmtJson.read(bytes.decodeToString()) as? MutableMap<String, Any?>
                ?: throw IllegalArgumentException("The item $id is no JSON object.")
        val changes =
            fields?.let {
                runCatching { OxfmtJson.read(it) }.getOrNull() as? Map<*, *>
                    ?: throw FieldRefusal("fields", "-Pfields=$it is no JSON object.")
            }

        @Suppress("UNCHECKED_CAST")
        require(ItemDefinitions.edit(definition, at, changes as Map<String, Any?>?, add, remove)) { "The item $id has nothing at $at." }
        return store(id, definition)
    }

    /**
     * Imports [source], a model that Blockbench exported as Java model JSON, as the model [id], such as `nordtal:item/lamp`.
     *
     * Each texture beside [source] is copied next to the model's folder under `textures/` and its reference rewritten; a
     * reference that the pack or the client jar resolves stays. Returns every file written.
     */
    fun import(
        source: File,
        id: String,
    ): List<File> {
        require(source.extension.lowercase() != "bbmodel") {
            "${source.name} is a Blockbench project. Export it in Blockbench with File > Export > Export Block/Item Model and import the .json."
        }
        val (namespace, path) = Identifier.parse(id)
        val model =
            OxfmtJson.read(source) as? MutableMap<String, Any?> ?: throw IllegalArgumentException("${source.name} is no model object.")
        val target = assets.resolve("$namespace/models/$path.json")
        val copies = linkedMapOf<File, File>()
        val textures = model["textures"] as? MutableMap<String, Any?> ?: mutableMapOf()
        for ((key, value) in textures.entries.toList()) {
            val reference = value as? String ?: continue
            if (reference.startsWith("#")) continue
            val exported = reference.substringAfterLast('/').substringAfterLast(':')
            val png = source.resolveSibling("$exported.png")
            val name = exported.lowercase()
            if (!png.isFile) {
                require(sprite(reference)) {
                    "Texture $key of ${source.name} is $reference, which is neither beside it as $exported.png nor in the pack or the client jar."
                }
                continue
            }
            val texture = listOfNotNull(path.substringBeforeLast('/', "").ifEmpty { null }, name).joinToString("/")
            val (_, file) = Identifier.parse("$namespace:$texture.png", ".png")
            textures[key] = "$namespace:$texture"
            copies[png] = assets.resolve("$namespace/textures/$file")
            source.resolveSibling("$exported.png.mcmeta").takeIf { it.isFile }?.let {
                copies[it] =
                    assets.resolve("$namespace/textures/$file.mcmeta")
            }
        }
        (
            listOf(
                target,
            ) + copies.values
        ).forEach { check(!it.exists()) { "${it.relativeTo(pack).invariantSeparatorsPath} exists already." } }
        AtomicFiles.write(
            mapOf(target to OxfmtJson.asset(model).toByteArray()) + copies.entries.associate { (from, to) -> to to from.readBytes() },
        )
        return listOf(target) + copies.values.distinct()
    }

    private companion object {
        val ATLAS = Regex("""assets/[^/]+/atlases/[^/]+\.json""")
    }
}
