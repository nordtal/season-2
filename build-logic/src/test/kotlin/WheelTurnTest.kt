package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class WheelTurnTest {
    private val variants = WheelSpin.offsets(WheelLayout.PLACES)

    @Test
    fun everyVariantSharesTheStartAndRestsOnItsWinner() {
        val first = WheelTurn.ticks(variants[0]).take(WheelTurn.SHARED)
        for (e in variants) {
            val ticks = WheelTurn.ticks(e)
            assertEquals(first, ticks.take(WheelTurn.SHARED), "variant $e")
            assertEquals(WheelTurn.length(e), ticks.size)
            assertTrue(ticks.size <= WheelTurn.COOLDOWN)
            assertEquals(WheelTurn.discFrame(WheelTurn.distance(e)), WheelTurn.discFrame(ticks.last().angle), "variant $e")
        }
    }

    @Test
    fun theWheelNeverRunsFasterThanThreeFramesATickAndSlowsDownEvenly() {
        for (e in variants) {
            val angles = WheelTurn.ticks(e).map { it.angle }
            for (i in 1 until angles.size) {
                assertTrue(angles[i] - angles[i - 1] <= WheelTurn.START_SPEED + 1e-9, "variant $e at tick $i")
            }
            val dips = WheelTurn.distance(e) + WheelTurn.PIN - 120.0
            val beforeDips = (0 until WheelTurn.length(e)).takeWhile { WheelTurn.angle(e, it + 0.5) < dips }
            val speeds = beforeDips.map { WheelTurn.speed(e, it.toDouble()) }
            for (i in 1 until speeds.size) assertTrue(speeds[i] <= speeds[i - 1] + 1e-9, "variant $e speeds up at tick $i")
            assertTrue(WheelTurn.length(e) >= 160, "variant $e turns long")
        }
    }

    @Test
    fun theSlowingNeverJumpsWhereTheVariantsPart() {
        for (e in variants) {
            val slowing =
                (WheelTurn.SHARED - 5 until WheelTurn.SHARED + 40).map {
                    WheelTurn.speed(e, it - 0.5) -
                        WheelTurn.speed(e, it + 0.5)
                }
            for (i in 1 until slowing.size) {
                assertTrue(abs(slowing[i] - slowing[i - 1]) <= 0.002, "variant $e slowing jumps at tick ${WheelTurn.SHARED - 5 + i}")
            }
        }
    }

    @Test
    fun thePointerStopsAgainstTheFarPinAndTheWheelRocksBackWithoutCrossingIt() {
        for (e in variants) {
            val ticks = WheelTurn.ticks(e)
            val pin = WheelTurn.distance(e) + WheelTurn.PIN
            assertTrue(ticks.all { it.angle <= pin + 1e-9 }, "variant $e passes the pin")
            val top = ticks.indexOfFirst { it.angle >= pin - 1e-9 }
            assertEquals(WheelTurn.Pointer.TICK1, ticks[top].pointer, "variant $e bends on the pin")
            val back = ticks.drop(top)
            for (i in 1 until back.size) assertTrue(back[i].angle <= back[i - 1].angle + 1e-9, "variant $e rocks forward again")
            assertEquals(WheelTurn.Pointer.REST, ticks.last().pointer)
        }
    }

    @Test
    fun aPegClicksAtMostOnceATickAndTheFarPinOnce() {
        for (e in variants) {
            val clicks = WheelTurn.clicks(e)
            assertEquals(clicks.sorted().distinct(), clicks)
            assertTrue(clicks.size <= WheelTurn.TURNS * WheelLayout.PLACES + e + 1, "variant $e")
            assertTrue(clicks.size >= WheelTurn.TURNS * WheelLayout.PLACES, "variant $e")
            assertTrue(clicks.last() < WheelTurn.length(e) - WheelTurn.SETTLE, "variant $e clicks while rocking back: $clicks")
        }
    }

    @Test
    fun thePrizesPopInFasterThanBefore() {
        assertTrue(WheelTurn.OPEN_TICKS <= 25)
        assertEquals(null, WheelSpin.popIn(11, 0))
        assertEquals(WheelSpin.waiting(11), WheelSpin.popIn(11, WheelTurn.OPEN_TICKS))
    }

    @Test
    fun aTurnedWheelPlacesItsPrizesAsTheSameWheelAtRest() {
        for (e in 0 until WheelLayout.PLACES) {
            for (b in 0 until WheelLayout.PLACES) {
                val turned = WheelSpin(e).prizeRest(b)
                val rest = WheelSpin.waiting((b + e) % WheelLayout.PLACES)
                assertEquals(rest, turned, "variant $e body $b")
            }
        }
    }

    @Test
    fun variantsSpreadOverThePlaces() {
        assertEquals(listOf(0, 3, 6, 9), WheelSpin.offsets(4))
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 7, 8, 10, 11), WheelSpin.offsets(10))
    }

    @Test
    fun aRunShowsFromHalfwayIntoItsLastTickAndTheRestIsTheFallback() {
        val runs = WheelSpin.runs(listOf("a", "a", "b", "r", "r"), "r", 5)
        assertEquals(listOf(6 to "a", 7 to "b"), runs)
        assertEquals((200 - 1.5) / 200, (WheelSpin.threshold(1, 200) as Number).toDouble(), 1e-4)
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
