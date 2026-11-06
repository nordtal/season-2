package eu.nordtal.season.build

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * The sound events of the pack: each namespace's `src/assets/<namespace>/sounds.json`, and the `.ogg` files under its
 * `sounds/`. An event's id is `<namespace>:<event>`, such as `nordtal:ui.click`, and it is the draft `sound <id>` with
 * its entry in `sounds.json`, the `.ogg` files that it alone names and the text of its subtitle.
 *
 * The events of the `sounds.json` files are kept as [TextFiles], each event's entry as JSON by its id, so that [Drafts]
 * merges them event by event, as it merges texts key by key.
 */
object Sounds {
    private val FILE = Regex("""src/assets/([^/]+)/sounds\.json""")
    private val OGG = Regex("""src/assets/([^/]+)/sounds/(.+)\.ogg""")
    private const val OWN = "sound "

    /** The fields of a sound in an event's `sounds` list besides its `name`, each checked by [problem]. */
    private val FIELDS = setOf("volume", "pitch", "weight", "stream", "attenuation_distance", "preload", "type")

    /** Whether [path], relative to the pack, is a `sounds.json`. */
    fun isSounds(path: String): Boolean = FILE.matches(path)

    /** The id of the draft of the event [event]. */
    fun id(event: String): String = "$OWN$event"

    /** The event whose draft [id] is, or null for any other draft. */
    fun event(id: String): String? = id.removePrefix(OWN).takeIf { id.startsWith(OWN) }

    /** The `sounds.json` of [namespace], relative to the pack. */
    private fun path(namespace: String) = "src/assets/$namespace/sounds.json"

    /** The events of each `sounds.json` of [pack] as they are, each entry as JSON by its event's id. */
    fun work(pack: File): TextFiles =
        pack
            .resolve("src/assets")
            .walkTopDown()
            .maxDepth(2)
            .filter { it.isFile }
            .map { it.relativeTo(pack).invariantSeparatorsPath }
            .filter(::isSounds)
            .sorted()
            .associateWith { values(it, pack.resolve(it).readText()) }

    /** The events of each `sounds.json` of [pack] at [ref], or none where the ref is missing. */
    fun at(
        pack: File,
        ref: String,
    ): TextFiles {
        val paths =
            runCatching { git(pack, listOf("ls-tree", "-r", "-z", "--name-only", ref, "--", "src/assets")) }
                .getOrElse { return emptyMap() }
                .split('\u0000')
                .filter(::isSounds)
                .sorted()
        return paths.associateWith { values(it, git(pack, listOf("show", "$ref:./$it"))) }
    }

    private fun values(
        path: String,
        text: String,
    ): Map<String, String> {
        val namespace = FILE.matchEntire(path)!!.groupValues[1]
        return (runCatching { OxfmtJson.read(text) }.getOrNull() as? Map<*, *>).orEmpty().entries.associate { (event, value) ->
            "$namespace:$event" to JsonOutput.toJson(value)
        }
    }

    /** Each event of [files] by its id, parsed. */
    private fun parsed(files: TextFiles): Map<String, Map<*, *>> =
        files.values
            .flatMap { it.entries }
            .associate { (event, json) -> event to (JsonSlurper().parseText(json) as? Map<*, *>).orEmpty() }
            .toSortedMap()

    /** Each event of [pack] by its id, as the work tree holds it. */
    fun events(pack: File): Map<String, Map<*, *>> = parsed(work(pack))

    /**
     * The draft that the file [path] of [pack] belongs to: the event that names it as a sound, as the work tree or the
     * last commit holds the events, or null where [path] is no `.ogg` that an event names.
     */
    fun idOf(
        pack: File,
        path: String,
    ): String? {
        val match = OGG.matchEntire(path) ?: return null
        val name = "${match.groupValues[1]}:${match.groupValues[2]}"
        return sequenceOf({ events(pack) }, { parsed(at(pack, "HEAD")) })
            .firstNotNullOfOrNull { events -> events().entries.firstOrNull { (_, event) -> name in names(event) }?.key }
            ?.let(::id)
    }

    /** The subtitle keys that the events of [pack] in the work tree and at [ref] name, each with its event's draft. */
    fun subtitles(
        pack: File,
        ref: String,
    ): Map<String, String> =
        (parsed(at(pack, ref)) + events(pack))
            .mapNotNull { (event, entry) -> (entry["subtitle"] as? String)?.let { it to id(event) } }
            .toMap()

