package eu.nordtal.season.build

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Where the wheel's parts sit, in GUI pixels. The dialog's body column holds [BODIES] item bodies 10 apart: 1 x 1 bodies
 * on the column's middle and body 5, the plate's row, a 100 x 1 item at the row's left and middle beside its 86 x 26
 * description. Every body draws over the same rectangle, so each part is placed from its body's own origin.
 */
internal object WheelLayout {
    const val BODIES = 14
    const val DISC = 96
    const val PLACES = WheelTurn.PLACES
    private const val RADIUS = 34.0
    private const val PLATE_ROW = 5

    /** The disc canvas's top-left from body 0's origin. */
    val CANVAS = intArrayOf(-93, 26)
    val POINTER = intArrayOf(40, -8)

    /** The off plate's top-left on the disc canvas, and its size. */
    val PLATE = intArrayOf(107, 33)
    const val PLATE_WIDTH = 75
    const val PLATE_HEIGHT = 17

    /** The rectangle every body spans, on the disc canvas: the art with a margin, and for the overlay the plate. */
    val ANCHOR = intArrayOf(-2, -10, 98, 106)
    val OVERLAY_ANCHOR = intArrayOf(-2, -10, 184, 106)

    /**
     * The plate row's height: its description's padding 4 above and below three text lines, as the client lays it out
     * (measured in the dev client, the same in every language and with no spin left).
     */
    const val PLATE_ROW_HEIGHT = 4 + 3 * 9 + 4

    /** Body [body]'s item origin from body 0's; the plate row's 1-pixel item sits at its row's vertical middle. */
    fun origin(body: Int): IntArray =
        when {
            body < PLATE_ROW -> intArrayOf(0, 11 * body)
            body == PLATE_ROW -> intArrayOf(-93, 11 * PLATE_ROW + (PLATE_ROW_HEIGHT - 1) / 2)
            else -> intArrayOf(0, 11 * PLATE_ROW + PLATE_ROW_HEIGHT + 10 + 11 * (body - PLATE_ROW - 1))
        }

    /** The disc canvas's top-left from body [body]'s origin. */
    fun canvas(body: Int): IntArray = origin(body).let { intArrayOf(CANVAS[0] - it[0], CANVAS[1] - it[1]) }

    /** The upright prize's top-left on the disc canvas at [angle] degrees clockwise from the top, whole pixels. */
    fun upright(angle: Double): IntArray {
        val a = Math.toRadians(angle)
        return intArrayOf(floor(40 + RADIUS * sin(a) + 0.5).toInt(), floor(40 - RADIUS * cos(a) + 0.5).toInt())
    }
}

/** One prize's picture: drawn at ([x], [y]) on the disc canvas, from the new key or the [old] one. */
internal data class PrizeAt(
    val x: Int,
    val y: Int,
    val old: Boolean,
)

/** The disc's picture: a blurred texture, or a sharp frame with or without the winner's mark. */
internal data class DiscAt(
    val blur: Int?,
    val frame: Int,
    val mark: Boolean,
)

/** The overlay's picture: the pointer, each body's count where its prize shows, and the off plate. */
internal data class OverlayAt(
    val pointer: WheelTurn.Pointer,
    val counts: List<PrizeAt?>,
    val plate: Boolean,
)

/**
 * Every tick of one spin in turn variant [e]: the swap, then the turn. Cooldown tick t of [COOLDOWN] shows the swap
 * while t < [WheelTurn.SWAP_TICKS], then turn tick t - SWAP_TICKS; with no cooldown left each body shows its rest.
 */
