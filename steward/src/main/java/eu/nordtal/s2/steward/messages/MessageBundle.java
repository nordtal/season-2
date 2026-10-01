package eu.nordtal.s2.steward.messages;

import java.util.List;

/**
 * One module's message bundle as a form: every declared or overridden key, in both languages.
 *
 * @param service the compose service directory, for example {@code smp}
 * @param module the plugin's data directory under it, or the empty string (see {@link MessageBundleLocation#module()})
 * @param writable whether a save can be written (see {@link MessageBundleLocation#writable()})
 * @param entries every key of the merged roots: the schema's keys in its order, then the rest sorted by key
 */
public record MessageBundle(String service, String module, boolean writable, List<MessageEntry> entries) {

    public MessageBundle {
        entries = List.copyOf(entries);
    }
}
