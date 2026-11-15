package eu.nordtal.season.build

import groovy.json.JsonSlurper
import java.io.File
import java.util.zip.ZipFile

/**
 * The files of the pack that no other part of Nordtal Designer's tree shows: models, block states, atlases, equipment,
 * particles, shaders, post effects, texts and any other file under `src/assets/<namespace>/`. Each is edited as text,
 * and [problems] checks it by the rules of its folder. A reference into `minecraft` may name the client jar, which
 * this check does not open, so it passes.
 */
object PackFiles {
    /** The first folders under a namespace that another part of the tree shows, and `font`, which the build writes. */
    private val OWNED = setOf("textures", "lang", "items", "sounds", "font")

    /** The kind of a file by its first folder under the namespace. A file in any other folder is of the kind `file`. */
    val KINDS =
        linkedMapOf(
            "models" to "model",
            "blockstates" to "blockstate",
            "atlases" to "atlas",
            "equipment" to "equipment",
            "particles" to "particle",
            "shaders" to "shader",
            "post_effect" to "post_effect",
            "texts" to "text",
        )

    /** The file a new file of each kind starts as. A kind without one starts empty. */
    private val TEMPLATES =
        mapOf(
            "model" to "{\n    \"parent\": \"minecraft:item/generated\",\n    \"textures\": {}\n}\n",
            "blockstate" to "{\n    \"variants\": {\n        \"\": { \"model\": \"minecraft:block/stone\" }\n    }\n}\n",
            "atlas" to "{\n    \"sources\": []\n}\n",
            "equipment" to "{\n    \"layers\": {}\n}\n",
            "particle" to "{\n    \"textures\": []\n}\n",
        )

    private val JAR_FILE = Regex("""assets/([a-z0-9_.-]+)/(.+)""")

    /** Whether Files shows [path], relative to `assets/`, such as `minecraft/blockstates/stone.json`. */
    fun shown(path: String): Boolean {
        val parts = path.split('/')
        if (parts.size < 2 || parts.any { it.isEmpty() }) return false
        if (parts.size == 2) return parts[1] != "sounds.json" && !parts[1].startsWith(".")
        return parts[1] !in OWNED && !parts.last().startsWith(".")
    }

    /** The kind of the file at [path] relative to `assets/`, by its first folder. */
    fun kind(path: String): String =
        path
            .split('/')
            .getOrNull(1)
            ?.let { KINDS[it] }
            .takeIf { path.count { it == '/' } >= 2 } ?: "file"

    /** Every file under [assets] that [shown] lets through, sorted by path. */
    fun list(assets: File): List<File> =
        assets
            .walkTopDown()
            .filter { it.isFile && shown(it.relativeTo(assets).invariantSeparatorsPath) }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()

    /** The files of the client jar [names] that [shown] lets through, as ids such as `minecraft:models/block/stone.json`. */
    fun vanilla(names: Collection<String>): List<String> =
        names
            .mapNotNull { JAR_FILE.matchEntire(it) }
            .filter { shown("${it.groupValues[1]}/${it.groupValues[2]}") }
            .map { "${it.groupValues[1]}:${it.groupValues[2]}" }
            .sorted()

    /** The problems of [file] under [assets], each a sentence for the person who wrote it, and none for a file it cannot judge. */
    fun problems(
        assets: File,
        file: File,
    ): List<String> {
        val path = file.relativeTo(assets).invariantSeparatorsPath
        val kind = kind(path)
        if (file.extension != "json") return emptyList()
        val json =
            try {
                JsonSlurper().parse(file)
            } catch (e: RuntimeException) {
                return listOf("It is no JSON: ${e.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()}")
            }
        val check: (Rules, Map<*, *>) -> Unit =
            when (kind) {
                "model" -> Rules::model
                "blockstate" -> Rules::blockstate
                "atlas" -> Rules::atlas
                "equipment" -> Rules::equipment
                "particle" -> Rules::particle
                else -> return emptyList()
            }
        val root = json as? Map<*, *> ?: return listOf("It is no JSON object.")
        val found = mutableListOf<String>()
        check(Rules(assets, found), root)
        return found
    }

    /** The findings of [problems] for every file of [assets], each after the file's path, for `checkPack`. */
    fun findings(assets: File): List<String> =
        list(assets).flatMap { file ->
            problems(assets, file).map { "${file.relativeTo(assets).invariantSeparatorsPath}: $it" }
        }

