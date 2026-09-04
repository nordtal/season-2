package eu.nordtal.s2.networkcontrol.pack;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;

import net.kyori.adventure.text.Component;

import java.util.Locale;
import java.util.Objects;

/**
 * The four things the pack station can say to a player, in that player's own language.
 * <p>
 * Same split as {@code GateMessages}: {@link PackStation} decides what happens, this renders it.
 * Everything here is reached only after the login gate has identified the account, so
 * {@code discord_user.locale} is known and there is no bilingual screen among them - that shape
 * belongs to the unlinked screen alone, where there is no language to pick.
 * </p>
 */
public final class PackMessages {

    private final Messages messages;

    public PackMessages(final Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * The line shown inside the client's own resource-pack prompt.
     * <p>
     * Only rendered by clients on 1.17 and newer, which on a 26.2 network is all of them.
     * </p>
     */
    public Component prompt(final Locale locale) {
        return MessageRenderer.of(messages).get(locale, "pack.prompt");
    }

    /**
     * The player refused the pack.
     * <p>
     * On a forced offer the client and Velocity between them will end the session anyway, with
     * Velocity's own generic text; this screen only lands if {@link PackStation} disconnects first,
     * from inside the awaited status event. That race is written down in
     * {@code pack.yml#force} and is one of the open verifications.
     * </p>
     */
    public Component declined(final Locale locale) {
        return MessageRenderer.of(messages).get(locale, "pack.declined");
    }

    /**
     * The client could not download the pack, or downloaded something whose SHA-1 did not match.
     * <p>
     * Those two are one status on the wire and cannot be told apart here, which is why the text
     * names both a connection problem and "try again" rather than blaming either.
     * </p>
     */
    public Component failedDownload(final Locale locale) {
        return MessageRenderer.of(messages).get(locale, "pack.failed-download");
    }

    /**
     * The URL itself did not load - a configuration error, not a player's problem, and the one
     * pack failure that will happen to <em>everybody</em> at once.
     */
    public Component invalidUrl(final Locale locale) {
        return MessageRenderer.of(messages).get(locale, "pack.invalid-url");
    }

    /** The client never answered the offer at all, for {@code pack.yml#apply-timeout-seconds}. */
    public Component timedOut(final Locale locale) {
        return MessageRenderer.of(messages).get(locale, "pack.timeout");
    }
}
