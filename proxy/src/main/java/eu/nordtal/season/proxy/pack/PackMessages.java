package eu.nordtal.season.proxy.pack;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;

/** Builds the screens of the pack station in the language of the player. */
public final class PackMessages {

    static final DocumentSpec<DocumentSpec.None> PROMPT = screen("pack-prompt").titled("Resource pack request");
    static final DocumentSpec<DocumentSpec.None> DECLINED =
            screen("pack-declined").titled("Resource pack declined");
    static final DocumentSpec<DocumentSpec.None> FAILED_DOWNLOAD =
            screen("pack-failed-download").titled("Resource pack download failed");
    static final DocumentSpec<DocumentSpec.None> INVALID_URL =
            screen("pack-invalid-url").titled("Resource pack unavailable");
    static final DocumentSpec<DocumentSpec.None> TIMEOUT =
            screen("pack-timeout").titled("Resource pack request unanswered");

    private final MessageRenderer renderer;

    public PackMessages(final MessageRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /** The line in the resource pack prompt of the client. */
    public Component prompt(final Locale locale) {
        return renderer.format(locale, Sent.of(PROMPT));
    }

    /** The screen for a player who declined the pack. */
    public Component declined(final Locale locale) {
        return renderer.format(locale, Sent.of(DECLINED));
    }

    /** The screen for a failed download or a SHA-1 mismatch. */
    public Component failedDownload(final Locale locale) {
        return renderer.format(locale, Sent.of(FAILED_DOWNLOAD));
    }

    /** The screen for a URL that does not load. */
    public Component invalidUrl(final Locale locale) {
        return renderer.format(locale, Sent.of(INVALID_URL));
    }

    /** The screen for a client that did not answer within {@code pack#apply-timeout-seconds}. */
    public Component timedOut(final Locale locale) {
        return renderer.format(locale, Sent.of(TIMEOUT));
    }

    private static DocumentSpec<DocumentSpec.None> screen(final String name) {
        return DocumentSpec.game("proxy", name, "Resource pack", DocumentSpec.Shape.KICK_SCREEN);
    }
}
