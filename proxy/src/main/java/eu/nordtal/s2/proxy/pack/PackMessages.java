package eu.nordtal.s2.proxy.pack;

import static eu.nordtal.s2.proxy.ProxyMessages.MESSAGES;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;

/** The screens the pack station shows a player, in their own language. */
public final class PackMessages {

    private final Messages messages;

    public PackMessages(final Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /** The line shown inside the client's own resource-pack prompt. */
    public Component prompt(final Locale locale) {
        return MessageRenderer.of(messages).format(locale, MESSAGES.pack().prompt());
    }

    /**
     * The player refused the pack.
     *
     * On a forced offer this lands only if {@link PackStation} disconnects before Velocity does.
     */
    public Component declined(final Locale locale) {
        return MessageRenderer.of(messages).format(locale, MESSAGES.pack().declined());
    }

    /** The download failed or its SHA-1 did not match, which the wire cannot tell apart. */
    public Component failedDownload(final Locale locale) {
        return MessageRenderer.of(messages).format(locale, MESSAGES.pack().failedDownload());
    }

    /** The URL itself did not load: a configuration error that hits everybody at once. */
    public Component invalidUrl(final Locale locale) {
        return MessageRenderer.of(messages).format(locale, MESSAGES.pack().invalidUrl());
    }

    /** The client never answered the offer within {@code pack.yml#apply-timeout-seconds}. */
    public Component timedOut(final Locale locale) {
        return MessageRenderer.of(messages).format(locale, MESSAGES.pack().timeout());
    }
}
