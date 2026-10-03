package eu.nordtal.s2.messages;

import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.messages.context.PlayerContext;
import java.time.ZoneId;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Who reads a message: its language, its zone and, for a player, who they are.
 * Every message can name the player as {@code {viewer}}, and they decide each player role's {@code self}.
 *
 * @param language the language, the network's default where the reader chose none
 * @param zone     the reader's own zone, or {@code null} for the network's
 * @param player   the player reading, or {@code null} for a console, a log or a reader not yet known
 */
public record Viewer(
        Locale language, @Nullable ZoneId zone, @Nullable PlayerContext player) {

    public Viewer {
        language = language == null ? Locales.DEFAULT : language;
    }

    /** Returns a reader known only by language, such as a console or a player before their identity is read. */
    public static Viewer of(final @Nullable Locale language) {
        return new Viewer(language == null ? Locales.DEFAULT : language, null, null);
    }
}
