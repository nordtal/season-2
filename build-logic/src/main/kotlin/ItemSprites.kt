package eu.nordtal.season.build

import groovy.json.JsonSlurper
import java.io.File
import java.util.zip.ZipFile

/**
 * The atlas sprite that draws each item of the pack and the client jar in a text: the `layer0`, else the `particle`, of
 * a definition that is one plain `minecraft:model` without tints, where the texture's PNG exists and lies in an atlas.
 * Any other item would draw wrongly or as the missing texture, so it has none and a text shows its plain name.
 */
object ItemSprites {
    /** The sprite [sprite] of the atlas [atlas], both full ids such as `minecraft:items` and `minecraft:item/diamond`. */
    data class Sprite(
        val atlas: String,
        val sprite: String,
    )

    private val ITEM = Regex("""assets/([a-z0-9_.-]+)/items/([a-z0-9_./-]+)\.json""")
    private val ATLAS = Regex("""assets/([a-z0-9_.-]+)/atlases/([a-z0-9_./-]+)\.json""")

    /**
     * The sprite of every item that has one, by the item's full id, sorted, from the pack's [assets] over [jar]. An atlas
     * of the pack adds its sources to the jar's, as the client merges them.
     */
    fun of(
        assets: File,
        jar: File,
    ): Map<String, Sprite> =
        ZipFile(jar).use { zip ->
            val packed =
                assets
                    .walkTopDown()
                    .filter { it.isFile }
                    .map { "assets/" + it.relativeTo(assets).invariantSeparatorsPath }
                    .toList()
            val jarred = zip.entries().asSequence().map { it.name }.toList()
            val fromJar = { path: String -> zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } } }
            val fromPack = { path: String -> assets.resolve(path.removePrefix("assets/")).takeIf { it.isFile }?.readBytes() }
            val atlases =
                jarred.filter { it.matches(ATLAS) }.map { it to fromJar(it) } + packed.filter { it.matches(ATLAS) }.map { it to fromPack(it) }
            of((jarred + packed).toSortedSet(), { fromPack(it) ?: fromJar(it) }, atlases)
        }

    /**
     * The sprites of the items among [names], the paths under the root of the pack and the jar such as
     * `assets/minecraft/items/stone.json`, read with [read], in the [atlases], each its path and its bytes.
     */
    private fun of(
        names: Set<String>,
        read: (String) -> ByteArray?,
        atlases: List<Pair<String, ByteArray?>>,
    ): Map<String, Sprite> {
        val sources =
            atlases.flatMap { (file, bytes) ->
                val match = ATLAS.matchEntire(file) ?: return@flatMap emptyList()
                (parse(bytes)?.get("sources") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map {
                    "${match.groupValues[1]}:${match.groupValues[2]}" to it
                }
            }
        val sprites = sortedMapOf<String, Sprite>()
        for (match in names.mapNotNull { ITEM.matchEntire(it) }) {
            val texture = texture(parse(read(match.value)), read) ?: continue
            val (namespace, path) = full(texture).split(':', limit = 2)
            if ("assets/$namespace/textures/$path.png" !in names) continue
            val sprite = sources.firstNotNullOfOrNull { (atlas, source) -> sprite(source, namespace, path)?.let { Sprite(atlas, it) } }
            if (sprite != null) sprites["${match.groupValues[1]}:${match.groupValues[2]}"] = sprite
        }
        return sprites
    }

    /** The texture an item definition draws as a flat picture, or null when it draws none or several. */
    private fun texture(
        definition: Map<*, *>?,
        read: (String) -> ByteArray?,
    ): String? {
        val model = definition?.get("model") as? Map<*, *> ?: return null
        if (model["type"] != "minecraft:model" && model["type"] != "model") return null
        if ((model["tints"] as? List<*>).orEmpty().isNotEmpty()) return null
        val textures = textures(model["model"] as? String ?: return null, read) ?: return null
        return resolve(textures, "layer0") ?: resolve(textures, "particle")
    }

    /** The textures of [model] and its parents, the child's for a key both name, or null when one cannot be read. */
    private fun textures(
        model: String,
        read: (String) -> ByteArray?,
    ): Map<String, String>? {
        val textures = mutableMapOf<String, String>()
        val seen = mutableSetOf<String>()
        var id: String? = model
        while (id != null && seen.add(full(id))) {
            val (namespace, path) = full(id).split(':', limit = 2)
            if (path.startsWith("builtin/")) break
            val json = parse(read("assets/$namespace/models/$path.json")) ?: return null
            (json["textures"] as? Map<*, *>)?.forEach { (key, value) ->
                val texture = value as? String ?: (value as? Map<*, *>)?.get("sprite") as? String
                if (key is String && texture != null) textures.putIfAbsent(key, texture)
            }
            id = json["parent"] as? String
        }
        return textures
    }

    /** The texture under [key], following references such as `#all`, or null when it names none. */
    private fun resolve(
        textures: Map<String, String>,
        key: String,
    ): String? {
        var value = textures[key] ?: return null
        val seen = mutableSetOf(key)
        while (value.startsWith("#")) {
            val next = value.removePrefix("#")
            if (!seen.add(next)) return null
            value = textures[next] ?: return null
        }
        return value
    }

    /** The sprite id that the atlas [source] gives the texture at [path] of [namespace], or null when it leaves it out. */
    private fun sprite(
        source: Map<*, *>,
        namespace: String,
        path: String,
    ): String? =
        when (source["type"]?.toString()?.removePrefix("minecraft:")) {
            "directory" -> {
                val directory = source["source"] as? String
                val prefix = source["prefix"] as? String ?: ""
                if (directory != null && path.startsWith("$directory/")) "$namespace:$prefix${path.removePrefix("$directory/")}" else null
            }
            "single" -> {
                val resource = (source["resource"] as? String)?.let(::full)
                if (resource == "$namespace:$path") (source["sprite"] as? String)?.let(::full) ?: resource else null
            }
            else -> null
        }

    private fun parse(bytes: ByteArray?): Map<*, *>? = bytes?.let { runCatching { JsonSlurper().parse(it) }.getOrNull() as? Map<*, *> }

    private fun full(id: String) = if (':' in id) id else "minecraft:$id"

    /** The sprites as the lines of a properties file, `item=atlas sprite`, sorted, with the colons of the item escaped. */
    fun properties(sprites: Map<String, Sprite>): String =
        sprites.entries.joinToString("") { (item, sprite) -> "${item.replace(":", "\\:")}=${sprite.atlas} ${sprite.sprite}\n" }
}
