package eu.nordtal.season.database.message;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.messages.MessageOverride;
import eu.nordtal.season.messages.Messages;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Stores message overrides network-wide, one row per bundle, key, language and variant.
 *
 * Every process reads the rows of its bundles. Steward writes them and signals the bundle.
 */
public interface MessageOverrideStore {

    /** Returns a store that borrows {@code dataSource}. */
    static MessageOverrideStore using(final DataSource dataSource) {
        return new JdbiMessageOverrideStore(dataSource);
    }

    /**
     * Layers the stored overrides over {@code messages} on connect and on every change.
     *
     * Call it before {@link SignalHub#start}. The first read runs on the hub's thread.
     */
    default void follow(final Messages messages, final SignalHub hub) {
        hub.watch(
                Channel.MESSAGES,
                "the message overrides",
                null,
                () -> overrides(messages.bundles()),
                messages::override);
    }

    List<MessageOverride> overrides(Collection<String> bundles);

    /** Returns the packaged texts that each override replaced, by {@code bundle/key/language}. */
    Map<String, List<String>> originals(Collection<String> bundles);

    /**
     * Replaces the texts of one key in one language and signals the bundle.
     *
     * @param texts the variants in order. Empty removes the override
     * @param original the packaged texts it replaces, in order
     */
    void change(String bundle, String key, String language, List<String> texts, List<String> original, Actor actor);
}
