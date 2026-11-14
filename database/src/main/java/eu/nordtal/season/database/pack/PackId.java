package eu.nordtal.season.database.pack;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** The id a client keeps a resource pack under, wherever it is sent from, so a client holds it once, not twice. */
public final class PackId {

    private PackId() {}

    /** Returns the id of the pack with the SHA-1 {@code sha1}, in either case. */
    public static UUID of(final String sha1) {
        return UUID.nameUUIDFromBytes(sha1.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
