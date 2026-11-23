package eu.nordtal.season.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import eu.nordtal.season.database.setting.SettingStore;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The resource pack the proxy offers: {@code url} and {@code sha1} of its {@code pack} group, or the map pack's.
 * An update run sets the first, the nightly map build {@code map-url} and {@code map-sha1}. Only stored rows count,
 * so a proxy without a stored pack reads as absent whatever the spec defaults to.
 */
public record PackState(@Nullable String url, @Nullable String sha1) {

    public static final String SERVICE = "proxy";

    public static final String GROUP = "pack";

    /** Returns whether a pack is set. The hash decides, because the client is held to it. */
    public boolean present() {
        return sha1 != null;
    }

    public static PackState read(final SettingStore store) {
        return of(store.overrides(List.of(SERVICE)));
    }

    /** Returns the map pack the proxy offers beside the language pack. */
    public static PackState readMap(final SettingStore store) {
        return ofMap(store.overrides(List.of(SERVICE)));
    }

    /** Returns the pack among {@code stored}, ignoring every other group and service. */
    public static PackState of(final List<SettingStore.Value> stored) {
        return of(stored, "url", "sha1");
    }

    /** Returns the map pack among {@code stored}, ignoring every other group and service. */
    public static PackState ofMap(final List<SettingStore.Value> stored) {
        return of(stored, "map-url", "map-sha1");
    }

    private static PackState of(final List<SettingStore.Value> stored, final String urlKey, final String sha1Key) {
        String url = null;
        String sha1 = null;
        for (final SettingStore.Value value : stored) {
            if (!SERVICE.equals(value.service()) || !GROUP.equals(value.group())) {
                continue;
            }
            // The pack's other settings are an admin's, not the release's.
            if (urlKey.equals(value.path())) {
                url = text(value.value());
            } else if (sha1Key.equals(value.path())) {
                sha1 = text(value.value());
            }
        }
        return new PackState(url, sha1);
    }

    /** Returns a stored JSON value as text, so a hash of only digits stays text. */
    private static @Nullable String text(final String json) {
        final JsonElement value = JsonParser.parseString(json);
        if (!value.isJsonPrimitive()) {
            return null;
        }
        final String text = value.getAsString().strip();
        return text.isEmpty() ? null : text;
    }
}
