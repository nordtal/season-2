package eu.nordtal.season.packbuilder.wheel;

import eu.nordtal.season.packbuilder.json.Json;
import eu.nordtal.season.packbuilder.vanilla.VanillaFacts;
import eu.nordtal.season.packbuilder.wheel.WheelSpin.DiscAt;
import eu.nordtal.season.packbuilder.wheel.WheelSpin.OverlayAt;
import eu.nordtal.season.packbuilder.wheel.WheelSpin.PrizeAt;
import eu.nordtal.season.packbuilder.wheel.WheelSpin.Run;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;

/**
 * The wheel's pack files and the plugin's timetable.
 * They come from the prize list, the vanilla facts and the off plate's label in each language. {@link #items} and
 * {@link #statics} map each path in the pack to its bytes.
 */
public final class WheelPack {

    /** The variant string's value while the dialog waits for the press. */
    static final String OPEN = "open";

    static final int PARITY = 1;
    static final int LANGUAGE = 13;
    private static final Map<String, Object> EMPTY = Map.of("type", "minecraft:empty");

    private final WheelVanilla vanilla;
    private final Map<String, String> labels;
    private final List<Integer> amounts;
    private final List<Group> groups = new ArrayList<>();
    private final List<Member> mixed = new ArrayList<>();
    private final Map<String, Json.Raw> leafJson = new HashMap<>();
    private final List<Map<String, Object>> countCases = new ArrayList<>();

    /** A prize on the pack's list: its item id without {@code minecraft:} and the amount the wheel hands out. */
    public record Prize(String key, int amount) {}

    private record Member(String key, Map<String, Object> leaf) {}

    private record Group(double[] display, List<Member> members) {}

    public WheelPack(final VanillaFacts facts, final List<Prize> prizes, final Map<String, String> labels) {
        this.vanilla = new WheelVanilla(facts);
        this.labels = labels;
        this.amounts = prizes.stream()
                .map(Prize::amount)
                .filter(a -> a > 1)
                .distinct()
                .sorted()
                .toList();
        final Map<String, Group> byDisplay = new LinkedHashMap<>();
        final LinkedHashSet<String> keys = new LinkedHashSet<>();
        prizes.forEach(prize -> keys.add(prize.key()));
        for (final String key : keys) {
            final Map<?, ?> definition = vanilla.definition(key)
                    .orElseThrow(() -> new IllegalStateException(
                            "the wheel's prize list names minecraft:" + key + ", which vanilla lacks"));
            final Map<String, Object> leaf = WheelVanilla.leaf(definition);
            final Map<String, double[]> displays = new LinkedHashMap<>();
            for (final String ref : WheelVanilla.refs(leaf)) {
                final double[] display = vanilla.display(ref);
                displays.putIfAbsent(tag(display), display);
            }
            if (displays.isEmpty()) {
                throw new IllegalStateException("minecraft:" + key + " draws no model in a GUI slot");
            }
            if (displays.size() == 1) {
                final Map.Entry<String, double[]> only =
                        displays.entrySet().iterator().next();
                byDisplay
                        .computeIfAbsent(only.getKey(), t -> new Group(only.getValue(), new ArrayList<>()))
                        .members()
                        .add(new Member(key, leaf));
            } else {
                mixed.add(new Member(key, leaf));
            }
        }
        groups.addAll(byDisplay.values());
        for (final int amount : amounts) {
            countCases.add(map(
                    "when",
                    String.valueOf(amount),
                    "model",
                    map("type", "minecraft:model", "model", model("count_" + amount))));
        }
    }

    private static String tag(final double[] m) {
        final List<String> out = new ArrayList<>(m.length);
        for (final double v : m) {
            out.add(String.format(Locale.ROOT, "%.5f", v));
        }
        return String.join(",", out);
    }

    private static String model(final String name) {
        return "nordtal:item/wheel/" + name;
    }

    /** A map in the order of its keys and values {@code kv}. */
    private static Map<String, Object> map(final Object... kv) {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    /** A part of the art: model {@code name}'s 16 x 16 square stretched over w x h at (x, y) on the disc canvas. */
    private static Map<String, Object> part(
            final String name, final int x, final int y, final int w, final int h, final int body, final double z) {
        final int[] c = WheelLayout.canvas(body);
        return map(
                "type", "minecraft:model",
                "model", model(name),
                "transformation", WheelMath.list(box(c[0] + x, c[1] + y, w, h, z)));
    }

    private static Map<String, Object> anchor(final int body, final int[] rect) {
        return part("anchor", rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1], body, -0.4);
    }

