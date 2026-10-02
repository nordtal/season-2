package eu.nordtal.s2.limbo.config;

import eu.nordtal.s2.settings.Checks;

/** What a valid {@code config} group of limbo is beyond its types. */
public final class LimboCheck {

    private LimboCheck() {}

    /**
     * Refuses a waiting room that would show nothing.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    public static void check(final LimboSpec config) {
        Checks.requireText("world-name", config.worldName());
        // Zero would be a title that never refreshes and expires into a black screen.
        Checks.requirePositive("title-refresh-seconds", config.titleRefreshSeconds());
        if (config.spawnY() < -60 || config.spawnY() > 300) {
            // The world is empty, but a value outside the build limits still refuses to keep a player there.
            throw new IllegalArgumentException(
                    "spawn-y must be somewhere inside a world's build limits, was " + config.spawnY());
        }
    }
}
