package eu.nordtal.season.build

import groovy.json.JsonSlurper

/**
 * The rules of an item definition under `assets/<namespace>/items/`, for every model type, property, tint source and
 * special model of the client jar of 26.2. The client's codecs in `net.minecraft.client.renderer.item` and
 * `net.minecraft.client.color.item` are the source of each table: a name without `?` is required.
 */
object ItemDefinitions {
    private const val TRANSFORMATION = "transformation:json?"

    private val DEFINITION =
        fields("model:model", "hand_animation_on_swap:boolean?", "oversized_in_gui:boolean?", "swap_animation_scale:number?")

    private val CASE = fields("when:json", "model:model")

    private val ENTRY = fields("threshold:number", "model:model")

    private val MODELS =
        mapOf(
            "empty" to fields(),
            "model" to fields("model:id", "tints:tints?", TRANSFORMATION),
            "range_dispatch" to fields("property:property", "entries:entries", "scale:number?", "fallback:model?", TRANSFORMATION),
            "special" to fields("model:special", "base:id", TRANSFORMATION),
            "composite" to fields("models:models", TRANSFORMATION),
            "bundle/selected_item" to fields(),
            "select" to fields("property:property", "cases:cases", "fallback:model?", TRANSFORMATION),
            "condition" to fields("property:property", "on_true:model", "on_false:model", TRANSFORMATION),
        )

    private val SELECT =
        mapOf(
            "custom_model_data" to fields("index:number?"),
            "main_hand" to fields(),
            "charge_type" to fields(),
            "trim_material" to fields(),
            "block_state" to fields("block_state_property"),
            "display_context" to fields(),
            "local_time" to fields("pattern", "locale?", "time_zone?"),
            "context_entity_type" to fields(),
            "context_dimension" to fields(),
            "component" to fields("component"),
        )

    private val CONDITION =
        mapOf(
            "custom_model_data" to fields("index:number?"),
            "using_item" to fields(),
            "broken" to fields(),
            "damaged" to fields(),
            "fishing_rod/cast" to fields(),
            "has_component" to fields("component", "ignore_default:boolean?"),
            "bundle/has_selected_item" to fields(),
            "selected" to fields(),
            "carried" to fields(),
            "extended_view" to fields(),
            "keybind_down" to fields("keybind"),
            "view_entity" to fields(),
            "component" to fields("predicate", "value:json"),
        )

    private val RANGE =
        mapOf(
            "custom_model_data" to fields("index:number?"),
            "bundle/fullness" to fields(),
            "damage" to fields("normalize:boolean?"),
            "cooldown" to fields(),
            "time" to fields("wobble:boolean?", "source"),
            "compass" to fields("wobble:boolean?", "target"),
            "crossbow/pull" to fields(),
            "use_cycle" to fields("period:number?"),
            "use_duration" to fields("remaining:boolean?"),
            "count" to fields("normalize:boolean?"),
        )

    private val TINTS =
        mapOf(
            "custom_model_data" to fields("index:number?", "default:json"),
            "constant" to fields("value:json"),
            "dye" to fields("default:json"),
            "grass" to fields("temperature:number", "downfall:number"),
            "firework" to fields("default:json"),
            "potion" to fields("default:json"),
            "map_color" to fields("default:json"),
            "team" to fields("default:json"),
        )

    private val SPECIALS =
        mapOf(
            "bell" to fields(),
            "banner" to fields("color", "attachment?"),
            "book" to fields("open_angle:number", "page1:number", "page2:number"),
            "conduit" to fields(),
            "chest" to fields("texture", "openness:number?", "chest_type?"),
            "copper_golem_statue" to fields("texture", "pose"),
            "head" to fields("kind", "texture?", "animation:number?"),
            "player_head" to fields(),
            "shulker_box" to fields("texture", "openness:number?"),
            "shield" to fields(),
            "trident" to fields(),
            "decorated_pot" to fields(),
            "end_cube" to fields("effect"),
        )

    /** The table of the properties of each model type that has one. */
    private val PROPERTIES = mapOf("select" to SELECT, "condition" to CONDITION, "range_dispatch" to RANGE)

    /** The class of a value of each kind that holds one. */
    private val VALUES =
        mapOf("id" to String::class, "text" to String::class, "number" to Number::class, "boolean" to Boolean::class, "json" to Any::class)

    /** The element kind of each list kind. */
    private val LISTS = mapOf("models" to "model", "cases" to "case", "entries" to "entry", "tints" to "tint")

