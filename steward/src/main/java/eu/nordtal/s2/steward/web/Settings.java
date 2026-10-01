package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.steward.config.WebSpec;
import io.javalin.http.Context;
import java.util.Map;

/** {@code /api/settings}: the thresholds and the base URL the start page judges by. */
final class Settings {

    private final WebSpec config;

    Settings(final WebSpec config) {
        this.config = config;
    }

    void get(final Context ctx) {
        ctx.json(Map.of(
                "disk", config.alerts().diskPercent(),
                "memory", config.alerts().memoryPercent(),
                "backupAgeHours", config.alerts().backupAgeHours(),
                // The base a Minecraft head URL is composed from.
                "minecraftHeadBaseUrl", config.avatars().minecraftHeadBaseUrl()));
    }
}