    /**
     * Writes the new file [id], such as `nordtal:models/item/lamp.json`, under the pack's `src/assets` from the template of
     * its kind, and returns it.
     *
     * @throws FieldRefusal at `asset` if [id] is no path that Files shows or the file exists already
     */
    fun create(
        pack: File,
        id: String,
    ): List<File> {
        val file = target(pack, id)
        if (file.exists()) throw FieldRefusal("asset", "$id exists already.")
        AtomicFiles.write(mapOf(file to TEMPLATES[kind(path(id))].orEmpty().toByteArray()))
        return listOf(file)
    }

    /**
     * Copies the file [id] of the client [jar], such as `minecraft:blockstates/stone.json`, into the pack's `src/assets`,
     * and returns it.
     *
     * @throws FieldRefusal at `asset` if the jar lacks it, Files does not show it, or the pack holds it already
     */
    fun copy(
        pack: File,
        jar: File,
        id: String,
    ): List<File> {
        val file = target(pack, id)
        if (file.exists()) throw FieldRefusal("asset", "$id is in the pack already. Delete it first to copy it again.")
        val bytes =
            ZipFile(jar).use { zip ->
                val entry = zip.getEntry("assets/${path(id)}") ?: throw FieldRefusal("asset", "The client jar has no $id.")
                zip.getInputStream(entry).use { it.readBytes() }
            }
        AtomicFiles.write(mapOf(file to bytes))
        return listOf(file)
    }

