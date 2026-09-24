package eu.nordtal.s2.steward.worker.configfile;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One module's message bundle as a form: every key the jar declares, plus whatever an operator has
 * overridden, in both languages (steward/48).
 *
 * @param service   the compose service directory, e.g. {@code smp}
 * @param module    the plugin's own data directory under it, or the empty string - see
 *                  {@link MessageBundleLocation#module()}
 * @param writable  whether a save can actually be written - see {@link MessageBundleLocation#writable()}
 * @param entries   every key found in either language's packaged bundle, or in either language's
 *                  override: the keys the jar's {@code schema.json} describes in its order, then the rest sorted
 *                  by key. Merging several roots (a module usually loads
 *                  {@code commands} and its own root together, and a Paper plugin also loads
 *                  {@code paper-common}) has already happened by the time this is built - see
 *                  {@link MessageBundles#read}
 */
public record MessageBundle(
        @NotNull String service,
        @NotNull String module,
        boolean writable,
        @NotNull List<MessageEntry> entries) {

    public MessageBundle {
        entries = List.copyOf(entries);
    }
}
