package eu.nordtal.season.build

import groovy.json.JsonOutput

/**
 * The states of an item definition that a `/give` can show: one per case of each select, condition and range whose
 * property a component sets, such as custom model data, damage, a component or a block state. Each is `{ label, give }`.
 */
object ItemStates {
    /** The most states one item lists. */
    private const val MOST = 64

    private val DATA = listOf("floats", "flags", "strings")

    private data class State(
        val labels: List<String> = emptyList(),
        val components: Map<String, String> = emptyMap(),
        val data: Map<String, Map<Int, Any>> = emptyMap(),
    ) {
        fun with(
            label: String,
            vararg set: Pair<String, String>,
        ) = copy(labels = labels + label, components = components + set)

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
                if (name == "custom_model_data") "$name=${data(state.data)}" else "$name=$value"
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
                        "damaged" -> state.with("damaged", "max_damage" to "2", "damage" to "1")
                        "custom_model_data" -> state.withData("flags[$index]", "flags", index, true)
                        else -> state
                    }
                walk(model["on_true"], on) + walk(model["on_false"], state)
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
                        if (property == "custom_model_data") {
                            state.withData("floats[$index]=${threshold / scale}", "floats", index, threshold / scale)
                        } else {
                            state
                        }
                    walk(entry["model"], on)
                } + walk(model["fallback"], state)
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
                state.with("$component=$text", component to JsonOutput.toJson(value))
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
}