    /** The name of each sound file that [event] lists, with its namespace. */
    private fun names(event: Map<*, *>): List<String> =
        (event["sounds"] as? List<*>).orEmpty().mapNotNull { sound ->
            val entry = sound as? Map<*, *>
            if (entry != null && entry["type"] == "event") return@mapNotNull null
            (entry?.get("name") ?: sound as? String)?.toString()?.let(::qualified)
        }

    private fun qualified(name: String) = if (':' in name) name else "minecraft:$name"

    /**
     * Writes the [events] into the `sounds.json` files of [pack] as [source] holds them: an event that [source] lacks
     * leaves its file. A file that ends up empty and that [source] lacks goes. Returns the files it wrote or deleted.
     */
    fun put(
        pack: File,
        source: TextFiles,
        events: Set<String>,
    ): List<File> {
        val contents = linkedMapOf<File, ByteArray?>()
        for ((namespace, group) in events.groupBy { it.substringBefore(':') }) {
            val path = path(namespace)
            val file = pack.resolve(path)
            val root = read(file)
            val before = JsonOutput.toJson(root)
            for (event in group) {
                val name = event.substringAfter(':')
                val wanted = source[path]?.get(event)
                if (wanted == null) root.remove(name) else root[name] = OxfmtJson.read(wanted)
            }
            if (JsonOutput.toJson(root) == before) continue
            contents[file] = if (root.isEmpty() && path !in source) null else OxfmtJson.format(root).toByteArray()
        }
        AtomicFiles.write(contents.filter { (file, bytes) -> bytes != null || file.exists() })
        return contents.keys.toList()
    }

    /** Merges the [events] of [clone] and [main] since [base] as [Texts.merge] merges keys, a conflict named by the event. */
    fun merge(
        base: TextFiles,
        clone: TextFiles,
        main: TextFiles,
        events: Set<String>,
        fields: Map<String, String> = emptyMap(),
    ): Pair<TextFiles, List<Drafts.Field>> = Texts.merge(base, clone, main, events, fields) { _, event -> event }

    private fun read(file: File): OxfmtJson.Object =
        (if (file.isFile) OxfmtJson.read(file) as? OxfmtJson.Object else null) ?: OxfmtJson.Object()

    /**
     * Creates the event [event] in a namespace of the pack's own, with the Ogg Vorbis [file] as its one sound, which it
     * copies to the event's path under `sounds/`. Returns the files it wrote.
     */
    fun create(
        pack: File,
        event: String,
        file: String,
    ): List<File> {
        val (namespace, _) = parse(event)
        if (namespace == "minecraft") {
            throw FieldRefusal("sound", "$event is a vanilla sound event. Override it instead, or create it in the namespace nordtal.")
        }
        if (!EVENT.matches(event.substringAfter(':'))) {
            throw FieldRefusal(
                "sound",
                "A sound event is words of a to z, 0 to 9, _ and - with dots between them, such as nordtal:ui.click.",
            )
        }
        if (event in events(pack)) throw FieldRefusal("sound", "The pack has the sound event $event already.")
        return change(pack, event, ogg(file)) { _, sound -> OxfmtJson.Object().also { it["sounds"] = array(listOfNotNull(sound)) } }
    }

    /**
     * Overrides the vanilla event [event] that [vanilla] lists. With [replace], the event plays only the pack's sounds and
     * starts from vanilla's sounds and subtitle, or from [file] alone where one is given; without, [file] plays beside
     * vanilla's sounds. Returns the files it wrote.
     */
    fun override(
        pack: File,
        vanilla: () -> VanillaSounds,
        event: String,
        replace: Boolean,
        file: String?,
    ): List<File> {
        val (namespace, _) = parse(event)
        if (namespace != "minecraft") throw FieldRefusal("sound", "$event is no vanilla sound event. Create it instead.")
        if (event in events(pack)) throw FieldRefusal("sound", "The pack overrides $event already.")
        val original = vanilla().events()[event] ?: throw FieldRefusal("sound", "Minecraft has no sound event $event.")
        if (!replace && file == null) throw FieldsNeeded(mapOf("file" to "sound"))
        val bytes = file?.let(::ogg)
        return change(pack, event, bytes) { _, sound ->
            OxfmtJson.Object().also { entry ->
                if (replace) entry["replace"] = true
                entry["sounds"] =
                    array(
                        if (sound != null) {
                            listOf(sound)
                        } else {
                            (original["sounds"] as List<*>).map { s ->
                                if (s is Map<*, *>) {
                                    map(s + ("name" to qualified(s["name"].toString())))
                                } else {
                                    qualified(s.toString())
                                }
                            }
                        },
                    )
                if (replace) (original["subtitle"] as? String)?.let { entry["subtitle"] = it }
            }
        }
    }

