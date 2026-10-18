package eu.nordtal.season.messages.context;

import eu.nordtal.season.messages.Palette;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/**
 * What every message of one process can name, and how the process shows times and tones.
 * The globals are {@code server}, {@code season} and {@code network}.
 *
 * @param globals the context of each global role, empty if no process is named
 * @param zone    the zone for a reader without one
 */
public record MessageEnvironment(Map<String, MessageContext> globals, ZoneId zone, Palette palette) {

    /** Names no process: globals show their replacement word, times use UTC, tones use defaults. */
    public static final MessageEnvironment NONE = new MessageEnvironment(Map.of(), ZoneId.of("UTC"), Palette.DEFAULTS);

    public MessageEnvironment {
        globals = Map.copyOf(globals);
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(palette, "palette");
    }

    public static MessageEnvironment of(
            final String service, final SeasonContext season, final ZoneId zone, final Palette palette) {
        return new MessageEnvironment(
                Map.of("server", new ServiceContext(service), "season", season, "network", NetworkContext.NORDTAL),
                zone,
                palette);
    }
}
