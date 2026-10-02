package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.steward.config.WebSpec;
import io.javalin.http.Context;
import java.util.Map;

/** {@code /api/settings}: what the pages need from the web group, which is the base of a Minecraft head. */
final class Settings {

    private final WebSpec config;

    Settings(final WebSpec config) {
        this.config = config;
    }

    void get(final Context ctx) {
        ctx.json(Map.of("minecraftHeadBaseUrl", config.avatars().minecraftHeadBaseUrl()));
    }
}
