package eu.nordtal.season.stewardagent.source;

import java.util.Locale;

/**
 * A digest as its source writes it: sha256 from Fill and GitHub, sha512 from Modrinth, sha1 for the pack.
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

    public String shortHex() {
        return hex.length() <= 12 ? hex : hex.substring(0, 12);
    }
}
