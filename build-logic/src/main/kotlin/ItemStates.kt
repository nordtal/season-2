package eu.nordtal.season.build

import groovy.json.JsonOutput
import kotlin.math.ceil

/**
 * The states of an item definition that a `/give` can show: one per case of each select, condition and range whose
 * property a component sets, such as custom model data, damage, a trim or a block state. Each is `{ label, give }`.
 */
object ItemStates {
    /** The most states one item lists. */
    private const val MOST = 64

    private val DATA = listOf("floats", "flags", "strings")

    /** The max damage that a range of normalized damage gives, so a threshold to three decimals is met exactly. */
    private const val MAX_DAMAGE = 1000

    /** The charged projectiles that show each case of `charge_type`. */
    private val CHARGES =
        mapOf("none" to "[]", "arrow" to "[{id:\"minecraft:arrow\"}]", "rocket" to "[{id:\"minecraft:firework_rocket\"}]")

    /** The value that /give accepts for each component that has_component shows as present. */
    private val PRESENT =
        mapOf(
            "charged_projectiles" to CHARGES.getValue("arrow"),
            "damage" to "1",
            "dyed_color" to "11546150",
            "lodestone_tracker" to "{}",
        )

    private data class State(
        val labels: List<String> = emptyList(),
        val components: Map<String, String> = emptyMap(),
        val data: Map<String, Map<Int, Any>> = emptyMap(),
    ) {
        fun with(
            label: String,
            vararg set: Pair<String, String>,
        ) = copy(labels = labels + label, components = components - set.map { "!${it.first}" }.toSet() + set)

        /** This state with [component] removed from the item, written `!component` in the give. */
        fun without(
            label: String,
            component: String,
        ) = copy(labels = labels + label, components = components - component + ("!$component" to ""))

        fun withData(
            label: String,
            list: String,
            index: Int,
            value: Any,
        ) = copy(
            labels = labels + label,
            components = if ("custom_model_data" in components) components else components + ("custom_model_data" to ""),
            data = data + (list to (data[list].orEmpty() + (index to value))),
        )
    }

    /** The states of [definition], the item [id]'s definition, the plain item last. */
    fun of(
        id: String,
        definition: Any?,
    ): List<Map<String, String>> {
        val base = if (id.startsWith("minecraft:")) State() else State(components = mapOf("item_model" to JsonOutput.toJson(id)))
        val item = if (id.startsWith("minecraft:")) id else "minecraft:paper"
        return walk((definition as? Map<*, *>)?.get("model"), base)
            .map { mapOf("label" to it.labels.joinToString(", "), "give" to give(item, it)) }
            .distinctBy { it["give"] }
            .take(MOST)
    }

    private fun give(
        item: String,
        state: State,
    ): String {
        if (state.components.isEmpty()) return item
        val components =
            state.components.map { (name, value) ->
                when {
                    name.startsWith("!") -> name
                    name == "custom_model_data" -> "$name=${data(state.data)}"
                    else -> "$name=$value"
                }
            }
        return "$item[${components.joinToString(",")}]"
    }

    /** The custom model data of [lists], each list filled up to its highest index with the value a missing entry has. */
    private fun data(lists: Map<String, Map<Int, Any>>): String =
        DATA.filter { it in lists }.joinToString(",", "{", "}") { list ->
            val values = lists.getValue(list)
            val empty: Any = mapOf("floats" to 0.0, "flags" to false, "strings" to "")[list]!!
            "$list:" + (0..values.keys.max()).map { values[it] ?: empty }.joinToString(",", "[", "]") { JsonOutput.toJson(it) }
        }

