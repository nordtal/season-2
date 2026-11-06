package eu.nordtal.season.build

import groovy.json.JsonOutput
import java.io.File
import java.nio.ByteBuffer

/**
 * Changes the texture keys of a model of the pack at [pack] and writes a model as a Blockbench project. A reference
 * resolves through [files], in the pack first and then in the client jar.
 */
class ModelFiles(
    private val pack: File,
    private val files: ItemFiles,
) {
    private val assets = pack.resolve("src/assets")

    /**
     * Sets the texture [key] of the pack's model [model] to [texture], renames it [to], or removes it with [delete], and
     * writes the model. A texture outside `minecraft` that neither the pack nor the client jar holds is written as a
     * transparent PNG of [width] by [height] in the same run. With [add] the model must not set or inherit [key] yet.
     * Returns every file written.
     */
    fun editTexture(
        model: String,
        key: String,
        texture: String?,
        to: String?,
        delete: Boolean,
        width: Int,
        height: Int,
        add: Boolean = false,
    ): List<File> {
        val file = file(model)

        @Suppress("UNCHECKED_CAST")
        val json = OxfmtJson.read(file) as? MutableMap<String, Any?> ?: throw IllegalArgumentException("The model $model is no JSON object.")

        @Suppress("UNCHECKED_CAST")
        val textures = json["textures"] as? MutableMap<String, Any?> ?: OxfmtJson.Object().also { json["textures"] = it }
        checkKey("key", key)
        val inherited = ItemDefinitions.textures(model, files::read, mutableListOf()).map { it.key }.toSet() - textures.keys
        if (add && (key in textures || key in inherited)) throw FieldRefusal("key", "$model has a #$key already.")
        if (delete || to != null) {
            if (key !in textures) {
                throw FieldRefusal("key", if (key in inherited) "#$key comes from a parent of $model." else "$model sets no #$key.")
            }
        }
        val pending = linkedMapOf<File, ByteArray?>()
        if (delete) {
            val users = references(json, key)
            if (users > 0) throw FieldRefusal("key", "$users face(s) or key(s) of $model draw #$key. Give them another texture first.")
            textures.remove(key)
            if (textures.isEmpty()) json.remove("textures")
        } else {
            if (texture != null) textures[key] = reference(model, textures, key, texture, width, height, pending)
            if (to != null && to != key) {
                checkKey("to", to)
                if (to in textures || to in inherited) throw FieldRefusal("to", "$model has a #$to already.")
                rename(json, textures, key, to)
            }
        }
        pending[file] = OxfmtJson.asset(json).toByteArray()
        AtomicFiles.write(pending)
        return pending.keys.toList()
    }

    /** The model file of [model] in the pack, refused when only the client jar or nothing holds it. */
    private fun file(model: String): File {
        val path = runCatching { Identifier.resource(model, "models", ".json") }.getOrElse { throw FieldRefusal("model", it.message.orEmpty()) }
        val file = assets.resolve(path)
        if (file.isFile) return file
        throw FieldRefusal(
            "model",
            if (files.read(path) != null) "$model is only in the client jar. Import a model of the pack to change it." else "The pack has no model $model.",
        )
    }

    private fun checkKey(
        field: String,
        key: String,
    ) {
        if (!KEY.matches(key)) throw FieldRefusal(field, "A texture key holds only a-z, A-Z, 0-9 and _, not '$key'.")
    }

    /**
     * The value for a key from [texture]: another key as `#key`, or a texture id that the pack or the jar holds. A missing
     * texture outside `minecraft` is added to [pending] as a transparent PNG.
     */
    private fun reference(
        model: String,
        textures: Map<String, Any?>,
        key: String,
        texture: String,
        width: Int,
        height: Int,
        pending: MutableMap<File, ByteArray?>,
    ): String {
        val given = texture.trim()
        if (given.startsWith("#")) {
            val other = given.substring(1)
            val known = other in textures || ItemDefinitions.textures(model, files::read, mutableListOf()).any { it.key == other }
            if (other == key || !known) throw FieldRefusal("texture", "$model has no other key $given.")
            if (leadsTo(textures, other, key)) throw FieldRefusal("texture", "#$other names #$key in turn, so neither would draw.")
            return given
        }
        val id = given.removeSuffix(".png").let { if (':' in it) it else "minecraft:$it" }
        val path = runCatching { Identifier.resource(id, "textures", ".png") }.getOrElse { throw FieldRefusal("texture", it.message.orEmpty()) }
        if (files.sprite(id)) return id
        if (id.startsWith("minecraft:")) throw FieldRefusal("texture", "$id is in neither the pack nor the client jar.")
        if (width !in 1..4096 || height !in 1..4096) {
            throw FieldRefusal("width", "A texture is 1 to 4096 pixels wide and high, not $width by $height.")
        }
        pending[assets.resolve(path)] = NewTextures.transparent(width, height)
        return id
    }

    /** Whether the key [from] of [textures] names [key] through `#` references. */
    private fun leadsTo(
        textures: Map<String, Any?>,
        from: String,
        key: String,
    ): Boolean {
        var next: String? = from
        val seen = mutableSetOf<String>()
        while (next != null && seen.add(next)) {
            if (next == key) return true
            next = (textures[next] as? String)?.takeIf { it.startsWith("#") }?.substring(1)
        }
        return false
    }

    /** How many faces and keys of [json] draw `#key`. */
    private fun references(
        json: Map<String, Any?>,
        key: String,
    ): Int = faces(json).count { it["texture"] == "#$key" } + (json["textures"] as Map<*, *>).values.count { it == "#$key" }

    /** Renames the key [from] of [textures] to [to], in place, and each face and key of [json] that draws it. */
    private fun rename(
        json: Map<String, Any?>,
        textures: MutableMap<String, Any?>,
        from: String,
        to: String,
    ) {
        val renamed = textures.entries.map { (k, v) -> (if (k == from) to else k) to (if (v == "#$from") "#$to" else v) }
        textures.clear()
        renamed.forEach { (k, v) -> textures[k] = v }

        @Suppress("UNCHECKED_CAST")
        faces(json).forEach { if (it["texture"] == "#$from") (it as MutableMap<String, Any?>)["texture"] = "#$to" }
    }

    private fun faces(json: Map<String, Any?>): List<Map<*, *>> =
        (json["elements"] as? List<*>).orEmpty().flatMap { ((it as? Map<*, *>)?.get("faces") as? Map<*, *>).orEmpty().values }.filterIsInstance<Map<*, *>>()

    /**
     * Writes the model [model] of the pack or the client jar to [to] as a Blockbench project of the format Java Block/Item
     * with each texture embedded, which `importBlockbench` reads back as the same model.
     */
    fun export(
        model: String,
        to: File,
    ): File {
        val chain = chain(model)
        val elements = chain.firstNotNullOfOrNull { it["elements"] as? List<*> }
        if (elements.isNullOrEmpty()) throw FieldRefusal("model", "$model and its parents draw no elements that Blockbench could edit.")
        val keys = linkedMapOf<String, String>()
        chain.reversed().forEach { json -> (json["textures"] as? Map<*, *>)?.forEach { (k, v) -> (v as? String)?.let { keys[k as String] = it } } }
        val textures =
            keys.keys.mapNotNull { key -> resolve(keys, key)?.let { key to it } }.groupBy({ it.second }, { it.first }).map { (id, named) ->
                val png = files.read(Identifier.resource(id, "textures", ".png"))
                    ?: throw FieldRefusal("model", "Texture $id of $model is a sprite without a PNG, which Blockbench cannot embed.")
                val mcmeta = files.read(Identifier.resource(id, "textures", ".png.mcmeta"))
                Blockbench.Embedded(id, named.firstOrNull { it != "particle" } ?: named.first(), "particle" in named, png, size(png, id), mcmeta)
            }
        textures.groupBy { it.id.substringAfterLast('/').substringAfter(':') }.values.firstOrNull { it.size > 1 }?.let { same ->
            throw FieldRefusal("model", "${same[0].id} and ${same[1].id} of $model share a file name, which a Blockbench import cannot tell apart.")
        }
        faces(chain.firstNotNullOf { it["elements"] as? List<*> }.let { mapOf("elements" to it) })
            .mapNotNull { (it["texture"] as? String)?.removePrefix("#") }
            .firstOrNull { resolve(keys, it) == null }
            ?.let { throw FieldRefusal("model", "A face of $model draws #$it, which names no texture, so Blockbench would drop it.") }
        val own = chain.first()
        val project =
            Blockbench.project(
                model.substringAfterLast('/').substringAfter(':'),
                own,
                elements.filterIsInstance<Map<*, *>>(),
                textures,
                keys.mapValues { (key, _) -> resolve(keys, key) },
            )
        to.absoluteFile.parentFile.mkdirs()
        to.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(project)))
        return to
    }

    /** [model] and each of its parents, the child first. */
    private fun chain(model: String): List<Map<*, *>> {
        val chain = mutableListOf<Map<*, *>>()
        val seen = mutableSetOf<String>()
        var id: String? = model
        while (id != null && !id.startsWith("builtin/") && seen.add(id)) {
            val bytes =
                files.read(Identifier.resource(id, "models", ".json"))
                    ?: throw FieldRefusal("model", "The model $id is in neither the pack nor the client jar.")
            val json = OxfmtJson.read(bytes.decodeToString()) as? Map<*, *> ?: break
            chain += json
            id = json["parent"] as? String
        }
        return chain
    }

    /** The texture id that [key] of [keys] names, through `#` references, or null when it names none. */
    private fun resolve(
        keys: Map<String, String>,
        key: String,
    ): String? {
        var value = keys[key]
        val seen = mutableSetOf(key)
        while (value != null && value.startsWith("#")) {
            val next = value.substring(1)
            if (!seen.add(next)) return null
            value = keys[next]
        }
        return value?.let { if (':' in it) it else "minecraft:$it" }
    }

    /** The pixel size of [png], read from its header. */
    private fun size(
        png: ByteArray,
        id: String,
    ): Pair<Int, Int> {
        require(png.size >= 24 && String(png, 12, 4, Charsets.US_ASCII) == "IHDR") { "Texture $id is no PNG." }
        val header = ByteBuffer.wrap(png, 16, 8)
        return header.int to header.int
    }

    private companion object {
        val KEY = Regex("""[A-Za-z0-9_]+""")
    }
}