internal class WheelSpin(
    private val e: Int,
) {
    private val turn = WheelTurn.ticks(e)

    fun prize(
        body: Int,
        t: Int,
    ): PrizeAt? {
        if (t >= WheelTurn.SWAP_TICKS) {
            val tick = turn[t - WheelTurn.SWAP_TICKS]
            if (tick.blur != null) return null
            return at(30.0 * body + tick.angle, 0, false)
        }
        val pop = WheelTurn.SWAP_STEP * body
        return when {
            t < pop -> at(30.0 * body, 0, true)
            t - pop < WheelTurn.POP_HOP.size -> at(30.0 * body, WheelTurn.POP_HOP[t - pop], false)
            else -> at(30.0 * body, 0, false)
        }
    }

    fun prizeRest(body: Int): PrizeAt = at(30.0 * body + WheelTurn.distance(e), 0, false)

    fun disc(
        parity: Int,
        t: Int,
    ): DiscAt {
        if (t < WheelTurn.SWAP_TICKS) return DiscAt(null, WheelTurn.discFrame(30.0 * parity), false)
        val tick = turn[t - WheelTurn.SWAP_TICKS]
        return if (tick.blur != null) DiscAt(tick.blur, 0, false) else DiscAt(null, WheelTurn.discFrame(30.0 * parity + tick.angle), false)
    }

    fun discRest(parity: Int): DiscAt = DiscAt(null, WheelTurn.discFrame(30.0 * parity + WheelTurn.distance(e)), true)

    fun overlay(t: Int): OverlayAt {
        val pointer = if (t < WheelTurn.SWAP_TICKS) WheelTurn.Pointer.REST else turn[t - WheelTurn.SWAP_TICKS].pointer
        return OverlayAt(pointer, (0 until WheelLayout.PLACES).map { prize(it, t) }, true)
    }

    fun overlayRest(): OverlayAt = OverlayAt(WheelTurn.Pointer.REST, (0 until WheelLayout.PLACES).map { prizeRest(it) }, false)

    private fun at(
        angle: Double,
        hop: Int,
        old: Boolean,
    ): PrizeAt = WheelLayout.upright(angle).let { PrizeAt(it[0], it[1] - hop, old) }

    companion object {
        const val COOLDOWN = WheelTurn.SWAP_TICKS + WheelTurn.TICKS

        /** The runs of equal pictures as (last tick, picture), without the trailing ones that equal [rest]. */
        fun <K> runs(
            pictures: List<K>,
            rest: K,
        ): List<Pair<Int, K>> {
            val out = ArrayList<Pair<Int, K>>()
            pictures.forEachIndexed { t, k -> if (out.isNotEmpty() && out.last().second == k) out[out.size - 1] = t to k else out += t to k }
            while (out.isNotEmpty() && out.last().second == rest) out.removeAt(out.size - 1)
            return out
        }

        /** The cooldown fraction from which a run that ends at tick [last] shows: halfway into its last tick. */
        fun threshold(last: Int): Any = WheelMath.number((COOLDOWN - last - 0.5) / COOLDOWN)

        /** The turn variants of [count]: their end places spread over the 12 as evenly as whole places allow. */
        fun offsets(count: Int): List<Int> = (0 until count).map { floor(it * WheelLayout.PLACES.toDouble() / count + 0.5).toInt() }
    }
}

/** A prize on the pack's list: its item id without `minecraft:` and the amount the wheel hands out. */
internal data class WheelPrize(
    val key: String,
    val amount: Int,
)

/**
 * The wheel's pack files and the plugin's timetable, from the prize list, the client jar and the off plate's label in
 * each language. [files] maps each path in the pack to its bytes; [properties] is `nordtal/wheel/wheel.properties`.
 */