    private static double[] move(final int body, final int x, final int y) {
        final int[] c = WheelLayout.canvas(body);
        return WheelMath.translate((c[0] + x) / 16.0, -(c[1] + y) / 16.0, 0.0);
    }

    private static Map<String, Object> select(
            final int index,
            final List<Map<String, Object>> cases,
            final Object fallback,
            final double @Nullable [] transformation) {
        final Map<String, Object> out = map(
                "type", "minecraft:select",
                "property", "minecraft:custom_model_data",
                "index", index,
                "cases", cases,
                "fallback", fallback);
        if (transformation != null) {
            out.put("transformation", WheelMath.list(transformation));
        }
        return out;
    }

    /** The unit square of a flat model stretched over w x h at (x0, y0) from a slot's origin. */
    static double[] box(final int x0, final int y0, final int w, final int h, final double z) {
        return WheelMath.times(
                WheelMath.translate(0.5 + (x0 - 8) / 16.0, 0.5 + (8 - (y0 + h)) / 16.0, 0.5 + z),
                WheelMath.scale(w / 16.0, h / 16.0, 1.0));
    }

    private Json.Raw leafOf(final String key, final Map<String, Object> leaf) {
        return leafJson.computeIfAbsent(key, k -> new Json.Raw(Json.write(leaf)));
    }

    /** A prize body's picture: one select on its key, each display group moved by its own L. */
    private String prizeNode(final int body, final Optional<PrizeAt> picture) {
        if (picture.isEmpty()) {
            return Json.write(EMPTY);
        }
        final PrizeAt at = picture.get();
        final int index = 0;
        final double[] m = move(body, at.x(), at.y());
        final List<Map<String, Object>> cases = new ArrayList<>();
        for (final Group group : groups) {
            final double[] local = WheelVanilla.local(group.display(), m);
            final Object node;
            if (group.members().size() == 1) {
                final Map<String, Object> leaf =
                        new LinkedHashMap<>(group.members().getFirst().leaf());
                final Object transformation = leaf.get("transformation");
                final double[] own =
                        transformation == null ? WheelMath.identity() : WheelMath.transformation(transformation);
                leaf.put("transformation", WheelMath.list(WheelMath.times(local, own)));
                node = leaf;
            } else {
                final List<Map<String, Object>> members = new ArrayList<>();
                for (final Member member : group.members()) {
                    members.add(map("when", member.key(), "model", leafOf(member.key(), member.leaf())));
                }
                node = select(index, members, EMPTY, local);
            }
            cases.add(map("when", group.members().stream().map(Member::key).toList(), "model", node));
        }
        for (final Member member : mixed) {
            cases.add(map("when", member.key(), "model", vanilla.moved(member.leaf(), m, WheelMath.identity())));
        }
        return Json.write(select(index, cases, part("unknown", at.x(), at.y(), 16, 16, body, 0.0), null));
    }

    private static String discNode(final DiscAt at) {
        final List<Object> models = new ArrayList<>();
        models.add(part("shadow", 0, 0, WheelLayout.DISC + 1, WheelLayout.DISC + 1, 0, -0.3));
        models.add(part("stand", 0, 0, WheelLayout.DISC, 105, 0, -0.2));
        models.add(part(
                String.format(Locale.ROOT, "disc_%02d", at.frame()),
                0,
                0,
                WheelLayout.DISC,
                WheelLayout.DISC,
                0,
                -0.1));
        if (at.mark()) {
            models.add(part("winner", 0, 0, WheelLayout.DISC, WheelLayout.DISC, 0, 0.0));
        }
        return Json.write(map("type", "minecraft:composite", "models", models));
    }

