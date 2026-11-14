package eu.nordtal.season.packrendering.wheel;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The wooden wheel's timetable in cooldown ticks of the item group {@code nordtal:wheel}.
 * The build writes it with the pack's turn. A variant {@code e} stops the wheel {@code e} places past whole turns.
 *
 * @param cooldown the turn's cooldown, as long as the longest variant's turn
 * @param open the cooldown of the prizes' pop-in when the dialog opens
 * @param pops the tick of each place's pop in the pop-in, place 0 first
 * @param clicks per variant, in the pack's order, its peg clicks
 * @param ends per variant, the tick at which its wheel rests
 * @param rows the dialog's rows, which the pack's art is drawn for
 */
public record WheelTimetable(
        int cooldown,
        int open,
        List<Integer> pops,
        Map<Integer, List<Integer>> clicks,
        Map<Integer, Integer> ends,
        Rows rows) {

    /**
     * The dialog's rows, one per body, which the pack's art is drawn for.
     *
     * @param heights each row's height, the disc's first; a row of 26 has a text of two lines, of 17 one
     * @param rowWidth the width of a row with a text, from its item's left to its text's right
     * @param itemWidth the item's width in a row with a text, but for the plate's row
     * @param plateItem the plate row's item's width, which hovers the prize at {@code itemPlace}
     * @param loneItem the width of the item in a row without a text
     * @param plateX the plate's left from its row's text's left
     * @param itemPlace the place whose prize the plate row's item shows
     * @param hovers per other place, the row whose text hovers its prize and the hover's left in that text
     */
    public record Rows(
            List<Integer> heights,
            int rowWidth,
            int itemWidth,
            int plateItem,
            int loneItem,
            int plateX,
            int itemPlace,
            Map<Integer, Hover> hovers) {

        public Rows {
            heights = List.copyOf(heights);
            hovers = Map.copyOf(hovers);
        }
    }

    /** Where a prize's hover sits: in body {@code body}'s text, {@code x} from its left. */
    public record Hover(int body, int x) {}

    /** Where the build puts the timetable, beside this module's other generated tables. */
    public static final String RESOURCE = "nordtal/wheel/wheel.properties";

    public WheelTimetable {
        pops = List.copyOf(pops);
        final Map<Integer, List<Integer>> copy = new LinkedHashMap<>();
        clicks.forEach((variant, ticks) -> copy.put(variant, List.copyOf(ticks)));
        clicks = Collections.unmodifiableMap(copy);
        ends = Map.copyOf(ends);
    }

    /** The timetable the build wrote for the pack this module ships with. */
    public static WheelTimetable load() {
        try (InputStream in = WheelTimetable.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "the jar has no " + RESOURCE + ", which the resource pack's build writes");
            }
            final Properties properties = new Properties();
            properties.load(in);
            return parse(properties);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads the timetable: cooldown, open, pops, variants, and clicks and end per variant. */
    static WheelTimetable parse(final Properties properties) {
        final Map<Integer, List<Integer>> clicks = new LinkedHashMap<>();
        final Map<Integer, Integer> ends = new LinkedHashMap<>();
        final int cooldown = Integer.parseInt(required(properties, "cooldown"));
        for (final int variant : numbers(required(properties, "variants"))) {
            clicks.put(variant, numbers(properties.getProperty("clicks." + variant, "")));
            ends.put(variant, Integer.parseInt(properties.getProperty("end." + variant, String.valueOf(cooldown))));
        }
        final Map<Integer, Hover> hovers = new LinkedHashMap<>();
        properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("hover."))
                .forEach(key -> {
                    final List<Integer> hover = numbers(properties.getProperty(key));
                    hovers.put(
                            Integer.parseInt(key.substring("hover.".length())), new Hover(hover.get(0), hover.get(1)));
                });
        final Rows rows = new Rows(
                numbers(required(properties, "heights")),
                number(properties, "row-width"),
                number(properties, "item-width"),
                number(properties, "plate-item"),
                number(properties, "lone-item"),
                number(properties, "plate-x"),
                number(properties, "item-place"),
                hovers);
        return new WheelTimetable(
                cooldown, number(properties, "open"), numbers(required(properties, "pops")), clicks, ends, rows);
    }

    private static int number(final Properties properties, final String key) {
        return Integer.parseInt(required(properties, key).trim());
    }

    /** The tick at which variant {@code variant}'s wheel rests, so its prize is paid. */
    public int end(final int variant) {
        return ends.getOrDefault(variant, cooldown);
    }

    /** The turn variants the pack holds. */
    public List<Integer> variants() {
        return List.copyOf(clicks.keySet());
    }

    private static String required(final Properties properties, final String key) {
        final String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalStateException(RESOURCE + " has no " + key);
        }
        return value;
    }

    private static List<Integer> numbers(final String list) {
        final List<Integer> out = new ArrayList<>();
        Arrays.stream(list.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .forEach(part -> out.add(Integer.parseInt(part)));
        return out;
    }
}
