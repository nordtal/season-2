package eu.nordtal.s2.common.hud;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.message.MessageRenderer;

import net.kyori.adventure.text.Component;

import java.util.Locale;

/**
 * The tab list's header and footer, composed once for every server on the network.
 *
 * <h2>Why this is in :common and not three times over</h2>
 * A player crosses limbo, the hunger games and the SMP inside one session, and the tab list is the
 * one surface that is the <em>same object</em> the whole way across - the client never clears it,
 * each backend simply overwrites it. Three modules each drawing their own version of "the nordtal
 * header" is three versions of one picture that drift the first time somebody improves one of them,
 * and the drift is invisible unless you happen to press Tab on the right server.
 *
 * <p>So the composition is here, the wording is in the {@code tab.header} / {@code tab.footer} keys
 * of each module's own bundle - deliberately <b>unprefixed</b>, so the three read identically - and
 * {@code TabListTest} asserts the three bundles agree. The keys are per module rather than in a
 * shared bundle so that an operator overriding them edits one file in the data folder of the server
 * they are looking at, which is where they already are.</p>
 *
 * <h2>The logo is a parameter, not a character in a .properties file</h2>
 * {@link Glyphs#LOGO_HEIGHT_32} is a private-use code point. Written into a bundle it is an
 * invisible character that survives exactly until somebody's editor normalises the file; passed as
 * {@code {logo}} it stays in Java, where the constant that owns it lives.
 *
 * <h2>The counts are this server's</h2>
 * Which is right rather than a compromise: the list above the footer holds the players on this
 * backend, so a number that counted the whole network would contradict the thing it sits under.
 */
public final class TabList {

    private TabList() {
    }

    /**
     * @param messages the renderer; the keys are MiniMessage, so this is not {@code Messages}
     * @param locale   the reader's language - unlike a nametag's flag, which is the wearer's
     * @return the header, with the logo glyph substituted
     */
    public static Component header(final MessageRenderer messages, final Locale locale) {
        return messages.format(locale, "tab.header", "logo", Glyphs.LOGO_HEIGHT_32);
    }

    /**
     * @param messages the renderer
     * @param locale   the reader's language
     * @param online   players on this server
     * @param max      this server's player cap
     * @return the footer
     */
    public static Component footer(final MessageRenderer messages, final Locale locale,
                                   final int online, final int max) {
        return messages.format(locale, "tab.footer", "online", online, "max", max);
    }
}
