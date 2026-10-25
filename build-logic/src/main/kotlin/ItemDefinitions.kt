package eu.nordtal.season.build

import groovy.json.JsonSlurper

/**
 * The rules of an item definition under `assets/<namespace>/items/`, for every model type, property, tint source and
 * special model of the client jar of 26.2. The client's codecs in `net.minecraft.client.renderer.item` and
 * `net.minecraft.client.color.item` are the source of each table: a name without `?` is required.
 */
object ItemDefinitions {
    private val TRANSFORMATION = "transformation?"

    private val DEFINITION = fields("model", "hand_animation_on_swap?", "oversized_in_gui?", "swap_animation_scale?")

    private val MODELS =
        mapOf(
            "empty" to fields(),
            "model" to fields("model", "tints?", TRANSFORMATION),
            "range_dispatch" to fields("property", "entries", "scale?", "fallback?", TRANSFORMATION),
            "special" to fields("model", "base", TRANSFORMATION),
            "composite" to fields("models", TRANSFORMATION),
            "bundle/selected_item" to fields(),
            "select" to fields("property", "cases", "fallback?", TRANSFORMATION),
            "condition" to fields("property", "on_true", "on_false", TRANSFORMATION),
        )

    private val SELECT =
        mapOf(
            "custom_model_data" to fields("index?"),
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
            "custom_model_data" to fields("index?"),
            "using_item" to fields(),
            "broken" to fields(),
            "damaged" to fields(),
            "fishing_rod/cast" to fields(),
            "has_component" to fields("component", "ignore_default?"),
            "bundle/has_selected_item" to fields(),
            "selected" to fields(),
            "carried" to fields(),
            "extended_view" to fields(),
            "keybind_down" to fields("keybind"),
            "view_entity" to fields(),
            "component" to fields("predicate", "value"),
        )

    private val RANGE =
        mapOf(
            "custom_model_data" to fields("index?"),
            "bundle/fullness" to fields(),
            "damage" to fields("normalize?"),
            "cooldown" to fields(),
            "time" to fields("wobble?", "source"),
            "compass" to fields("wobble?", "target"),
            "crossbow/pull" to fields(),
            "use_cycle" to fields("period?"),
            "use_duration" to fields("remaining?"),
            "count" to fields("normalize?"),
        )

    private val TINTS =
        mapOf(
            "custom_model_data" to fields("index?", "default"),
            "constant" to fields("value"),
            "dye" to fields("default"),
            "grass" to fields("temperature", "downfall"),
            "firework" to fields("default"),
            "potion" to fields("default"),
            "map_color" to fields("default"),
            "team" to fields("default"),
        )

    private val SPECIALS =
        mapOf(
            "bell" to fields(),
            "banner" to fields("color", "attachment?"),
            "book" to fields("open_angle", "page1", "page2"),
            "conduit" to fields(),
            "chest" to fields("texture", "openness?", "chest_type?"),
            "copper_golem_statue" to fields("texture", "pose"),
            "head" to fields("kind", "texture?", "animation?"),
            "player_head" to fields(),
            "shulker_box" to fields("texture", "openness?"),
            "shield" to fields(),
            "trident" to fields(),
            "decorated_pot" to fields(),
            "end_cube" to fields("effect"),
        )

    /** Every model type, with the properties, tint sources and special models of each, for the Designer's editor. */
    val types: Map<String, Any> =
        mapOf(
            "models" to MODELS.keys.map { "minecraft:$it" },
            "select" to SELECT.keys.map { "minecraft:$it" },
            "condition" to CONDITION.keys.map { "minecraft:$it" },
            "range_dispatch" to RANGE.keys.map { "minecraft:$it" },
            "tints" to TINTS.keys.map { "minecraft:$it" },
            "special" to SPECIALS.keys.map { "minecraft:$it" },
        )

    private class Fields(
        val required: Set<String>,
        val optional: Set<String>,
    )

