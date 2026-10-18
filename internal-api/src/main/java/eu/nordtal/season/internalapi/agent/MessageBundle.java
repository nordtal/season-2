package eu.nordtal.season.internalapi.agent;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * The message bundles of one jar as a form: every key they declare, in both languages, and the layouts that place them.
 *
 * @param service the compose service directory, for example {@code smp}
 * @param module the plugin's data directory under it, or the empty string
 * @param entries every key of the bundles: the keys of the schemas in their order, then the rest sorted by key
 * @param layouts every layout of the bundles as their {@code layouts.json} holds it, by bundle
 */
public record MessageBundle(String service, String module, List<MessageEntry> entries, List<JsonObject> layouts) {

    public MessageBundle {
        entries = List.copyOf(entries);
        layouts = List.copyOf(layouts);
    }
}