    /**
     * The fields of every kind of object, each `{ name, kind, required }`, for the Designer's editor.
     * `kinds` holds the value kinds, the element kind of each list kind and the table of types of each typed kind.
     */
    val types: Map<String, Any> =
        mapOf(
            "item" to DEFINITION.described(),
            "case" to CASE.described(),
            "entry" to ENTRY.described(),
            "models" to
                MODELS.map { (type, fields) ->
                    listOfNotNull(
                        "type" to "minecraft:$type",
                        type.takeIf { it in PROPERTIES }?.let { "properties" to it },
                        "fields" to fields.described(),
                    ).toMap()
                },
            "select" to table(SELECT),
            "condition" to table(CONDITION),
            "range_dispatch" to table(RANGE),
            "tints" to table(TINTS),
            "special" to table(SPECIALS),
            "kinds" to
                mapOf(
                    "values" to VALUES.mapValues { it.value.simpleName!!.lowercase() },
                    "lists" to LISTS,
                    "tables" to mapOf("model" to "models", "tint" to "tints", "special" to "special"),
                ),
        )

    private fun table(entries: Map<String, Fields>) =
        entries.map { (type, fields) ->
            mapOf(
                "type" to "minecraft:$type",
                "fields" to fields.described(),
            )
        }

    /** One field: its [name], the [kind] of its value and whether it is [required]. */
    private class Field(
        val name: String,
        val kind: String,
        val required: Boolean,
    )

    private class Fields(
        val all: List<Field>,
    ) {
        fun described() = all.map { mapOf("name" to it.name, "kind" to it.kind, "required" to it.required) }
    }

    /** Fields written as `name:kind`, with `?` at the end when optional. A field without a kind holds text. */
    private fun fields(vararg names: String) =
        Fields(
            names.map {
                val name = it.removeSuffix("?")
                Field(name.substringBefore(':'), name.substringAfter(':', "text"), !it.endsWith("?"))
            },
        )

    /** The problems of [definition], the parsed JSON of an item definition, each with the place it occurs. */
    fun problems(definition: Any?): List<String> {
        val problems = mutableListOf<String>()
        val root = definition as? Map<*, *> ?: return listOf("an item definition is a JSON object")
        shape(root, DEFINITION.all, "item", problems)
        model(root["model"], "model", problems)
        return problems
    }

    /** The model ids that [definition] draws: the `model` of each `minecraft:model` and the `base` of each special model. */
    fun models(definition: Any?): List<String> {
        val ids = mutableListOf<String>()

        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    when (type(node)) {
                        "model" -> (node["model"] as? String)?.let { ids += it }
                        "special" -> (node["base"] as? String)?.let { ids += it }
                    }
                    node.forEach { (key, value) -> if (key != "model" || type(node) != "special") walk(value) }
                }

