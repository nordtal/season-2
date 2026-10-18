package eu.nordtal.season.steward.settings;

import eu.nordtal.season.steward.config.WebSpec;
import io.javalin.http.Context;

/** Serves {@code /api/settings}: the values that the pages read from the web group. */
public final class Settings {

    private final WebSpec config;

    public Settings(final WebSpec config) {
        this.config = config;
    }

    public void get(final Context ctx) {
        ctx.json(new PageSettings(config.avatars().minecraftHeadBaseUrl()));
    }

    public record PageSettings(String minecraftHeadBaseUrl) {}
}
