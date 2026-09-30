package eu.nordtal.s2.packrendering.hud;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.packrendering.Glyphs;
import java.util.Locale;
import java.util.function.Function;
import net.kyori.adventure.text.Component;

/**
 * The tab list's header and footer, composed once for every server on the network.
 * The wording is each module's unprefixed {@code tab.header} and {@code tab.footer}, which {@code TabListTest} holds
 * equal.
 */
public final class TabList {

    private TabList() {}

    /**
     * Returns the header, with the logo glyph substituted.
     *
     * @param messages the renderer; the keys are MiniMessage, so this is not {@code Messages}
     * @param locale the reader's language, unlike a nametag's flag, which is the wearer's
     * @param header the server's own header message, given the logo
     */
    public static Component header(
            final MessageRenderer messages, final Locale locale, final Function<Object, MessageRef> header) {
        return messages.format(locale, header.apply(Glyphs.LOGO_HEIGHT_32));
    }
}
