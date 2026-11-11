package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WheelTurnTest {
    private val variants = WheelSpin.offsets(WheelLayout.PLACES)

    @Test
    fun theBlurStreaksOnlyEverMoveOn() {
        for (e in variants) {
            val blurs = WheelTurn.ticks(e).mapNotNull { it.blur }
            for (i in 1 until blurs.size) {
                val step = Math.floorMod(blurs[i] - blurs[i - 1], WheelTurn.BLUR_TEXTURES)
                assertTrue(step != WheelTurn.BLUR_TEXTURES - 1, "variant $e steps back at blur tick $i: $blurs")
            }
        }
    }

    @Test
    fun theTurnStartsBlurredAndEndsSharpOnTheWinner() {
        for (e in variants) {
            val ticks = WheelTurn.ticks(e)
            assertEquals(WheelTurn.TICKS, ticks.size)
            assertTrue(ticks.take(10).any { it.blur != null }, "variant $e starts fast")
            assertTrue(ticks.takeLast(WheelTurn.SETTLE).all { it.blur == null }, "variant $e settles sharp")
            assertEquals(WheelTurn.discFrame(WheelTurn.distance(e)), WheelTurn.discFrame(ticks.last().angle), "variant $e")
        }
    }

    @Test
    fun aPegClicksAtMostOnceATickAndThePegPastTheWinnerClicksTwice() {
        for (e in variants) {
            val clicks = WheelTurn.clicks(e)
            assertEquals(clicks.sorted().distinct(), clicks)
            // Three whole turns and e places forward, one peg past the winner, and back over it; a fast tick that
            // passes two pegs clicks once.
            assertTrue(clicks.size <= WheelTurn.TURNS * WheelLayout.PLACES + e + 2, "variant $e")
            assertTrue(clicks.size >= WheelTurn.TURNS * WheelLayout.PLACES, "variant $e")
            assertTrue(clicks.takeLast(2).all { it > WheelTurn.TICKS / 2 }, "variant $e ends on the pegs: $clicks")
        }
    }

    @Test
    fun theRollBackMirrorsThePointer() {
        for (e in variants) {
            val back = WheelTurn.ticks(e).takeLast(WheelTurn.SETTLE).map { it.pointer }
            assertTrue(back.none { it == WheelTurn.Pointer.TICK1 || it == WheelTurn.Pointer.TICK2 }, "variant $e: $back")
        }
    }

    @Test
    fun variantsSpreadOverThePlaces() {
        assertEquals(listOf(0, 3, 6, 9), WheelSpin.offsets(4))
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 7, 8, 10, 11), WheelSpin.offsets(10))
    }

    @Test
    fun aRunShowsFromHalfwayIntoItsLastTickAndTheRestIsTheFallback() {
        val runs = WheelSpin.runs(listOf("a", "a", "b", "r", "r"), "r")
        assertEquals(listOf(1 to "a", 2 to "b"), runs)
        assertEquals((WheelSpin.COOLDOWN - 1.5) / WheelSpin.COOLDOWN, (WheelSpin.threshold(1) as Number).toDouble(), 1e-4)
    }

    @Test
    fun aLeafTakesTheGuiCaseAndCarriesTheCollapsedTransformation() {
        val model = mapOf("type" to "minecraft:model", "model" to "minecraft:item/shield")
        val node =
            mapOf(
                "type" to "minecraft:condition",
                "transformation" to listOf(1, 0, 0, 2, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1),
                "on_true" to mapOf("type" to "minecraft:empty"),
                "on_false" to
                    mapOf(
                        "type" to "minecraft:select",
                        "property" to "minecraft:display_context",
                        "cases" to listOf(mapOf("when" to listOf("gui", "fixed"), "model" to model)),
                        "fallback" to mapOf("type" to "minecraft:empty"),
                    ),
            )
        val leaf = WheelVanilla.leaf(node)
        assertEquals("minecraft:item/shield", leaf["model"])
        assertEquals(2.0, ((leaf["transformation"] as List<*>)[3] as Number).toDouble(), 1e-9)
    }
}
