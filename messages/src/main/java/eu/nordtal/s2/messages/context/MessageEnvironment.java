package eu.nordtal.s2.messages.context;

import eu.nordtal.s2.messages.Palette;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/**
 * What every message of one process can name, and how it shows times and tones; handed in at startup.
 * The globals are {@code server}, {@code season} and {@code network}; the zone serves a reader without one.
 *
 * @param globals role to context; empty where no process is named, which prints those values' replacement words
 * @param zone    the network's time zone
 * @param palette the process's tone colours, read on every render
 */
public record MessageEnvironment(Map<String, MessageContext> globals, ZoneId zone, Palette palette) {

    /** No process: every global prints its replacement word, times read in UTC, and tones are their defaults. */
    public static final MessageEnvironment NONE = new MessageEnvironment(Map.of(), ZoneId.of("UTC"), Palette.DEFAULTS);

    public MessageEnvironment {
        globals = Map.copyOf(globals);
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(palette, "palette");
    }

    /** Returns the environment of the service called {@code service}, in the season and zone the settings name. */
    public static MessageEnvironment of(
            final String service, final SeasonContext season, final ZoneId zone, final Palette palette) {
        return new MessageEnvironment(
                Map.of("server", new ServiceContext(service), "season", season, "network", NetworkContext.NORDTAL),
                zone,
                palette);
    }
}
