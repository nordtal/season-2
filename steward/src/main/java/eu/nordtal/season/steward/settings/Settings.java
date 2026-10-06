package eu.nordtal.season.steward.settings;

import eu.nordtal.season.steward.config.WebSpec;
import io.javalin.http.Context;

/** {@code /api/settings}: what the pages need from the web group, which is the base of a Minecraft head. */
public final class Settings {

    private final WebSpec config;

    public Settings(final WebSpec config) {
        this.config = config;
    }

    public void get(final Context ctx) {
        ctx.json(new PageSettings(config.avatars().minecraftHeadBaseUrl()));
    }

    /** What the pages read of the web group. */
    public record PageSettings(String minecraftHeadBaseUrl) {}
}