    /**
     * Changes one thing of the event [event]: with [file] it adds that Ogg Vorbis file as a sound, with [name] a sound
     * that Minecraft or the pack holds, with [remove] it removes the sound [at], with [fields], a JSON object, it sets or,
     * where a value is null, clears those fields of the sound [at], and with [replace] whether the event replaces
     * vanilla's sounds. Returns the files it wrote or deleted.
     */
    fun edit(
        pack: File,
        vanilla: () -> VanillaSounds,
        event: String,
        at: Int?,
        fields: String?,
        file: String?,
        name: String?,
        remove: Boolean,
        replace: String?,
    ): List<File> {
        val entry = events(pack)[event] ?: throw FieldRefusal("sound", "The pack has no sound event $event.")
        val sounds = (entry["sounds"] as? List<*>).orEmpty()

        fun index(): Int {
            val index = at ?: throw FieldRefusal("at", "Set -Pat to the index of a sound.")
            if (index !in sounds.indices) throw FieldRefusal("at", "$event has no sound $index.")
            return index
        }
        if (file !=
            null
        ) {
            return change(pack, event, ogg(file)) { root, sound -> root.also { (it["sounds"] as MutableList<Any?>) += sound } }
        }
        if (name != null) {
            val qualified = qualified(name)
            if (qualified !in own(pack) &&
                !vanilla().has(qualified)
            ) {
                throw FieldRefusal("name", "Neither the pack nor Minecraft has the sound $name.")
            }
            return change(
                pack,
                event,
                null,
            ) { root, _ -> root.also { (it["sounds"] as MutableList<Any?>) += map(mapOf("name" to qualified)) } }
        }
        if (remove) {
            val index = index()
            val gone = names(mapOf("sounds" to listOf(sounds[index])))
            val written = change(pack, event, null) { root, _ -> root.also { (it["sounds"] as MutableList<Any?>).removeAt(index) } }
            return written + unused(pack, gone)
        }
        if (replace != null) {
            if (replace != "true" && replace != "false") throw FieldRefusal("replace", "Set -Preplace to true or false.")
            if (!event.startsWith("minecraft:")) throw FieldRefusal("replace", "Only a vanilla sound event replaces vanilla's sounds.")
            return change(pack, event, null) { root, _ ->
                root.also {
                    if (replace == "true") {
                        it.remove("replace")
                        val subtitle = it.remove("subtitle")
                        val sounds = it.remove("sounds")
                        it["replace"] = true
                        it["sounds"] = sounds
                        it["subtitle"] = subtitle ?: vanilla().events()[event]?.get("subtitle")
                        if (it["subtitle"] == null) it.remove("subtitle")
                    } else {
                        it.remove("replace")
                        it.remove("subtitle")
                        val copied = names(vanilla().events()[event].orEmpty()).toSet()
                        (it["sounds"] as MutableList<Any?>).removeAll { sound ->
                            names(mapOf("sounds" to listOf(sound))).any(copied::contains)
                        }
                    }
                }
            }.let { written ->
                val key = entry["subtitle"] as String?
                if (replace == "true" || key == null) written else written + subtitleText(pack, event, key)
            }
        }
        val values =
            fields?.let { text ->
                runCatching { JsonSlurper().parseText(text) as? Map<*, *> }.getOrNull()
                    ?: throw FieldRefusal("fields", "-Pfields=$text is no JSON object.")
            } ?: throw FieldRefusal("fields", "Set one of -Pfile, -Pname, -Premove, -Preplace or -Pfields.")
        val index = index()
        values.forEach { (field, value) -> problem(field.toString(), value)?.let { throw FieldRefusal("fields", "$field $it.") } }
        return change(pack, event, null) { root, _ ->
            root.also {
                val list = it["sounds"] as MutableList<Any?>
                val sound = (list[index] as? Map<*, *>)?.let(::map) ?: map(mapOf("name" to list[index].toString()))
                values.forEach { (field, value) -> if (value == null) sound.remove(field) else sound[field.toString()] = number(value) }
                list[index] = sound
            }
        }
    }

    private val EVENT = Regex("""[a-z0-9_-]+(\.[a-z0-9_-]+)*""")
    private val NAME = Regex("""([a-z0-9_.-]+:)?[a-z0-9_.-]+(/[a-z0-9_.-]+)*""")

    /** Whether [name] is a sound name the client resolves, such as `nordtal:ui/click` or `random/anvil_land`. */
    fun isName(name: String): Boolean = NAME.matches(name)

