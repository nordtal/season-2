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
 */
public record WheelTimetable(
        int cooldown, int open, List<Integer> pops, Map<Integer, List<Integer>> clicks, Map<Integer, Integer> ends) {

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
        return new WheelTimetable(
                cooldown,
                Integer.parseInt(required(properties, "open")),
                numbers(required(properties, "pops")),
                clicks,
                ends);
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
