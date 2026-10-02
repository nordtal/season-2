package eu.nordtal.s2.stewardagent.apply;

import com.google.gson.JsonPrimitive;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.stewardagent.plan.PackState;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Sets the pack the proxy sends: {@code url} and {@code sha1} of its {@code pack} group, as Steward. */
public final class PackWriter {

    private PackWriter() {}

    /**
     * Writes the two values, which the proxy takes at its next start.
     *
     * @return {@code true} if they were written, {@code false} if the proxy's group already said this
     */
    public static boolean write(final SettingStore store, final String url, final String sha1) {
        final PackState wanted = new PackState(url, sha1);
        final Map<String, @Nullable String> values = Map.of(
                "url", new JsonPrimitive(url).toString(),
                "sha1", new JsonPrimitive(sha1).toString());
        return store.change(
                PackState.SERVICE,
                PackState.GROUP,
                values,
                Actor.STEWARD,
                held -> !PackState.of(held).equals(wanted));
    }
}
