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
 * The build writes it with the pack's turn. Each variant {@code e} has a direct and a rebound ending.
 *
 * @param cooldown the turn's cooldown, as long as the longest variant's turn
 * @param open the cooldown of the prizes' pop-in when the dialog opens
 * @param pops the tick of each place's pop in the pop-in, place 0 first
 * @param clicks per variant, in the pack's order, its direct-ending peg clicks
 * @param ends per variant, the tick at which its direct-ending wheel rests
 * @param reboundClicks per variant, in the pack's order, its rebound-ending peg clicks
 * @param reboundEnds per variant, the tick at which its rebound-ending wheel rests
 * @param rows the dialog's rows, which the pack's art is drawn for
 */
public record WheelTimetable(
        int cooldown,
        int open,
        List<Integer> pops,
        Map<Integer, List<Integer>> clicks,
        Map<Integer, Integer> ends,
        Map<Integer, List<Integer>> reboundClicks,
        Map<Integer, Integer> reboundEnds,
        Rows rows) {

    /**
     * The dialog's rows, one per body, which the pack's art is drawn for.
     *
     * @param heights each row's height, the lone base art body first, then the description body
     * @param lines the text lines in the description body
     * @param rowWidth the width of the description row, from its item's left to its text's right
     * @param itemWidth the item's width in the description row
     * @param loneItem the width of the item in the lone base art body
     * @param plateX the plate's left from the description text's left
     * @param plateY the plate's top from the description text's top
     * @param hovers per place, the prize's top-left in the description text
     */
    public record Rows(
            List<Integer> heights,
            int lines,
            int rowWidth,
            int itemWidth,
            int loneItem,
            int plateX,
            int plateY,
            Map<Integer, Hover> hovers) {

        public Rows {
            heights = List.copyOf(heights);
            hovers = Map.copyOf(hovers);
        }
    }

    /** Where a prize's hover sits in the description text: ({@code x}, {@code y}) from its top-left. */
    public record Hover(int x, int y) {}

    /** Where the build puts the timetable, beside this module's other generated tables. */
    public static final String RESOURCE = "nordtal/wheel/wheel.properties";

    public WheelTimetable {
        pops = List.copyOf(pops);
        clicks = copyClickMap(clicks);
        ends = Map.copyOf(ends);
        reboundClicks = copyClickMap(reboundClicks);
        reboundEnds = Map.copyOf(reboundEnds);
    }

    private static Map<Integer, List<Integer>> copyClickMap(final Map<Integer, List<Integer>> source) {
        final Map<Integer, List<Integer>> copy = new LinkedHashMap<>();
        source.forEach((variant, ticks) -> copy.put(variant, List.copyOf(ticks)));
        return Collections.unmodifiableMap(copy);
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

    /** Reads the timetable: cooldown, open, pops, variants, and direct and rebound clicks and ends per variant. */
    static WheelTimetable parse(final Properties properties) {
        final Map<Integer, List<Integer>> clicks = new LinkedHashMap<>();
        final Map<Integer, Integer> ends = new LinkedHashMap<>();
        final Map<Integer, List<Integer>> reboundClicks = new LinkedHashMap<>();
        final Map<Integer, Integer> reboundEnds = new LinkedHashMap<>();
        final int cooldown = Integer.parseInt(required(properties, "cooldown"));
        for (final int variant : numbers(required(properties, "variants"))) {
            clicks.put(variant, numbers(properties.getProperty("clicks." + variant, "")));
            ends.put(variant, Integer.parseInt(properties.getProperty("end." + variant, String.valueOf(cooldown))));
            reboundClicks.put(
                    variant,
                    numbers(properties.getProperty(
                            "clicks." + variant + ".rebound", properties.getProperty("clicks." + variant, ""))));
            reboundEnds.put(
                    variant,
                    Integer.parseInt(properties.getProperty(
                            "end." + variant + ".rebound",
                            properties.getProperty("end." + variant, String.valueOf(cooldown)))));
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
                number(properties, "lines"),
                number(properties, "row-width"),
                number(properties, "item-width"),
                number(properties, "lone-item"),
                number(properties, "plate-x"),
                number(properties, "plate-y"),
                hovers);
        return new WheelTimetable(
                cooldown,
                number(properties, "open"),
                numbers(required(properties, "pops")),
                clicks,
                ends,
                reboundClicks,
                reboundEnds,
                rows);
    }

    private static int number(final Properties properties, final String key) {
        return Integer.parseInt(required(properties, key).trim());
    }

    /** The peg-pass clicks for {@code variant} with the direct or rebound ending. */
    public List<Integer> clicks(final int variant, final boolean rebound) {
        return (rebound ? reboundClicks : clicks).getOrDefault(variant, List.of());
    }

    /** The tick at which {@code variant}'s wheel rests for the direct or rebound ending, so its prize is paid. */
    public int end(final int variant, final boolean rebound) {
        return (rebound ? reboundEnds : ends).getOrDefault(variant, cooldown);
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