internal class WheelPack(
    private val vanilla: WheelVanilla,
    prizes: List<WheelPrize>,
    private val labels: Map<String, String>,
) {
    private val amounts = prizes.map { it.amount }.filter { it > 1 }.distinct().sorted()
    private val groups: List<Pair<DoubleArray, List<Pair<String, Map<String, Any?>>>>>
    private val mixed: List<Pair<String, Map<String, Any?>>>

    init {
        val byDisplay = LinkedHashMap<String, Pair<DoubleArray, MutableList<Pair<String, Map<String, Any?>>>>>()
        val split = ArrayList<Pair<String, Map<String, Any?>>>()
        for (key in prizes.map { it.key }.distinct()) {
            val definition = vanilla.definition(key) ?: throw IllegalStateException("the wheel's prize list names minecraft:$key, which the client jar lacks")
            val leaf = WheelVanilla.leaf(definition)
            val displays = WheelVanilla.refs(leaf).map { vanilla.display(it) }.distinctBy { tag(it) }
            when (displays.size) {
                0 -> throw IllegalStateException("minecraft:$key draws no model in a GUI slot")
                1 -> byDisplay.getOrPut(tag(displays[0])) { displays[0] to ArrayList() }.second += key to leaf
                else -> split += key to leaf
            }
        }
        groups = byDisplay.values.map { it.first to it.second.toList() }
        mixed = split
    }

    private fun tag(m: DoubleArray): String = m.joinToString(",") { "%.5f".format(it) }

    private fun model(name: String) = "nordtal:item/wheel/$name"

    private fun json(value: Any?): String = WheelMath.json(value).toString()

    /** A part of the art: model [name]'s 16 x 16 square stretched over [w] x [h] at ([x], [y]) on the disc canvas. */
    private fun part(
        name: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        body: Int,
        z: Double,
    ): Map<String, Any?> {
        val c = WheelLayout.canvas(body)
        return mapOf("type" to "minecraft:model", "model" to model(name), "transformation" to WheelMath.list(box(c[0] + x, c[1] + y, w, h, z)))
    }

    private fun anchor(
        body: Int,
        rect: IntArray,
    ) = part("anchor", rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1], body, -0.4)

    private fun move(
        body: Int,
        x: Int,
        y: Int,
    ): DoubleArray = WheelLayout.canvas(body).let { WheelMath.translate((it[0] + x) / 16.0, -(it[1] + y) / 16.0, 0.0) }

    private fun select(
        index: Int,
        cases: List<Map<String, Any?>>,
        fallback: Any?,
        transformation: DoubleArray? = null,
    ): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["type"] = "minecraft:select"
        out["property"] = "minecraft:custom_model_data"
        out["index"] = index
        out["cases"] = cases
        out["fallback"] = fallback
        if (transformation != null) out["transformation"] = WheelMath.list(transformation)
        return out
    }

    private val leafJson = HashMap<String, Raw>()

    private fun leafOf(
        key: String,
        leaf: Map<String, Any?>,
    ): Raw = leafJson.getOrPut(key) { Raw(json(leaf)) }

    /** A prize body's picture: one select on its key, each display group moved by its own L. */
    private fun prizeNode(
        body: Int,
        at: PrizeAt?,
    ): String {
        if (at == null) return json(EMPTY)
        val index = if (at.old) 2 else 0
        val m = move(body, at.x, at.y)
        val cases = ArrayList<Map<String, Any?>>()
        for ((display, members) in groups) {
            val local = WheelVanilla.local(display, m)
            val node =
                if (members.size == 1) {
                    val (key, leaf) = members[0]
                    val own = leaf["transformation"]?.let { WheelMath.transformation(it) } ?: WheelMath.identity()
                    leaf + ("transformation" to WheelMath.list(WheelMath.times(local, own)))
                } else {
                    select(index, members.map { (key, leaf) -> mapOf("when" to key, "model" to leafOf(key, leaf)) }, EMPTY, local)
                }
            cases += mapOf("when" to members.map { it.first }, "model" to node)
        }
        for ((key, leaf) in mixed) cases += mapOf("when" to key, "model" to WheelVanilla.moved(leaf, vanilla, m))
        return json(select(index, cases, part("unknown", at.x, at.y, 16, 16, body, 0.0)))
    }

    private fun discNode(at: DiscAt): String {
        val models = ArrayList<Any?>()
        models += part("shadow", 0, 0, WheelLayout.DISC + 1, WheelLayout.DISC + 1, 0, -0.3)
        models += part("stand", 0, 0, WheelLayout.DISC, 105, 0, -0.2)
        val face = if (at.blur != null) "disc_blur_%02d".format(at.blur) else "disc_%02d".format(at.frame)
        models += part(face, 0, 0, WheelLayout.DISC, WheelLayout.DISC, 0, -0.1)
        if (at.mark) models += part("winner", 0, 0, WheelLayout.DISC, WheelLayout.DISC, 0, 0.0)
        return json(mapOf("type" to "minecraft:composite", "models" to models))
    }

    private val countCases: List<Map<String, Any?>> by lazy {
        amounts.map { mapOf("when" to it.toString(), "model" to mapOf("type" to "minecraft:model", "model" to model("count_$it"))) }
    }

    private fun overlayNode(at: OverlayAt): String {
        val body = WheelLayout.BODIES - 1
        val pointer =
            when (at.pointer) {
                WheelTurn.Pointer.REST -> "pointer"
                WheelTurn.Pointer.TICK1 -> "pointer_tick1"
                WheelTurn.Pointer.TICK2 -> "pointer_tick2"
                WheelTurn.Pointer.TICK1_BACK -> "pointer_tick1_back"
                WheelTurn.Pointer.TICK2_BACK -> "pointer_tick2_back"
            }
        val models = ArrayList<Any?>()
        models += part(pointer, WheelLayout.POINTER[0], WheelLayout.POINTER[1], 16, 16, body, 0.1)
        if (countCases.isNotEmpty()) {
            at.counts.forEachIndexed { b, c ->
                if (c != null) models += select(if (c.old) 13 + b else b, countCases, EMPTY, move(body, c.x, c.y))
            }
        }
        if (at.plate) {
            val langs = labels.keys.toList()
            val plates = langs.map { part("plate_off_$it", WheelLayout.PLATE[0], WheelLayout.PLATE[1], WheelLayout.PLATE_WIDTH, WheelLayout.PLATE_HEIGHT, body, 0.0) }
            models += select(LANGUAGE, langs.drop(1).mapIndexed { i, lang -> mapOf("when" to lang, "model" to plates[i + 1]) }, plates[0])
        }
        return json(mapOf("type" to "minecraft:composite", "models" to models))
    }

    /** One body's turn in one variant: a range on the cooldown over its pictures, its rest as the fallback. */
    private fun <K> turn(
        pictures: List<K>,
        rest: K,
        node: (K) -> String,
    ): String {
        val memo = HashMap<K, Raw>()
        val raw = { k: K -> memo.getOrPut(k) { Raw(node(k)) } }
        val entries =
            WheelSpin.runs(pictures, rest).map { (last, k) -> mapOf("threshold" to WheelSpin.threshold(last), "model" to raw(k)) }.reversed()
        return json(mapOf("type" to "minecraft:range_dispatch", "property" to "minecraft:cooldown", "entries" to entries, "fallback" to raw(rest)))
    }

    /** Each body's turn per variant [e] (and the disc's per parity), written once and assembled for any variant count. */
    private val turns: Map<String, Map<Int, String>> by lazy {
        val out = LinkedHashMap<String, MutableMap<Int, String>>()
        for (e in 0 until WheelLayout.PLACES) {
            val spin = WheelSpin(e)
            val ticks = 0 until WheelSpin.COOLDOWN
            for (parity in 0..1) {
                out.getOrPut("disc$parity") { HashMap() }[e] = turn(ticks.map { spin.disc(parity, it) }, spin.discRest(parity), ::discNode)
            }
            for (b in 0 until WheelLayout.PLACES) {
                out.getOrPut("p$b") { HashMap() }[e] = turn(ticks.map { spin.prize(b, it) }, spin.prizeRest(b)) { prizeNode(1 + b, it) }
            }
            out.getOrPut("overlay") { HashMap() }[e] = turn(ticks.map { spin.overlay(it) }, spin.overlayRest(), ::overlayNode)
        }
        out
    }

    private fun variants(
        index: Int,
        offsets: List<Int>,
        of: Map<Int, String>,
    ): Map<String, Any?> = select(index, offsets.drop(1).map { mapOf("when" to it.toString(), "model" to Raw(of.getValue(it))) }, Raw(of.getValue(offsets[0])))

    private fun item(
        body: Int,
        node: Any?,
        rect: IntArray = WheelLayout.ANCHOR,
    ): ByteArray =
        json(mapOf("model" to mapOf("type" to "minecraft:composite", "models" to listOf(node, anchor(body, rect))), "oversized_in_gui" to true))
            .toByteArray()

    /** The three body kinds' item definitions for the turn variants [offsets]. */
    fun items(offsets: List<Int>): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        val disc = select(1, listOf(mapOf("when" to "1", "model" to variants(0, offsets, turns.getValue("disc1")))), variants(0, offsets, turns.getValue("disc0")))
        out["assets/nordtal/items/wheel/disc.json"] = item(0, disc)
        for (b in 0 until WheelLayout.PLACES) out["assets/nordtal/items/wheel/p$b.json"] = item(1 + b, variants(1, offsets, turns.getValue("p$b")))
        out["assets/nordtal/items/wheel/overlay.json"] = item(WheelLayout.BODIES - 1, variants(12, offsets, turns.getValue("overlay")), WheelLayout.OVERLAY_ANCHOR)
        return out
    }

    /** The flat models and the textures the build draws: digits, counts, the placeholder, the off plates. */
    fun statics(): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        val flats =
            (0 until 16).map { "disc_%02d".format(it) } + (0 until WheelTurn.BLUR_TEXTURES).map { "disc_blur_%02d".format(it) } +
                listOf("shadow", "stand", "winner", "pointer", "pointer_tick1", "pointer_tick2", "anchor", "unknown") +
                labels.keys.map { "plate_off_$it" }
        for (name in flats) out["assets/nordtal/models/item/wheel/$name.json"] = flat(if (name == "anchor") "clear" else name, false)
        for (name in listOf("pointer_tick1", "pointer_tick2")) out["assets/nordtal/models/item/wheel/${name}_back.json"] = flat(name, true)
        for (n in amounts) out["assets/nordtal/models/item/wheel/count_$n.json"] = count(n)
        val ascii = vanilla.ascii()
        val textures = "assets/nordtal/textures/item/wheel"
        out["$textures/clear.png"] = WheelArt.png(WheelArt.clear())
        for (d in 0..9) out["$textures/digit_$d.png"] = WheelArt.png(WheelArt.digit(ascii, d))
        out["$textures/unknown.png"] = WheelArt.png(WheelArt.unknown(ascii))
        for ((lang, label) in labels) out["$textures/plate_off_$lang.png"] = WheelArt.png(WheelArt.offPlate(ascii, label))
        return out
    }

    private fun flat(
        texture: String,
        mirrored: Boolean,
    ): ByteArray {
        val t = model(texture)
        val uv = if (mirrored) listOf(16, 0, 0, 16) else listOf(0, 0, 16, 16)
        val element = mapOf("from" to listOf(0, 0, 8), "to" to listOf(16, 16, 8), "faces" to mapOf("south" to mapOf("uv" to uv, "texture" to "#t")))
        return json(mapOf("gui_light" to "front", "textures" to mapOf("t" to t, "particle" to t), "elements" to listOf(element))).toByteArray()
    }

    /** A stack's count as vanilla draws it: digit k of n at x 17 - 6n + 6k, y 9, each 6 x 8 with its shadow. */
    private fun count(n: Int): ByteArray {
        val digits = n.toString()
        val textures = LinkedHashMap<String, Any?>()
        val elements =
            digits.mapIndexed { k, d ->
                textures["d$d"] = model("digit_$d")
                val x = 17 - 6 * digits.length + 6 * k
                mapOf("from" to listOf(x, -1, 8), "to" to listOf(x + 6, 7, 8), "faces" to mapOf("south" to mapOf("uv" to listOf(0, 0, 16, 16), "texture" to "#d$d")))
            }
        textures["particle"] = model("digit_${digits[0]}")
        return json(mapOf("gui_light" to "front", "textures" to textures, "elements" to elements)).toByteArray()
    }

    /** The budget's choice: the most variants, 12 down to [MIN_VARIANTS], whose files fit [MAX_ZIPPED] and [MAX_LOAD_MS]. */
    fun fit(): Budget {
        val statics = statics()
        var last: Budget? = null
        for (count in WheelLayout.PLACES downTo MIN_VARIANTS) {
            val offsets = WheelSpin.offsets(count)
            val files = statics + items(offsets)
            val budget = Budget(offsets, files, files.values.sumOf { it.size.toLong() }, files.values.sumOf { deflated(it) })
            last = budget
            if (budget.zipped <= MAX_ZIPPED && budget.loadMs() <= MAX_LOAD_MS) return budget
        }
        return last!!
    }

    /** The chosen variants, the wheel's files, their raw and deflated size in bytes. */
    class Budget(
        val offsets: List<Int>,
        val files: Map<String, ByteArray>,
        val raw: Long,
        val zipped: Long,
    ) {
        /** The client's load estimate in ms for raw size S in MB: 17.56 S + 0.0772 S², fitted on the prototype's packs. */
        fun loadMs(): Double = (raw / 1e6).let { 17.56 * it + 0.0772 * it * it }

        /** The plugin's timetable: cooldown, swap, pop ticks, variants and each variant's peg clicks, in cooldown ticks. */
        fun properties(): String {
            val lines = ArrayList<String>()
            lines += "# Written by the build from the wheel's turn; the plugin reads it."
            lines += "cooldown=${WheelSpin.COOLDOWN}"
            lines += "swap=${WheelTurn.SWAP_TICKS}"
            lines += "pops=" + (0 until WheelLayout.PLACES).joinToString(",") { (WheelTurn.SWAP_STEP * it).toString() }
            lines += "variants=" + offsets.joinToString(",")
            for (e in offsets) lines += "clicks.$e=" + WheelTurn.clicks(e).joinToString(",") { (WheelTurn.SWAP_TICKS + it).toString() }
            lines += "raw=$raw"
            lines += "zipped=$zipped"
            lines += "load=" + loadMs().toLong()
            return lines.joinToString("\n", postfix = "\n")
        }
    }

    companion object {
        const val LANGUAGE = 25
        const val MIN_VARIANTS = 4
        const val MAX_ZIPPED = 5_000_000L
        const val MAX_LOAD_MS = 1000.0
        private val EMPTY = mapOf("type" to "minecraft:empty")

        /** The unit square of a flat model stretched over [w] x [h] at ([x0], [y0]) from a slot's origin. */
        fun box(
            x0: Int,
            y0: Int,
            w: Int,
            h: Int,
            z: Double,
        ): DoubleArray =
            WheelMath.times(
                WheelMath.translate(0.5 + (x0 - 8) / 16.0, 0.5 + (8 - (y0 + h)) / 16.0, 0.5 + z),
                WheelMath.scale(w / 16.0, h / 16.0, 1.0),
            )

        private fun deflated(bytes: ByteArray): Long {
            val sink = ByteArrayOutputStream()
            DeflaterOutputStream(sink, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { it.write(bytes) }
            return sink.size().toLong()
        }
    }
}
