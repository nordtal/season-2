package eu.nordtal.season.proxy.pack;

import static eu.nordtal.season.proxy.ProxyMessages.MESSAGES;

import eu.nordtal.season.messagerendering.MessageRenderer;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;

/** Builds the screens of the pack station in the language of the player. */
public final class PackMessages {

    private final MessageRenderer renderer;

    public PackMessages(final MessageRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /** The line in the resource pack prompt of the client. */
    public Component prompt(final Locale locale) {
        return renderer.format(locale, MESSAGES.pack().prompt());
    }

    /** The screen for a player who declined the pack. */
    public Component declined(final Locale locale) {
        return renderer.format(locale, MESSAGES.pack().declined());
    }

    /** The screen for a failed download or a SHA-1 mismatch. */
    public Component failedDownload(final Locale locale) {
        return renderer.format(locale, MESSAGES.pack().failedDownload());
    }

    /** The screen for a URL that does not load. */
    public Component invalidUrl(final Locale locale) {
        return renderer.format(locale, MESSAGES.pack().invalidUrl());
    }

    /** The screen for a client that did not answer within {@code pack#apply-timeout-seconds}. */
    public Component timedOut(final Locale locale) {
        return renderer.format(locale, MESSAGES.pack().timeout());
    }
}