    private String overlayNode(final OverlayAt at) {
        final int body = WheelLayout.BODIES - 1;
        final List<Object> models = new ArrayList<>();
        models.add(part(at.pointer().texture(), WheelLayout.POINTER[0], WheelLayout.POINTER[1], 16, 16, body, 0.1));
        if (!countCases.isEmpty()) {
            for (int b = 0; b < at.counts().size(); b++) {
                final Optional<PrizeAt> count = at.counts().get(b);
                if (count.isPresent()) {
                    models.add(select(
                            b,
                            countCases,
                            EMPTY,
                            move(body, count.get().x(), count.get().y())));
                }
            }
        }
        if (at.plate()) {
            final List<Map<String, Object>> plates = new ArrayList<>();
            for (final String lang : labels.keySet()) {
                plates.add(part(
                        "plate_off_" + lang,
                        WheelLayout.PLATE[0],
                        WheelLayout.PLATE[1],
                        WheelLayout.PLATE_WIDTH,
                        WheelLayout.PLATE_HEIGHT,
                        body,
                        0.0));
            }
            final List<String> langs = List.copyOf(labels.keySet());
            final List<Map<String, Object>> cases = new ArrayList<>();
            for (int i = 1; i < langs.size(); i++) {
                cases.add(map("when", langs.get(i), "model", plates.get(i)));
            }
            models.add(select(LANGUAGE, cases, plates.getFirst(), null));
        }
        return Json.write(map("type", "minecraft:composite", "models", models));
    }

    /** A range on the cooldown over {@code runs}, with {@code fallback} once the last has passed. */
    private static <K> Map<String, Object> range(
            final List<Run<K>> runs, final int cooldown, final Function<K, Json.Raw> raw, final Object fallback) {
        final List<Map<String, Object>> entries = new ArrayList<>();
        for (final Run<K> run : runs) {
            entries.add(map("threshold", WheelSpin.threshold(run.last(), cooldown), "model", raw.apply(run.picture())));
        }
        Collections.reverse(entries);
        return map(
                "type",
                "minecraft:range_dispatch",
                "property",
                "minecraft:cooldown",
                "entries",
                entries,
                "fallback",
                fallback);
    }

    /** How one body's picture is drawn: before the press, while it turns and at rest. */
    private record Pictures<K>(
            IntFunction<K> popIn,
            K waiting,
            BiFunction<WheelSpin, Integer, K> spin,
            Function<WheelSpin, K> rest,
            Function<K, String> node) {}

    /**
     * One body's pictures for the variants {@code offsets}, chosen by the variant string at {@code index}.
     * "open" plays the pop-in and waits; a variant plays the shared start, then its own ticks, and rests.
     */
    private static <K> Map<String, Object> body(final int index, final List<Integer> offsets, final Pictures<K> p) {
        final Map<K, Json.Raw> memo = new HashMap<>();
        final Function<K, Json.Raw> raw =
                k -> memo.computeIfAbsent(k, key -> new Json.Raw(p.node().apply(key)));
        final List<K> popIn = new ArrayList<>();
        for (int t = 0; t < WheelTurn.OPEN_TICKS; t++) {
            popIn.add(p.popIn().apply(t));
        }
        final Map<String, Object> open =
                range(WheelSpin.runs(popIn, p.waiting(), 0), WheelTurn.OPEN_TICKS, raw, raw.apply(p.waiting()));
        final int cooldown = WheelTurn.COOLDOWN;
        final WheelSpin first = new WheelSpin(offsets.getFirst());
        final List<K> start = new ArrayList<>();
        for (int t = 0; t < WheelTurn.SHARED; t++) {
            start.add(p.spin().apply(first, t));
        }
        final List<Map<String, Object>> own = new ArrayList<>();
        for (final int e : offsets) {
            final WheelSpin s = new WheelSpin(e);
            final List<K> pictures = new ArrayList<>();
            for (int t = WheelTurn.SHARED; t < cooldown; t++) {
                pictures.add(p.spin().apply(s, t));
            }
            final K rest = p.rest().apply(s);
            own.add(range(WheelSpin.runs(pictures, rest, WheelTurn.SHARED), cooldown, raw, raw.apply(rest)));
        }
        final List<Map<String, Object>> cases = new ArrayList<>();
        for (int i = 1; i < offsets.size(); i++) {
            cases.add(map("when", String.valueOf(offsets.get(i)), "model", own.get(i)));
        }
        final Map<String, Object> variants = select(index, cases, own.getFirst(), null);
        return select(
                index,
                List.of(map("when", OPEN, "model", open)),
                range(WheelSpin.runs(start, 0), cooldown, raw, variants),
                null);
    }

