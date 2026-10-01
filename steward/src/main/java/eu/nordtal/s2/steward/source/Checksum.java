package eu.nordtal.s2.steward.source;

import java.util.Locale;

/**
 * A digest as the API that published it writes it: sha256 from Fill, sha512 from Modrinth, sha1 for the pack.
 *
 * GitHub assets carry no digest, so our own jars and DisplayTags are fetched over TLS unverified.
 */
public record Checksum(String algorithm, String hex) {

    public Checksum {
        algorithm = algorithm.toLowerCase(Locale.ROOT);
        hex = hex.toLowerCase(Locale.ROOT);
    }

    public static Checksum sha1(final String hex) {
        return new Checksum("sha1", hex);
    }

    public static Checksum sha256(final String hex) {
        return new Checksum("sha256", hex);
    }

    public static Checksum sha512(final String hex) {
        return new Checksum("sha512", hex);
    }

    /** The short form a report shows, since a full sha512 is 128 characters. */
    public String shortHex() {
        return hex.length() <= 12 ? hex : hex.substring(0, 12);
    }
}