    /**
     * Deletes the file [id] of the pack and returns it. A model outside `minecraft`, or one the client [jar] lacks, is
     * refused while another JSON file of the pack names it as a parent or model, and the refusal lists each place as `path:line`.
     */
    fun delete(
        pack: File,
        jar: File?,
        id: String,
    ): List<File> {
        val file = target(pack, id)
        if (!file.isFile) throw FieldRefusal("asset", "$id is no file of the pack.")
        val path = path(id)
        val vanilla = jar?.takeIf { it.isFile }?.let { ZipFile(it).use { zip -> zip.getEntry("assets/$path") != null } } ?: false
        if (kind(path) == "model" && !vanilla) {
            val model = path.substringBefore('/') + ":" + path.substringAfter("/models/").removeSuffix(".json")
            val forms = listOfNotNull(model, model.removePrefix("minecraft:").takeIf { model.startsWith("minecraft:") })
            val assets = pack.resolve("src/assets")
            val places =
                assets
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "json" && it != file }
                    .sortedBy { it.invariantSeparatorsPath }
                    .flatMap { other ->
                        other
                            .readText()
                            .lineSequence()
                            .withIndex()
                            .filter { (_, line) -> forms.any { form -> naming(form).containsMatchIn(line) } }
                            .map { "${other.relativeTo(pack).invariantSeparatorsPath}:${it.index + 1}" }
                    }.toList()
            check(places.isEmpty()) {
                "The model $model is still used in ${places.size} place${if (places.size == 1) "" else "s"}:\n" +
                    places.joinToString("\n") { "    $it" }
            }
        }
        AtomicFiles.write(mapOf(file to null))
        return listOf(file)
    }

    /** A `parent` or `model` key that names the model [id], so a texture of the same id is no use of it */
    private fun naming(id: String) = Regex("\"(parent|model)\"\\s*:\\s*\"${Regex.escape(id)}\"")

    /** The path under `assets/` of the file [id], such as `nordtal/models/item/lamp.json`. */
    private fun path(id: String): String {
        val namespace = id.substringBefore(':', "")
        val rest = id.substringAfter(':', "")
        Identifier
            .problem(
                "$namespace:${rest.ifEmpty { "x" }}",
            )?.let { throw FieldRefusal("asset", "${it.replaceFirstChar(Char::uppercase)}.") }
        return "$namespace/$rest"
    }

    /** The file of [id] under the pack's `src/assets`, refused unless Files shows it. */
    private fun target(
        pack: File,
        id: String,
    ): File {
        val path = path(id)
        if (!shown(path) || '.' !in path.substringAfterLast('/')) {
            val folder = path.split('/').getOrNull(1)
            val reason =
                when (folder) {
                    "textures", "sounds", "lang", "items" -> "Create it as a texture, sound, text or item instead."
                    "font" -> "The build writes fonts from glyphs.json, so create a font in Glyphs instead."
                    else -> "Give its folder and its file name with an extension, such as models/item/lamp.json."
                }
            throw FieldRefusal("asset", "$id is no file of Files. $reason")
        }
        return pack.resolve("src/assets/$path")
    }

    /** The checks of one file, which add each problem to [found]. */
    private class Rules(
        val assets: File,
        val found: MutableList<String>,
    ) {
        fun model(root: Map<*, *>) {
            root["parent"]?.let { parent ->
                if (parent !is String) found += "parent is no text." else reference(parent, "models", ".json", "Its parent")
            }
            val textures = root["textures"]
            if (textures != null && textures !is Map<*, *>) found += "textures is no object."
            (textures as? Map<*, *>)?.forEach { (key, value) ->
                when {
                    value !is String -> found += "The texture $key is no text."
                    value.startsWith("#") -> if (value.length == 1) found += "The texture $key names no key after #."
                    else -> reference(value, "textures", ".png", "The texture $key")
                }
            }
            val elements = root["elements"]
            if (elements != null && elements !is List<*>) found += "elements is no list."
            (elements as? List<*>)?.forEachIndexed { i, element ->
                val map = element as? Map<*, *>
                if (map == null) {
                    found += "Element ${i + 1} is no object."
                    return@forEachIndexed
                }
                for (corner in listOf("from", "to")) {
                    val point = map[corner] as? List<*>
                    if (point == null || point.size != 3 || point.any { it !is Number }) {
                        found += "Element ${i + 1} has no $corner of three numbers."
                    }
                }
                val faces = map["faces"]
                if (faces !is Map<*, *> || faces.isEmpty()) found += "Element ${i + 1} has no faces."
            }
        }

        fun blockstate(root: Map<*, *>) {
            val variants = root["variants"]
            val multipart = root["multipart"]
            if ((variants == null) == (multipart == null)) {
                found += "It needs either variants or multipart, and not both."
                return
            }
            if (variants != null) {
                if (variants !is Map<*, *>) {
                    found += "variants is no object."
                    return
                }
                variants.forEach { (key, value) -> models(value, "The variant \"$key\"") }
            }
            if (multipart != null) {
                if (multipart !is List<*>) {
                    found += "multipart is no list."
                    return
                }
                multipart.forEachIndexed { i, part ->
                    val apply = (part as? Map<*, *>)?.get("apply")
                    if (apply == null) found += "Part ${i + 1} of multipart has no apply." else models(apply, "Part ${i + 1} of multipart")
                }
            }
        }

        /** A variant's [value]: one model object or a list of them, each with a `model` the pack or the jar holds */
        private fun models(
            value: Any?,
            what: String,
        ) {
            val list = value as? List<*> ?: listOf(value)
            if (list.isEmpty()) found += "$what lists no model."
            list.forEach { entry ->
                val model = (entry as? Map<*, *>)?.get("model")
                if (model !is String) found += "$what has no model." else reference(model, "models", ".json", what)
            }
        }

        fun atlas(root: Map<*, *>) {
            val sources = root["sources"] as? List<*>
            if (sources == null) {
                found += "It has no list of sources."
                return
            }
            sources.forEachIndexed { i, source ->
                if ((source as? Map<*, *>)?.get("type") !is String) found += "Source ${i + 1} has no type."
            }
        }

        fun equipment(root: Map<*, *>) {
            val layers = root["layers"] as? Map<*, *>
            if (layers == null) {
                found += "It has no object of layers."
                return
            }
            layers.forEach { (type, list) ->
                if (list !is List<*>) {
                    found += "The layers of $type are no list."
                    return@forEach
                }
                list.forEachIndexed { i, layer ->
                    val texture = (layer as? Map<*, *>)?.get("texture")
                    if (texture !is String) {
                        found += "Layer ${i + 1} of $type has no texture."
                    } else {
                        reference(texture, "textures/entity/equipment/$type", ".png", "Layer ${i + 1} of $type")
                    }
                }
            }
        }

        fun particle(root: Map<*, *>) {
            val textures = root["textures"] as? List<*>
            if (textures == null) {
                found += "It has no list of textures."
                return
            }
            textures.forEach { texture ->
                if (texture !is String) found += "A texture is no text." else reference(texture, "textures/particle", ".png", "The texture")
            }
        }

        /**
         * Adds a problem unless [id] is an identifier whose file under [folder] the pack holds. An id in `minecraft`
         * may name the client jar and passes.
         */
        private fun reference(
            id: String,
            folder: String,
            suffix: String,
            what: String,
        ) {
            val full = if (':' in id) id else "minecraft:$id"
            Identifier.problem(full)?.let {
                found += "$what is no id: $it."
                return
            }
            if (full.startsWith("minecraft:")) return
            val file = assets.resolve("${full.substringBefore(':')}/$folder/${full.substringAfter(':')}$suffix")
            if (!file.isFile) found += "$what names $full, which the pack does not hold."
        }
    }
}