    private static Map<String, Object> disc(final int parity, final List<Integer> offsets) {
        return body(
                0,
                offsets,
                new Pictures<>(
                        t -> WheelSpin.waitingDisc(parity),
                        WheelSpin.waitingDisc(parity),
                        (s, t) -> s.disc(parity, t),
                        s -> s.discRest(parity),
                        WheelPack::discNode));
    }

    private Map<String, Object> prize(final int b, final List<Integer> offsets) {
        return body(
                1,
                offsets,
                new Pictures<Optional<PrizeAt>>(
                        t -> WheelSpin.popIn(b, t),
                        Optional.of(WheelSpin.waiting(b)),
                        (s, t) -> Optional.of(s.prize(b, t)),
                        s -> Optional.of(s.prizeRest(b)),
                        at -> prizeNode(1 + b, at)));
    }

    private Map<String, Object> overlay(final List<Integer> offsets) {
        return body(
                WheelLayout.PLACES,
                offsets,
                new Pictures<>(
                        WheelSpin::popInOverlay,
                        WheelSpin.waitingOverlay(),
                        (s, t) -> s.overlay(t),
                        WheelSpin::overlayRest,
                        this::overlayNode));
    }

    private static byte[] item(final int body, final Object node, final int[] rect) {
        return Json.bytes(map(
                "model",
                map("type", "minecraft:composite", "models", List.of(node, anchor(body, rect))),
                "oversized_in_gui",
                true));
    }

    /** The three body kinds' item definitions for the turn variants {@code offsets}. */
    public Map<String, byte[]> items(final List<Integer> offsets) {
        final Map<String, byte[]> out = new LinkedHashMap<>();
        final Map<String, Object> disc =
                select(PARITY, List.of(map("when", "1", "model", disc(1, offsets))), disc(0, offsets), null);
        out.put("assets/nordtal/items/wheel/disc.json", item(0, disc, WheelLayout.ANCHOR));
        for (int b = 0; b < WheelLayout.PLACES; b++) {
            out.put("assets/nordtal/items/wheel/p" + b + ".json", item(1 + b, prize(b, offsets), WheelLayout.ANCHOR));
        }
        out.put(
                "assets/nordtal/items/wheel/overlay.json",
                item(WheelLayout.BODIES - 1, overlay(offsets), WheelLayout.OVERLAY_ANCHOR));
        return out;
    }

