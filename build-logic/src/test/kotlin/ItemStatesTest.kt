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
    fun eachTrimMaterialOfAVanillaArmourIsATrimWithTheCoastPattern() {
        // The shape of minecraft:iron_chestplate in the 26.2 client jar, cut to two of its eleven cases
        val chestplate =
            """{ "type": "minecraft:select", "property": "minecraft:trim_material",
                 "cases": [{ "model": { "type": "minecraft:model", "model": "minecraft:item/iron_chestplate_quartz_trim" }, "when": "minecraft:quartz" },
                           { "model": { "type": "minecraft:model", "model": "minecraft:item/iron_chestplate_iron_trim" }, "when": "minecraft:iron" }],
                 "fallback": { "type": "minecraft:model", "model": "minecraft:item/iron_chestplate" } }"""
        assertEquals(
            listOf(
                "trim_material=minecraft:quartz" to
                    "minecraft:iron_chestplate[trim={material:\"minecraft:quartz\",pattern:\"minecraft:coast\"}]",
                "trim_material=minecraft:iron" to
                    "minecraft:iron_chestplate[trim={material:\"minecraft:iron\",pattern:\"minecraft:coast\"}]",
                "" to "minecraft:iron_chestplate",
            ),
            states("minecraft:iron_chestplate", chestplate).map { it["label"] to it["give"] },
        )
    }

    @Test
    fun aChargeTypeIsTheChargedProjectileABrokenItemHasItsLastDamageAndAPresentComponentIsSet() {
        val crossbow =
            """{ "type": "minecraft:select", "property": "minecraft:charge_type",
                 "cases": [{ "when": "arrow", "model": $lamp }, { "when": "rocket", "model": $lamp }, { "when": "none", "model": $lamp }] }"""
        assertEquals(
            listOf(
                "charge_type=arrow" to "minecraft:crossbow[charged_projectiles=[{id:\"minecraft:arrow\"}]]",
                "charge_type=rocket" to "minecraft:crossbow[charged_projectiles=[{id:\"minecraft:firework_rocket\"}]]",
                "charge_type=none" to "minecraft:crossbow[charged_projectiles=[]]",
            ),
            states("minecraft:crossbow", crossbow).map { it["label"] to it["give"] },
        )
        val elytra = """{ "type": "minecraft:condition", "property": "minecraft:broken", "on_true": $lamp, "on_false": $lamp }"""
        assertEquals(
            listOf("broken" to "minecraft:elytra[max_damage=2,damage=1]", "" to "minecraft:elytra"),
            states("minecraft:elytra", elytra).map { it["label"] to it["give"] },
        )
        val armour =
            """{ "type": "minecraft:condition", "property": "minecraft:has_component", "component": "minecraft:dyed_color",
                 "on_true": $lamp, "on_false": $lamp }"""
        assertEquals(
            listOf("minecraft:dyed_color" to "minecraft:wolf_armor[minecraft:dyed_color=11546150]", "" to "minecraft:wolf_armor"),
            states("minecraft:wolf_armor", armour).map { it["label"] to it["give"] },
        )
        val compass =
            """{ "type": "minecraft:condition", "property": "minecraft:has_component", "component": "minecraft:lodestone_tracker",
                 "on_true": $lamp, "on_false": $lamp }"""
        assertEquals(
            "minecraft:compass[minecraft:lodestone_tracker={}]",
            states("minecraft:compass", compass).first()["give"],
        )
    }

    @Test
    fun aPresentComponentHasAValueOfItsTypeAndAComponentWithoutAKnownValueShowsOnlyItsAbsence() {
        fun present(component: String) =
            states(
                "nordtal:lamp",
                """{ "type": "minecraft:condition", "property": "minecraft:has_component", "component": "$component",
                     "on_true": $lamp, "on_false": $lamp }""",
            ).map { it["label"] to it["give"] }
        assertEquals(
            listOf(
                "minecraft:damage" to "minecraft:paper[item_model=\"nordtal:lamp\",minecraft:damage=1]",
                "" to "minecraft:paper[item_model=\"nordtal:lamp\"]",
            ),
            present("minecraft:damage"),
        )
        assertEquals(
            "minecraft:paper[item_model=\"nordtal:lamp\",minecraft:charged_projectiles=[{id:\"minecraft:arrow\"}]]",
            present("minecraft:charged_projectiles").first().second,
        )
        assertEquals(listOf("" to "minecraft:paper[item_model=\"nordtal:lamp\"]"), present("minecraft:custom_name"))
    }

    @Test
    fun aRangeOfDamageSetsTheDamageThatReachesEachThresholdAndARangeWithoutFallbackHasNoOtherState() {
        val normalized =
            """{ "type": "minecraft:range_dispatch", "property": "minecraft:damage",
                 "entries": [{ "threshold": 0.07, "model": $lamp }, { "threshold": 0.58, "model": $lamp }] }"""
        assertEquals(
            listOf(
                "damage=0.07" to "minecraft:shield[max_damage=1000,damage=70]",
                "damage=0.58" to "minecraft:shield[max_damage=1000,damage=580]",
            ),
            states("minecraft:shield", normalized).map { it["label"] to it["give"] },
        )
        val counted =
            """{ "type": "minecraft:range_dispatch", "property": "minecraft:damage", "normalize": false, "scale": 2,
                 "entries": [{ "threshold": 3, "model": $lamp }], "fallback": $lamp }"""
        assertEquals(
            listOf("damage=1.5" to "minecraft:shield[max_damage=3,damage=2]", "" to "minecraft:shield"),
            states("minecraft:shield", counted).map { it["label"] to it["give"] },
        )
    }

    @Test
    fun aPropertyThatNoComponentSetsAddsNoState() {
        val condition = """{ "type": "minecraft:condition", "property": "minecraft:using_item", "on_true": $lamp, "on_false": $lamp }"""
        assertEquals(listOf(""), states("nordtal:lamp", condition).map { it["label"] })
    }
}
