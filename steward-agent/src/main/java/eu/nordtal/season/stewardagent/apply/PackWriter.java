package eu.nordtal.season.stewardagent.apply;

import com.google.gson.JsonPrimitive;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.settings.PackState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Sets the packs the proxy sends: {@code url} and {@code sha1} of its {@code pack} group, as Steward.
 *
 * The agent writes them after each pack it built, and {@code map-url} and {@code map-sha1} after each map pack.
 */
public final class PackWriter {

    private PackWriter() {}

    /**
     * Writes the two values, which the proxy offers at once to every player in its waiting room.
     *
     * @return {@code true} if they were written, {@code false} if the proxy's group already said this
     */
    public static boolean write(final SettingStore store, final String url, final String sha1) {
        final PackState wanted = new PackState(url, sha1);
        return write(store, "url", "sha1", wanted, held -> !PackState.of(held).equals(wanted));
    }

    /**
     * Writes the map pack, which the proxy offers beside the language pack to every player who joins next.
     *
     * @return {@code true} if it was written, {@code false} if the proxy's group already said this
     */
    public static boolean writeMap(final SettingStore store, final String url, final String sha1) {
        final PackState wanted = new PackState(url, sha1);
        return write(
                store,
                "map-url",
                "map-sha1",
                wanted,
                held -> !PackState.ofMap(held).equals(wanted));
    }

    private static boolean write(
            final SettingStore store,
            final String urlKey,
            final String sha1Key,
            final PackState wanted,
            final Predicate<List<SettingStore.Value>> differs) {
        final Map<String, @Nullable String> values = Map.of(
                urlKey, new JsonPrimitive(Objects.requireNonNull(wanted.url())).toString(),
                sha1Key, new JsonPrimitive(Objects.requireNonNull(wanted.sha1())).toString());
        return store.change(PackState.SERVICE, PackState.GROUP, values, Actor.STEWARD, differs);
    }
}
