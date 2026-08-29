package eu.nordtal.displaytags.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.displaytags.DisplayTags;

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

    /**
     * Compares two version numbers such as {@code 1.1.5} and {@code 2.0.0}.
     * <p>
     * Numeric components are compared from left to right, a missing or unreadable component counts
     * as zero, and a pre-release suffix ({@code 2.0.0-beta.1}) ranks below the matching final
     * release.
     *
     * @return a negative number if {@code left} is older, zero if both are the same, a positive
     *         number if {@code left} is newer
     */
    public static int compare(String left, String right) {
        String[] leftParts = release(left).split("\\.");
        String[] rightParts = release(right).split("\\.");

        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            int comparison = Integer.compare(component(leftParts, index), component(rightParts, index));
            if (comparison != 0) return comparison;
        }

        // Same numbers: a pre-release ("2.0.0-rc.1") is older than the release it leads up to.
        return Boolean.compare(isRelease(left), isRelease(right));
    }

    private static String release(String version) {
        if (version == null) return "";

        int suffix = version.indexOf('-');
        return suffix < 0 ? version.trim() : version.substring(0, suffix).trim();
    }

    private static boolean isRelease(String version) {
        return version != null && version.indexOf('-') < 0;
    }

    private static int component(String[] parts, int index) {
        if (index >= parts.length) return 0;

        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException error) {
            return 0;
        }
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
