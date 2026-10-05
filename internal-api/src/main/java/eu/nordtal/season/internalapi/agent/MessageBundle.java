package eu.nordtal.season.internalapi.agent;

import java.util.List;

/**
 * One jar's message bundles as a form: every key they declare, in both languages.
 *
 * @param service the compose service directory, for example {@code smp}
 * @param module the plugin's data directory under it, or the empty string
 * @param entries every key of the jar's bundles: the schemas' keys in their order, then the rest sorted by key
 */
public record MessageBundle(String service, String module, List<MessageEntry> entries) {

    public MessageBundle {
        entries = List.copyOf(entries);
    }
}
