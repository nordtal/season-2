package eu.nordtal.season.limbo.config;

import eu.nordtal.season.settings.Checks;

/** Checks the {@code config} group. */
public final class LimboCheck {

    private LimboCheck() {}

    /** Throws an {@link IllegalArgumentException} that names the first invalid value. */
    public static void check(final LimboSpec config) {
        Checks.requireText("world-name", config.worldName());
        Checks.requirePositive("title-refresh-seconds", config.titleRefreshSeconds());
        if (config.spawnY() < -60 || config.spawnY() > 300) {
            throw new IllegalArgumentException(
                    "spawn-y must be somewhere inside a world's build limits, was " + config.spawnY());
        }
    }
}
