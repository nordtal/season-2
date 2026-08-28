package me.skyyiscool.displaytags.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.skyyiscool.displaytags.DisplayTags;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.function.Consumer;

/**
 * Asks the Modrinth API for the newest release of the plugin.
 */
public class UpdateChecker {
    private static final String API_URL = "https://api.modrinth.com/v2";

    private final DisplayTags plugin;
    private final String projectId;

    private String latest;

    public UpdateChecker(DisplayTags plugin, String projectId) {
        this.plugin = plugin;
        this.projectId = projectId;
    }

    /**
     * Resolves the latest released version number and hands it to {@code consumer}.
     * <p>
     * The HTTP request runs off the main thread, but the consumer is always invoked on the main
     * thread so it may safely talk to the server API or message players. The consumer is not
     * invoked at all if the lookup fails.
     */
    public void getLatestVersion(Consumer<String> consumer) {
        if (this.latest != null) {
            consumer.accept(this.latest);
            return;
        }

        this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () -> {
            String version;
            try {
                version = fetchLatestVersion();
            } catch (Exception error) {
                this.plugin.getLogger().severe("Unable to fetch latest version: " + error.getMessage());
                return;
            }

            if (version == null) return;
            this.latest = version;

            // Hop back onto the main thread before handing control to the caller.
            if (!this.plugin.isEnabled()) return;
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> consumer.accept(version));
        });
    }

    private String fetchLatestVersion() throws Exception {
        URL url = URI.create(API_URL + "/project/" + this.projectId + "/version").toURL();

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
            JsonArray versions = JsonParser.parseReader(reader).getAsJsonArray();

            // Modrinth returns versions newest first, so the first release is the latest one.
            for (var element : versions) {
                JsonObject object = element.getAsJsonObject();
                if (!"release".equals(object.get("version_type").getAsString())) continue;

                return object.get("version_number").getAsString();
            }
        } finally {
            connection.disconnect();
        }

        return null;
    }
}
