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
 * @param cooldown the spin's cooldown: the swap, then the turn
 * @param swap the swap's ticks
 * @param pops the tick of each place's pop, place 0 first
 * @param clicks per variant, in the pack's order, its peg clicks
 */
public record WheelTimetable(int cooldown, int swap, List<Integer> pops, Map<Integer, List<Integer>> clicks) {

    /** Where the build puts the timetable, beside this module's other generated tables. */
    public static final String RESOURCE = "nordtal/wheel/wheel.properties";

    public WheelTimetable {
        pops = List.copyOf(pops);
        final Map<Integer, List<Integer>> copy = new LinkedHashMap<>();
        clicks.forEach((variant, ticks) -> copy.put(variant, List.copyOf(ticks)));
        clicks = Collections.unmodifiableMap(copy);
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

    /** Reads {@code cooldown}, {@code swap}, {@code pops}, {@code variants} and {@code clicks.<variant>}. */
    static WheelTimetable parse(final Properties properties) {
        final Map<Integer, List<Integer>> clicks = new LinkedHashMap<>();
        for (final int variant : numbers(required(properties, "variants"))) {
            clicks.put(variant, numbers(properties.getProperty("clicks." + variant, "")));
        }
        return new WheelTimetable(
                Integer.parseInt(required(properties, "cooldown")),
                Integer.parseInt(required(properties, "swap")),
                numbers(required(properties, "pops")),
                clicks);
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
