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
 * Where an admin's message overrides are stored, network-wide: one row per bundle, key, language and variant.
 *
 * Every process reads the rows of the bundles it loads; steward writes them, and a write signals the bundle.
 */
public interface MessageOverrideStore {

    /** Returns the store in the database behind {@code dataSource}, which the caller owns. */
    static MessageOverrideStore using(final DataSource dataSource) {
        return new JdbiMessageOverrideStore(dataSource);
    }

    /**
     * Layers the stored overrides over {@code messages} as the hub connects and again whenever they change.
     *
     * Before {@link SignalHub#start}; the first read runs on the hub's thread, so a Paper server never waits for it.
     */
    default void follow(final Messages messages, final SignalHub hub) {
        hub.watch(
                Channel.MESSAGES,
                "the message overrides",
                null,
                () -> overrides(messages.bundles()),
                messages::override);
    }

    /** Returns every override of these bundles, in one read, by bundle, key, language and variant. */
    List<MessageOverride> overrides(Collection<String> bundles);

    /**
     * Returns the packaged texts each override of these bundles replaced, by {@code bundle/key/language}.
     * Steward shows them beside a text a release changed; a key and language whose override replaced none is absent.
     */
    Map<String, List<String>> originals(Collection<String> bundles);

    /**
     * Replaces the texts of one key in one language as one set of variants, and signals the bundle.
     *
     * @param texts    the variants in order; empty removes the override, so the packaged texts apply again
     * @param original the packaged texts the override replaces, in order, empty where the language has none; their
     *                 hash is what tells every process later that a release changed them
     */
    void change(String bundle, String key, String language, List<String> texts, List<String> original, Actor actor);
}