    private fun walk(
        node: Any?,
        state: State,
    ): List<State> {
        val model = node as? Map<*, *> ?: return listOf(state)
        val property = (model["property"] as? String)?.removePrefix("minecraft:")
        val index = (model["index"] as? Number)?.toInt() ?: 0
        return when ((model["type"] as? String)?.removePrefix("minecraft:")) {
            "composite" -> {
                (model["models"] as? List<*>).orEmpty().flatMap { walk(it, state) }
            }

            "condition" -> {
                val on =
                    when (property) {
                        "damaged" -> state.with("damaged", "max_stack_size" to "1", "max_damage" to "2", "damage" to "1")
                        "broken" -> state.with("broken", "max_stack_size" to "1", "max_damage" to "2", "damage" to "1")
                        "has_component" -> present(model["component"] as? String, state)
                        "custom_model_data" -> state.withData("flags[$index]", "flags", index, true)
                        else -> state
                    }
                (on?.let { walk(model["on_true"], it) }).orEmpty() + walk(model["on_false"], absent(model, property, state))
            }

            "select" -> {
                val cases = (model["cases"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
                cases.flatMap { case ->
                    val whens = case["when"] as? List<*> ?: listOf(case["when"])
                    whens.flatMap { value -> walk(case["model"], select(model, property, index, value, state)) }
                } + if ("fallback" in model) walk(model["fallback"], state) else emptyList()
            }

            "range_dispatch" -> {
                val entries = (model["entries"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
                val scale = (model["scale"] as? Number)?.toDouble() ?: 1.0
                entries.flatMap { entry ->
                    val threshold = (entry["threshold"] as? Number)?.toDouble() ?: 0.0
                    val on =
                        when (property) {
                            "custom_model_data" -> state.withData("floats[$index]=${threshold / scale}", "floats", index, threshold / scale)
                            "damage" -> damage(model["normalize"] as? Boolean ?: true, threshold / scale, state)
                            else -> state
                        }
                    walk(entry["model"], on)
                } + if ("fallback" in model) walk(model["fallback"], state) else emptyList()
            }

            else -> {
                listOf(state)
            }
        }
    }

    private fun select(
        model: Map<*, *>,
        property: String?,
        index: Int,
        value: Any?,
        state: State,
    ): State {
        val text = value as? String ?: JsonOutput.toJson(value)
        return when (property) {
            "custom_model_data" -> {
                state.withData("strings[$index]=$text", "strings", index, text)
            }

            "component" -> {
                val component = model["component"] as? String ?: return state
                state.with("$component=$text", component.removePrefix("minecraft:") to JsonOutput.toJson(value))
            }

            "trim_material" -> {
                state.with("trim_material=$text", "trim" to "{material:${JsonOutput.toJson(text)},pattern:\"minecraft:coast\"}")
            }

            "charge_type" -> {
                state.with("charge_type=$text", "charged_projectiles" to (CHARGES[text] ?: return state))
            }

            "block_state" -> {
                val key = model["block_state_property"] as? String ?: return state
                val previous =
                    state.components["block_state"]
                        ?.removeSurrounding("{", "}")
                        ?.let { "$it," }
                        .orEmpty()
                state.with("$key=$text", "block_state" to "{$previous$key:${JsonOutput.toJson(text)}}")
            }

            else -> {
                state
            }
        }
    }

    /** [state] with [component] set, as a condition on `has_component` reads it, or null when [PRESENT] has no value for it. */
    private fun present(
        component: String?,
        state: State,
    ): State? =
        component?.let { name ->
            val key = name.removePrefix("minecraft:")
            PRESENT[key]?.let { state.with(name, key to it) }
        }

    /**
     * [state] as the false case of a condition reads it. A condition on `has_component` counts the default component of
     * the item's prototype as present unless `ignore_default` is true, so that state removes the component.
     */
    private fun absent(
        model: Map<*, *>,
        property: String?,
        state: State,
    ): State {
        val component = (model["component"] as? String).takeIf { property == "has_component" } ?: return state
        if (model["ignore_default"] == true) return state
        return state.without("!$component", component.removePrefix("minecraft:"))
    }

    /**
     * [state] with the damage at which the property `damage` reaches [value], a share of the max damage if [normalize].
     * A damageable item must not stack, so the state also sets `max_stack_size` to 1.
     */
    private fun damage(
        normalize: Boolean,
        value: Double,
        state: State,
    ): State {
        // The tolerance keeps a threshold such as 0.07, which is 70.00000000000001 thousandths as a double, at 70.
        val damage = ceil(value * (if (normalize) MAX_DAMAGE else 1) - 1e-9).toInt().coerceAtLeast(0)
        val max = if (normalize) MAX_DAMAGE else damage + 1
        return state.with("damage=$value", "max_stack_size" to "1", "max_damage" to "$max", "damage" to "$damage")
    }
}
