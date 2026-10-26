package eu.nordtal.season.build

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ItemStatesTest {
    private fun states(
        id: String,
        model: String,
    ) = ItemStates.of(id, JsonSlurper().parseText("""{ "model": $model }"""))

    private val lamp = """{ "type": "minecraft:model", "model": "nordtal:item/lamp" }"""

    @Test
    fun aPackItemIsGivenAsPaperWithItsItemModel() {
        assertEquals(
            listOf(mapOf("label" to "", "give" to "minecraft:paper[item_model=\"nordtal:lamp\"]")),
            states("nordtal:lamp", lamp),
        )
    }

    @Test
    fun aVanillaItemIsGivenAsItself() {
        assertEquals(listOf(mapOf("label" to "", "give" to "minecraft:apple")), states("minecraft:apple", lamp))
    }

    @Test
    fun eachCaseOfCustomModelDataIsAState() {
        val select =
            """{ "type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 1,
                 "cases": [{ "when": "lit", "model": $lamp }, { "when": ["off", "broken"], "model": $lamp }], "fallback": $lamp }"""
        assertEquals(
            listOf(
                "strings[1]=lit" to "minecraft:paper[item_model=\"nordtal:lamp\",custom_model_data={strings:[\"\",\"lit\"]}]",
                "strings[1]=off" to "minecraft:paper[item_model=\"nordtal:lamp\",custom_model_data={strings:[\"\",\"off\"]}]",
                "strings[1]=broken" to "minecraft:paper[item_model=\"nordtal:lamp\",custom_model_data={strings:[\"\",\"broken\"]}]",
                "" to "minecraft:paper[item_model=\"nordtal:lamp\"]",
            ),
            states("nordtal:lamp", select).map { it["label"] to it["give"] },
        )
    }

    @Test
    fun conditionsComponentsBlockStatesAndRangesNestAndRepeatedStatesAreGivenOnce() {
        val model =
            """{ "type": "minecraft:condition", "property": "minecraft:damaged", "on_false": $lamp,
                 "on_true": { "type": "minecraft:condition", "property": "minecraft:custom_model_data", "on_false": $lamp,
                   "on_true": { "type": "minecraft:select", "property": "minecraft:block_state", "block_state_property": "facing",
                     "cases": [{ "when": "north", "model": { "type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data",
                       "scale": 2, "entries": [{ "threshold": 1, "model": $lamp }], "fallback": $lamp } }] } } }"""
        assertEquals(
            listOf(
                "damaged, flags[0], facing=north, floats[0]=0.5" to
                    "minecraft:paper[item_model=\"nordtal:lamp\",max_damage=2,damage=1,custom_model_data={floats:[0.5],flags:[true]},block_state={facing:\"north\"}]",
                "damaged, flags[0], facing=north" to
                    "minecraft:paper[item_model=\"nordtal:lamp\",max_damage=2,damage=1,custom_model_data={flags:[true]},block_state={facing:\"north\"}]",
                "damaged" to "minecraft:paper[item_model=\"nordtal:lamp\",max_damage=2,damage=1]",
                "" to "minecraft:paper[item_model=\"nordtal:lamp\"]",
            ),
            states("nordtal:lamp", model).map { it["label"] to it["give"] },
        )
    }

    @Test
    fun aSelectedComponentIsSetToTheCaseValueAndASelectWithoutFallbackHasNoOtherState() {
        val select =
            """{ "type": "minecraft:select", "property": "minecraft:component", "component": "minecraft:rarity",
                 "cases": [{ "when": "epic", "model": $lamp }] }"""
        assertEquals(
            listOf("minecraft:rarity=epic" to "minecraft:apple[minecraft:rarity=\"epic\"]"),
            states("minecraft:apple", select).map { it["label"] to it["give"] },
        )
    }

    @Test
    fun aPropertyThatNoComponentSetsAddsNoState() {
        val condition = """{ "type": "minecraft:condition", "property": "minecraft:using_item", "on_true": $lamp, "on_false": $lamp }"""
        assertEquals(listOf(""), states("nordtal:lamp", condition).map { it["label"] })
    }
}