                is List<*> -> {
                    node.forEach { walk(it) }
                }
            }
        }
        walk((definition as? Map<*, *>)?.get("model"))
        return ids.distinct()
    }

    private fun model(
        node: Any?,
        where: String,
        problems: MutableList<String>,
    ) {
        val model = node as? Map<*, *> ?: return run { problems += "$where is a model object" }
        val type = type(model) ?: return run { problems += "$where has no known 'type': ${model["type"]}" }
        val property = PROPERTIES[type]?.let { propertyFields(model, it, "$where > property", problems) }.orEmpty()
        shape(model, MODELS.getValue(type).all + property, where, problems)
        when (type) {
            "model" -> {
                (model["tints"] as? List<*>)?.forEachIndexed { index, tint -> typed(tint, TINTS, "$where > tints[$index]", problems) }
            }

            "special" -> {
                typed(model["model"], SPECIALS, "$where > model", problems)
            }

            "composite" -> {
                list(model["models"], "$where > models", problems).forEachIndexed {
                    index,
                    it,
                    ->
                    model(it, "$where > models[$index]", problems)
                }
            }

            "condition" -> {
                listOf("on_true", "on_false").filter { it in model }.forEach { model(model[it], "$where > $it", problems) }
            }

            "select" -> {
                list(model["cases"], "$where > cases", problems).forEachIndexed { index, case ->
                    entry(case, CASE, "$where > cases[$index]", problems)
                }
            }

            "range_dispatch" -> {
                list(model["entries"], "$where > entries", problems).forEachIndexed { index, entry ->
                    entry(entry, ENTRY, "$where > entries[$index]", problems)
                }
            }
        }
        if ("fallback" in model) model(model["fallback"], "$where > fallback", problems)
    }

    private fun entry(
        node: Any?,
        fields: Fields,
        where: String,
        problems: MutableList<String>,
    ) {
        val entry = node as? Map<*, *> ?: return run { problems += "$where is an object" }
        shape(entry, fields.all, where, problems)
        if ("model" in entry) model(entry["model"], "$where > model", problems)
    }

    private fun list(
        node: Any?,
        where: String,
        problems: MutableList<String>,
    ): List<*> = node as? List<*> ?: emptyList<Any>().also { if (node != null) problems += "$where is a list" }

    private fun propertyFields(
        model: Map<*, *>,
        table: Map<String, Fields>,
        where: String,
        problems: MutableList<String>,
    ): List<Field> {
        val fields =
            id(model["property"])?.let { table[it] }?.all
                ?: return emptyList<Field>().also { problems += "$where is unknown: ${model["property"]}" }
        fields.filter { it.required && it.name !in model }.forEach { problems += "$where needs '${it.name}'" }
        return fields.map { Field(it.name, it.kind, false) }
    }

    /**
     * The problems of the references of [definition]: each model it draws and the parents of each must be in the pack or
     * the client jar, which [read] reads by a path under `assets/`, and each of their textures must be a [sprite].
     */
    fun missing(
        definition: Any?,
        read: (String) -> ByteArray?,
        sprite: (String) -> Boolean,
    ): List<String> {
        val problems = mutableListOf<String>()
        models(definition).forEach { id ->
            for ((model, _, texture) in textures(id, read, problems)) {
                if (!sprite(texture)) problems += "texture $texture of model $model is in neither the pack nor the client jar"
            }
        }
        return problems.distinct()
    }

    /** A texture that [model] names under [key]. */
    data class ModelTexture(
        val model: String,
        val key: String,
        val texture: String,
    )

    /**
     * The textures that [model] and its parents name, the child's first, read with [read] by a path under `assets/`. A
     * reference to another key is left out, and each model that cannot be read is added to [problems].
     */
    fun textures(
        model: String,
        read: (String) -> ByteArray?,
        problems: MutableList<String>,
    ): List<ModelTexture> {
        val textures = mutableListOf<ModelTexture>()
        val seen = mutableSetOf<String>()
        var id: String? = model
        while (id != null && !id.startsWith("builtin/") && seen.add(id)) {
            val path =
                runCatching { Identifier.resource(id, "models", ".json") }
                    .getOrElse { return textures.also { _ -> problems += "model ${it.message}" } }
            val bytes = read(path) ?: return textures.also { problems += "model $id is in neither the pack nor the client jar" }
            val json = runCatching { JsonSlurper().parse(bytes) }.getOrNull() as? Map<*, *> ?: break
            (json["textures"] as? Map<*, *>)?.forEach { (key, value) ->
                val texture = value as? String ?: (value as? Map<*, *>)?.get("sprite") as? String
                if (texture != null && !texture.startsWith("#")) textures += ModelTexture(id!!, key as String, texture)
            }
            id = json["parent"] as? String
        }
        return textures
    }

    /** The fields of [node], an object of [kind]: its type's and its property's for a model, a tint or a special model. */
    private fun fieldsOf(
        kind: String,
        node: Map<*, *>,
    ): Fields =
        when (kind) {
            "item" -> {
                DEFINITION
            }

            "case" -> {
                CASE
            }

            "entry" -> {
                ENTRY
            }

            else -> {
                val type = id(node["type"])
                val own = mapOf("model" to MODELS, "tint" to TINTS, "special" to SPECIALS)[kind]?.get(type)?.all.orEmpty()
                Fields(own + PROPERTIES[type]?.get(id(node["property"]))?.all.orEmpty())
            }
        }

    /**
     * Changes [definition] at [at], such as `model/cases/0`, and returns false when nothing is there. [fields] sets
     * fields, a null removes one, [add] puts them into a new object there and [remove] removes the object. A new type
     * or property drops each field it does not allow or gives another kind.
     * @throws FieldsNeeded when a required value is still missing
     */
    fun edit(
        definition: MutableMap<String, Any?>,
        at: String,
        fields: Map<String, Any?>?,
        add: String?,
        remove: Boolean,
    ): Boolean {
        var parent: Any? = null
        var node: Any? = definition
        var kind = "item"
        for (key in at.split('/').filter { it.isNotEmpty() }) {
            parent = node
            when (node) {
                is Map<*, *> -> {
                    kind = fieldsOf(kind, node).all.firstOrNull { it.name == key }?.kind ?: return false
                    node = node[key]
                }

                is List<*> -> {
                    kind = LISTS[kind] ?: return false
                    node = key.toIntOrNull()?.let { node.getOrNull(it) }
                }

                else -> {
                    return false
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        val target = node as? MutableMap<String, Any?> ?: return false
        if (remove) {
            val key = at.substringAfterLast('/')
            when (parent) {
                is MutableList<*> -> parent.removeAt(key.toInt())
                is MutableMap<*, *> -> parent.remove(key)
                else -> return false
            }
            return true
        }
        if (add == null) {
            change(target, kind, fields.orEmpty())
            return true
        }
        val field = fieldsOf(kind, target).all.firstOrNull { it.name == add }?.kind
        if (field == "model") {
            if (target[add] != null) return false
            val model = empty()
            change(model, field, fields.orEmpty())
            target[add] = model
            return true
        }
        val list = LISTS[field] ?: return false
        val element = linkedMapOf<String, Any?>()
        change(element, list, fields.orEmpty())
        @Suppress("UNCHECKED_CAST")
        (target.getOrPut(add) { mutableListOf<Any?>() } as MutableList<Any?>) += element
        return true
    }

    /** A model that draws nothing, which a model starts as */
    private fun empty() = linkedMapOf<String, Any?>("type" to "minecraft:empty")

    private fun change(
        node: MutableMap<String, Any?>,
        kind: String,
        fields: Map<String, Any?>,
    ) {
        val type = node["type"]
        val property = node["property"]
        val before = fieldsOf(kind, node).all.associate { it.name to it.kind }
        fields.forEach { (key, value) -> if (value == null) node.remove(key) else node[key] = value }
        val properties = if (kind == "model") PROPERTIES[id(node["type"])] else null
        if (properties != null && id(node["property"]) !in properties) node["property"] = "minecraft:" + properties.keys.first()
        if (node["type"] != type || node["property"] != property) {
            val allowed = fieldsOf(kind, node).all.associate { it.name to it.kind } + ("type" to "text")
            node.keys.removeIf { key ->
                !key.startsWith("_") && (key !in allowed || (key in before && key !in fields && before[key] != allowed[key]))
            }
        }
        for (field in fieldsOf(kind, node).all.filter { it.name in fields && fields[it.name] != null }) {
            misfit(field, node[field.name])?.let { throw FieldRefusal(field.name, it) }
        }
        for (field in fieldsOf(kind, node).all.filter { it.required && it.name !in node }) {
            when (field.kind) {
                "model" -> node[field.name] = empty()
                "special" -> node[field.name] = linkedMapOf("type" to "minecraft:" + SPECIALS.keys.first())
                in LISTS -> node[field.name] = mutableListOf<Any?>()
            }
        }
        val needed = fieldsOf(kind, node).all.filter { it.required && it.name !in node && it.kind in VALUES }
        if (needed.isNotEmpty()) throw FieldsNeeded(needed.associate { it.name to it.kind })
    }

    private fun typed(
        node: Any?,
        table: Map<String, Fields>,
        where: String,
        problems: MutableList<String>,
    ) {
        val entry = node as? Map<*, *> ?: return run { problems += "$where is an object" }
        val fields = id(entry["type"])?.let { table[it] } ?: return run { problems += "$where has no known 'type': ${entry["type"]}" }
        shape(entry, fields.all, where, problems)
    }

    private fun shape(
        node: Map<*, *>,
        fields: List<Field>,
        where: String,
        problems: MutableList<String>,
    ) {
        fields.filter { it.required && it.name !in node }.forEach { problems += "$where needs '${it.name}'" }
        fields.filter { it.name in node }.forEach { field -> misfit(field, node[field.name])?.let { problems += "$where > $it" } }
        val known = fields.map { it.name }.toSet() + "type"
        node.keys
            .map { it as String }
            .filterNot { it in known || it.startsWith("_") }
            .forEach { problems += "$where has no field '$it'" }
    }

    /** Why [value] cannot be the value of [field], or null when it can. */
    private fun misfit(
        field: Field,
        value: Any?,
    ): String? = VALUES[field.kind]?.takeUnless { it.isInstance(value) }?.let { "${field.name} is no ${field.kind}: $value" }

    private fun type(model: Map<*, *>): String? = id(model["type"])?.takeIf { it in MODELS }

    /** The path of a `minecraft` id, written with or without its namespace. */
    private fun id(value: Any?): String? = (value as? String)?.removePrefix("minecraft:")?.takeIf { ':' !in it }
}