    /** The flat models and the textures the pack draws: digits, counts, the placeholder, the off plates. */
    public Map<String, byte[]> statics() {
        final Map<String, byte[]> out = new LinkedHashMap<>();
        final List<String> flats = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            flats.add(String.format(Locale.ROOT, "disc_%02d", i));
        }
        flats.addAll(
                List.of("shadow", "stand", "winner", "pointer", "pointer_tick1", "pointer_tick2", "anchor", "unknown"));
        labels.keySet().forEach(lang -> flats.add("plate_off_" + lang));
        for (final String name : flats) {
            out.put("assets/nordtal/models/item/wheel/" + name + ".json", flat(name.equals("anchor") ? "clear" : name));
        }
        for (final int n : amounts) {
            out.put("assets/nordtal/models/item/wheel/count_" + n + ".json", count(n));
        }
        final BufferedImage ascii = vanilla.ascii();
        final String textures = "assets/nordtal/textures/item/wheel/";
        out.put(textures + "clear.png", WheelArt.png(WheelArt.clear()));
        for (int d = 0; d <= 9; d++) {
            out.put(textures + "digit_" + d + ".png", WheelArt.png(WheelArt.digit(ascii, d)));
        }
        out.put(textures + "unknown.png", WheelArt.png(WheelArt.unknown(ascii)));
        labels.forEach((lang, label) ->
                out.put(textures + "plate_off_" + lang + ".png", WheelArt.png(WheelArt.offPlate(ascii, label))));
        return out;
    }

    private static byte[] flat(final String texture) {
        final String t = model(texture);
        final Map<String, Object> element = map(
                "from", List.of(0, 0, 8),
                "to", List.of(16, 16, 8),
                "faces", map("south", map("uv", List.of(0, 0, 16, 16), "texture", "#t")));
        return Json.bytes(
                map("gui_light", "front", "textures", map("t", t, "particle", t), "elements", List.of(element)));
    }

    /** A stack's count as vanilla draws it: digit k of n at x 17-6n+6k, y 9, each 6 x 8 with its shadow. */
    private static byte[] count(final int n) {
        final String digits = String.valueOf(n);
        final Map<String, Object> textures = new LinkedHashMap<>();
        final List<Object> elements = new ArrayList<>();
        for (int k = 0; k < digits.length(); k++) {
            final char d = digits.charAt(k);
            textures.put("d" + d, model("digit_" + d));
            final int x = 17 - 6 * digits.length() + 6 * k;
            elements.add(map(
                    "from", List.of(x, -1, 8),
                    "to", List.of(x + 6, 7, 8),
                    "faces", map("south", map("uv", List.of(0, 0, 16, 16), "texture", "#d" + d))));
        }
        textures.put("particle", model("digit_" + digits.charAt(0)));
        return Json.bytes(map("gui_light", "front", "textures", textures, "elements", elements));
    }

    /**
     * The plugin's timetable, in cooldown ticks.
     * It holds the turn's cooldown, the pop-in's ticks and pops, the variants, and each variant's peg clicks and the
     * tick it rests at.
     */
    public static String timetable(final List<Integer> offsets) {
        final StringBuilder out = new StringBuilder();
        out.append("# Written by the build from the wheel's turn; the plugin reads it.\n");
        out.append("cooldown=").append(WheelTurn.COOLDOWN).append('\n');
        out.append("open=").append(WheelTurn.OPEN_TICKS).append('\n');
        final List<String> pops = new ArrayList<>();
        for (int b = 0; b < WheelLayout.PLACES; b++) {
            pops.add(String.valueOf(WheelTurn.POP_STEP * b));
        }
        out.append("pops=").append(String.join(",", pops)).append('\n');
        out.append("variants=").append(joined(offsets)).append('\n');
        for (final int e : offsets) {
            out.append("clicks.")
                    .append(e)
                    .append('=')
                    .append(joined(WheelTurn.clicks(e)))
                    .append('\n');
        }
        for (final int e : offsets) {
            out.append("end.").append(e).append('=').append(WheelTurn.length(e)).append('\n');
        }
        return out.toString();
    }

    private static String joined(final List<Integer> numbers) {
        return String.join(",", numbers.stream().map(String::valueOf).toList());
    }

    /** The variants of {@code count}, spread over the twelve places as evenly as whole places allow. */
    public static List<Integer> offsets(final int count) {
        return WheelSpin.offsets(count);
    }

    /** The prize list, a JSON list of {@code item} and {@code amount}. */
    public static List<Prize> prizes(final Object json) {
        final List<Prize> out = new ArrayList<>();
        for (final Object each : (List<?>) json) {
            final Map<?, ?> prize = (Map<?, ?>) each;
            final String item = (String) prize.get("item");
            out.add(new Prize(WheelVanilla.shortId(item), ((Number) prize.get("amount")).intValue()));
        }
        return out;
    }

    /** The {@code spin-button} label per language of the wheel's text document, German first. */
    public static Map<String, String> labels(final Object document) {
        final List<Map<?, ?>> parts = ((List<?>) ((Map<?, ?>) document).get("parts"))
                .stream()
                        .<Map<?, ?>>map(p -> (Map<?, ?>) p)
                        .filter(p -> "spin-button".equals(p.get("slot")))
                        .toList();
        if (parts.size() != 1) {
            throw new IllegalArgumentException("the wheel's document has " + parts.size() + " spin buttons");
        }
        final List<?> lines = (List<?>) parts.getFirst().get("lines");
        if (lines.size() != 1) {
            throw new IllegalArgumentException("the spin button has " + lines.size() + " lines");
        }
        final Map<?, ?> text = (Map<?, ?>) ((Map<?, ?>) lines.getFirst()).get("text");
        final List<Map.Entry<String, String>> entries = new ArrayList<>();
        text.forEach((lang, label) -> entries.add(Map.entry((String) lang, (String) label)));
        entries.sort((a, b) -> sortKey(a.getKey()).compareTo(sortKey(b.getKey())));
        final Map<String, String> out = new LinkedHashMap<>();
        entries.forEach(entry -> out.put(entry.getKey(), entry.getValue()));
        return out;
    }

    private static String sortKey(final String lang) {
        return lang.equals("de") ? "" : lang;
    }
}