    /** Why [value] is no value of the sound field [field], or null where it is one. */
    fun problem(
        field: String,
        value: Any?,
    ): String? {
        if (field !in FIELDS) return "is no field of a sound; the fields are ${FIELDS.joinToString()}"
        if (value == null) return null
        return when (field) {
            "volume", "pitch" -> {
                "must be a number above 0".takeIf { (value as? Number)?.toDouble()?.let { it > 0 } != true }
            }

            "weight", "attenuation_distance" -> {
                "must be a whole number from 1".takeIf {
                    !(value is Int || value is Long) ||
                        (value as Number).toLong() < 1
                }
            }

            "stream", "preload" -> {
                "must be true or false".takeIf { value !is Boolean }
            }

            else -> {
                "must be file or event".takeIf { value != "file" && value != "event" }
            }
        }
    }

    /** [value] as the pack's JSON writes it: a whole number that came as a decimal, such as `2.0` for a pitch, stays one. */
    private fun number(value: Any?): Any? = if (value is Number && value !is Int && value !is Long) BigDecimal(value.toString()) else value

    /**
     * Sets the subtitle text of [event] in [language] to [value], or with [delete] removes the subtitle and, where no
     * other event names it, its text. The key is `subtitles.<namespace>.<event>` unless the event names one that the
     * pack's language files hold.
     */
    fun subtitle(
        pack: File,
        event: String,
        language: String?,
        value: String?,
        delete: Boolean,
    ): List<File> {
        val events = events(pack)
        val entry = events[event] ?: throw FieldRefusal("sound", "The pack has no sound event $event.")
        val key = entry["subtitle"] as String?
        if (delete) {
            key ?: throw FieldRefusal("sound", "$event has no subtitle.")
            val written = change(pack, event, null) { root, _ -> root.also { it.remove("subtitle") } }
            return written + subtitleText(pack, event, key)
        }
        if (event.startsWith("minecraft:") && entry["replace"] != true) {
            throw FieldRefusal(
                "sound",
                "The client shows vanilla's subtitle for $event unless it replaces vanilla's sounds. Set -Preplace=true first.",
            )
        }
        // A key that no language file of the pack holds is vanilla's, which other events share, so the pack takes its own.
        val own = "subtitles.${event.substringBefore(':')}.${event.substringAfter(':').replace('/', '.')}"
        val subtitle = key?.takeIf { it == own || Texts.work(pack).values.any { texts -> it in texts } } ?: own
        val texts = Texts.edit(pack, subtitle, language, value, false)
        return texts +
            if (subtitle != key) change(pack, event, null) { root, _ -> root.also { it["subtitle"] = subtitle } } else emptyList()
    }

    /** Deletes the text of the subtitle [key] that [event] named, where the pack holds it and no other event names it. */
    private fun subtitleText(
        pack: File,
        event: String,
        key: String,
    ): List<File> {
        val shared = events(pack).any { (other, e) -> other != event && e["subtitle"] == key }
        return if (shared || Texts.work(pack).values.none { key in it }) emptyList() else Texts.edit(pack, key, null, null, true)
    }

    /**
     * Deletes the event [event] from the pack, with each `.ogg` of the pack that no other event names and the text of its
     * subtitle where no other event names that. Returns the files it deleted or wrote.
     */
    fun delete(
        pack: File,
        event: String,
    ): List<File> {
        val events = events(pack)
        val entry = events[event] ?: throw FieldRefusal("sound", "The pack has no sound event $event.")
        val key = entry["subtitle"] as String?
        val texts = key?.let { subtitleText(pack, event, it) }.orEmpty()
        val committed = at(pack, "HEAD").keys
        val left =
            work(pack).mapValues { (_, values) -> values - event }.filter { (path, values) ->
                values.isNotEmpty() ||
                    path in committed
            }
        val written = put(pack, left, setOf(event))
        return written + unused(pack, names(entry)) + texts
    }

    /** Deletes each of the sound files [names] that the pack holds and no event names any more, and returns them. */
    private fun unused(
        pack: File,
        names: List<String>,
    ): List<File> {
        val named = events(pack).values.flatMap(::names).toSet()
        val gone = names.filter { it !in named }.map { file(pack, it) }.filter { it.isFile }
        AtomicFiles.write(gone.associateWith { null })
        return gone
    }

    /** The name of each `.ogg` file that the pack holds. */
    private fun own(pack: File): Set<String> =
        pack
            .resolve("src/assets")
            .walkTopDown()
            .filter { it.isFile }
            .mapNotNull { OGG.matchEntire(it.relativeTo(pack).invariantSeparatorsPath) }
            .map { "${it.groupValues[1]}:${it.groupValues[2]}" }
            .toSet()

