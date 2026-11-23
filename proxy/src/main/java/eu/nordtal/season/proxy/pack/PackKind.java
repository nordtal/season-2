package eu.nordtal.season.proxy.pack;

import eu.nordtal.season.proxy.config.PackSpec;

/** The two packs the proxy offers: the pack of the player's language, and the map pack that every language shares. */
public enum PackKind {
    LANGUAGE,
    MAP;

    /** Returns the download URL {@code spec} names for this pack. */
    public String url(final PackSpec spec) {
        return this == LANGUAGE ? spec.url() : spec.mapUrl();
    }

    /** Returns the SHA-1 {@code spec} names for this pack, blank for a map pack that was never built. */
    public String sha1(final PackSpec spec) {
        return this == LANGUAGE ? spec.sha1() : spec.mapSha1();
    }

    /** Returns whether {@code spec} names this pack, so the proxy offers it. */
    public boolean named(final PackSpec spec) {
        return !sha1(spec).isBlank();
    }
}
