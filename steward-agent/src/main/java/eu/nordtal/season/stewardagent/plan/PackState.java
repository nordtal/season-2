package eu.nordtal.season.stewardagent.plan;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import eu.nordtal.season.database.setting.SettingStore;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The resource pack the proxy offers: {@code url} and {@code sha1} of its {@code pack} group, which an update run sets.
 *
 * Only the stored rows count, so a proxy that never had a pack reads as absent whatever its spec defaults to.
 */
public record PackState(@Nullable String url, @Nullable String sha1) {

    /** The service whose settings hold the pack. */
    public static final String SERVICE = "proxy";

    /** The group of that service which holds it. */
    public static final String GROUP = "pack";

    /** Whether a pack was ever set: a hash is what the client is held to. */
    public boolean present() {
        return sha1 != null;
    }

    /** Reads the two values as stored. */
    public static PackState read(final SettingStore store) {
        return of(store.overrides(List.of(SERVICE)));
    }

    /** Returns the pack among {@code stored}, ignoring every other group and service. */
    public static PackState of(final List<SettingStore.Value> stored) {
        String url = null;
        String sha1 = null;
        for (final SettingStore.Value value : stored) {
            if (!SERVICE.equals(value.service()) || !GROUP.equals(value.group())) {
                continue;
            }
            switch (value.path()) {
                case "url" -> url = text(value.value());
                case "sha1" -> sha1 = text(value.value());
                default -> {
                    // The pack's other settings are an admin's, not the release's.
                }
            }
        }
        return new PackState(url, sha1);
    }

    /** A stored JSON value as text, so a hash made only of digits stays the text it was. */
    private static @Nullable String text(final String json) {
        final JsonElement value = JsonParser.parseString(json);
        if (!value.isJsonPrimitive()) {
            return null;
        }
        final String text = value.getAsString().strip();
        return text.isEmpty() ? null : text;
    }
}
