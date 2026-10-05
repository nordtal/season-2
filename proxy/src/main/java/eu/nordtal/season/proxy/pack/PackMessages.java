package eu.nordtal.season.proxy.pack;

import static eu.nordtal.season.proxy.ProxyMessages.MESSAGES;

import eu.nordtal.season.messagerendering.MessageRenderer;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;

/** The screens the pack station shows a player, in their own language. */
public final class PackMessages {

    private final MessageRenderer renderer;

    public PackMessages(final MessageRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /** The line shown inside the client's own resource-pack prompt. */
    public Component prompt(final Locale locale) {
        return renderer.format(locale, MESSAGES.pack().prompt());
    }

    /**
     * The player refused the pack.
     *
     * On a forced offer this lands only if {@link PackStation} disconnects before Velocity does.
     */
    public Component declined(final Locale locale) {
        return renderer.format(locale, MESSAGES.pack().declined());
    }

    /** The download failed or its SHA-1 did not match, which the wire cannot tell apart. */
    public Component failedDownload(final Locale locale) {
        return renderer.format(locale, MESSAGES.pack().failedDownload());
    }

    /** The URL itself did not load: a configuration error that hits everybody at once. */
    public Component invalidUrl(final Locale locale) {
        return renderer.format(locale, MESSAGES.pack().invalidUrl());
    }

    /** The client never answered the offer within {@code pack#apply-timeout-seconds}. */
    public Component timedOut(final Locale locale) {
        return renderer.format(locale, MESSAGES.pack().timeout());
    }
}