    private fun fields(vararg names: String) =
        Fields(names.filterNot { it.endsWith("?") }.toSet(), names.filter { it.endsWith("?") }.map { it.removeSuffix("?") }.toSet())

    /** The problems of [definition], the parsed JSON of an item definition, each with the place it occurs. */
    fun problems(definition: Any?): List<String> {
        val problems = mutableListOf<String>()
        val root = definition as? Map<*, *> ?: return listOf("an item definition is a JSON object")
        shape(root, DEFINITION, emptySet(), "item", problems)
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
        val property = mapOf("select" to SELECT, "condition" to CONDITION, "range_dispatch" to RANGE)[type]
        val extra = property?.let { propertyFields(model, it, "$where > property", problems) } ?: emptySet()
        shape(model, MODELS.getValue(type), extra, where, problems)
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
                    entry(case, "when", "$where > cases[$index]", problems)
                }
            }

            "range_dispatch" -> {
                list(model["entries"], "$where > entries", problems).forEachIndexed { index, entry ->
                    entry(entry, "threshold", "$where > entries[$index]", problems)
                }
            }
        }
        if ("fallback" in model) model(model["fallback"], "$where > fallback", problems)
    }

    private fun entry(
        node: Any?,
        key: String,
        where: String,
        problems: MutableList<String>,
    ) {
        val entry = node as? Map<*, *> ?: return run { problems += "$where is an object" }
        shape(entry, fields(key, "model"), emptySet(), where, problems)
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
    ): Set<String> {
        val fields =
            id(model["property"])?.let { table[it] }
                ?: return emptySet<String>().also { problems += "$where is unknown: ${model["property"]}" }
        fields.required.filterNot { it in model }.forEach { problems += "$where needs '$it'" }
        return fields.required + fields.optional
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
        val seen = mutableSetOf<String>()

        fun check(id: String) {
            if (id.startsWith("builtin/") || !seen.add(id)) return
            val path =
                runCatching {
                    Identifier.resource(
                        id,
                        "models",
                        ".json",
                    )
                }.getOrElse { return run { problems += "model ${it.message}" } }
            val bytes = read(path) ?: return run { problems += "model $id is in neither the pack nor the client jar" }
            val model = JsonSlurper().parse(bytes) as? Map<*, *> ?: return
            (model["parent"] as? String)?.let { check(it) }
            (model["textures"] as? Map<*, *>)?.values?.forEach { value ->
                val texture = value as? String ?: (value as? Map<*, *>)?.get("sprite") as? String ?: return@forEach
                if (!texture.startsWith("#") && !sprite(texture)) {
                    problems += "texture $texture of model $id is in neither the pack nor the client jar"
                }
            }
        }
        models(definition).forEach { check(it) }
        return problems
    }

    private fun typed(
        node: Any?,
        table: Map<String, Fields>,
        where: String,
        problems: MutableList<String>,
    ) {
        val entry = node as? Map<*, *> ?: return run { problems += "$where is an object" }
        val fields = id(entry["type"])?.let { table[it] } ?: return run { problems += "$where has no known 'type': ${entry["type"]}" }
        shape(entry, fields, setOf("type"), where, problems)
    }

    private fun shape(
        node: Map<*, *>,
        fields: Fields,
        extra: Set<String>,
        where: String,
        problems: MutableList<String>,
    ) {
        fields.required.filterNot { it in node }.forEach { problems += "$where needs '$it'" }
        val known = fields.required + fields.optional + extra + "type"
        node.keys
            .map { it as String }
            .filterNot { it in known || it.startsWith("_") }
            .forEach { problems += "$where has no field '$it'" }
    }

    private fun type(model: Map<*, *>): String? = id(model["type"])?.takeIf { it in MODELS }

    /** The path of a `minecraft` id, written with or without its namespace. */
    private fun id(value: Any?): String? = (value as? String)?.removePrefix("minecraft:")?.takeIf { ':' !in it }
}