    /** The file of the pack that holds the sound [name], whether it exists or not. */
    private fun file(
        pack: File,
        name: String,
    ): File = pack.resolve("src/assets/${name.substringBefore(':')}/sounds/${name.substringAfter(':')}.ogg")

    /**
     * Writes the entry that [entry] makes of [event]'s, or of a new one, and the Ogg Vorbis [bytes] where given, at the
     * next free path for [event]: `sounds/<event, each . as />.ogg` in its namespace, under `sounds/nordtal/` in `minecraft`
     * so it never hides a vanilla file, and with `_2` and up where that path is taken.
     */
    private fun change(
        pack: File,
        event: String,
        bytes: ByteArray?,
        entry: (OxfmtJson.Object, OxfmtJson.Object?) -> OxfmtJson.Object,
    ): List<File> {
        val (namespace, name) = parse(event)
        val folder = (if (namespace == "minecraft") "nordtal/" else "") + name.replace('.', '/')
        val taken = own(pack) + events(pack).values.flatMap(::names)
        val sound =
            bytes?.let {
                generateSequence(1) { it + 1 }
                    .map { n -> "$namespace:$folder" + if (n == 1) "" else "_$n" }
                    .first { it !in taken && !file(pack, it).exists() }
            }
        val file = pack.resolve(path(namespace))
        val root = read(file)
        val old = (root[name] as? Map<*, *>)?.let(::map) ?: OxfmtJson.Object()
        if (old["sounds"] !is MutableList<*>) old["sounds"] = array((old["sounds"] as? List<*>).orEmpty())
        root[name] = entry(old, sound?.let { map(mapOf("name" to it)) })
        val contents = linkedMapOf<File, ByteArray?>(file to OxfmtJson.format(root).toByteArray())
        if (sound != null) contents[file(pack, sound)] = bytes
        AtomicFiles.write(contents)
        return contents.keys.toList()
    }

    private fun parse(event: String): Pair<String, String> =
        try {
            Identifier.parse(event)
        } catch (e: FieldRefusal) {
            throw FieldRefusal("sound", e.message ?: "$event is no sound event.", e)
        }

    /** The Ogg Vorbis file at [path]. */
    private fun ogg(path: String): ByteArray {
        val file = File(path)
        if (!file.isFile) throw FieldRefusal("file", "$path is no file.")
        val bytes = file.readBytes()
        if (!OggVorbis.isVorbis(bytes)) {
            throw FieldRefusal(
                "file",
                "${file.name} is no Ogg Vorbis file, the one format Minecraft plays. Export it as Ogg Vorbis (.ogg) in an audio editor such as Audacity first.",
            )
        }
        return bytes
    }

    /**
     * Decodes the sound [at] of the event [event] to a WAV under `build/sounds/`, played at its volume and pitch, and returns
     * it. The event is the pack's, or vanilla's where the pack has none; a sound is the pack's file, or vanilla's.
     */
    fun play(
        pack: File,
        vanilla: () -> VanillaSounds,
        event: String,
        at: Int?,
    ): File {
        val entry = events(pack)[event] ?: vanilla().events()[event] ?: throw FieldRefusal("sound", "There is no sound event $event.")
        val sounds = (entry["sounds"] as? List<*>).orEmpty()
        val index = at ?: 0
        val sound = sounds.getOrNull(index) ?: throw FieldRefusal("at", "$event has no sound $index.")
        val fields = sound as? Map<*, *> ?: mapOf("name" to sound)
        val name = fields["name"].toString()
        if (fields["type"] == "event") throw FieldRefusal("at", "Sound $index of $event plays the event $name; play that one.")
        val own = file(pack, qualified(name))
        val bytes = if (own.isFile) own.readBytes() else vanilla().sound(qualified(name)).readBytes()
        val wav =
            OggVorbis.wav(
                OggVorbis.decode(bytes),
                (fields["volume"] as? Number)?.toDouble() ?: 1.0,
                (fields["pitch"] as? Number)?.toDouble() ?: 1.0,
            )
        val digest =
            MessageDigest
                .getInstance("SHA-1")
                .digest(wav)
                .take(8)
                .joinToString("") { "%02x".format(it) }
        val out = pack.resolve("build/sounds/$digest.wav")
        if (!out.isFile) AtomicFiles.write(mapOf(out to wav))
        return out
    }

    private fun map(from: Map<*, *>): OxfmtJson.Object =
        OxfmtJson.Object(expanded = false).also { copy ->
            from.forEach { (k, v) ->
                copy[k.toString()] =
                    v
            }
        }

    private fun array(from: List<*>): OxfmtJson.Array = OxfmtJson.Array().also { copy -> from.forEach { copy += it } }
}
