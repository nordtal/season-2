package eu.nordtal.s2.updater.source;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * A digest as the API that published it writes it: an algorithm name and lowercase hex.
 *
 * <h2>Three algorithms, one per source, and none of them is a choice we made</h2>
 * <ul>
 *   <li><b>sha256</b> - the PaperMC Fill API, on every server jar.</li>
 *   <li><b>sha512</b> - Modrinth, on every file. It is also what the datapack pins in
 *       {@code compose.yml} already use, for the same reason: it is what the API hands out, so the
 *       pin can be copied rather than computed, and a pin nobody can re-derive is a pin that
 *       rots.</li>
 *   <li><b>sha1</b> - the resource pack, because that is what the Minecraft client checks.</li>
 * </ul>
 *
 * <h2>GitHub publishes none</h2>
 * A release asset comes with a size and no digest of any kind (checked against the API on
 * 2026-09-01). So our own five jars and the DisplayTags jar are fetched unverified, exactly as
 * {@code entrypoint.sh} fetches them today. That is a real gap and it is written down rather than
 * papered over: the mitigation is that those URLs are {@code github.com} over TLS and that a
 * truncated download produces a jar the JVM refuses to load, loudly, at start.
 */
public record Checksum(@NotNull String algorithm, @NotNull String hex) {

    public Checksum {
        algorithm = algorithm.toLowerCase(Locale.ROOT);
        hex = hex.toLowerCase(Locale.ROOT);
    }

    public static @NotNull Checksum sha1(final @NotNull String hex) {
        return new Checksum("sha1", hex);
    }

    public static @NotNull Checksum sha256(final @NotNull String hex) {
        return new Checksum("sha256", hex);
    }

    public static @NotNull Checksum sha512(final @NotNull String hex) {
        return new Checksum("sha512", hex);
    }

    /** The short form a report shows - a full sha512 is 128 characters and says nothing more. */
    public @NotNull String shortHex() {
        return hex.length() <= 12 ? hex : hex.substring(0, 12);
    }
}
