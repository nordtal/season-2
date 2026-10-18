package eu.nordtal.season.messages;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.messages.context.PlayerContext;
import java.time.ZoneId;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The reader of a message.
 * Every message can name the player as {@code {viewer}}, who is the {@code self} of each role.
 *
 * @param language the reader's language, the network's default if the reader chose none
 * @param zone     the reader's zone, or {@code null} for the network's
 * @param player   the reading player, or {@code null} for a console, a log or an unknown reader
 */
public record Viewer(
        Locale language, @Nullable ZoneId zone, @Nullable PlayerContext player) {

    public Viewer {
        language = language == null ? Locales.DEFAULT : language;
    }

    public static Viewer of(final @Nullable Locale language) {
        return new Viewer(language == null ? Locales.DEFAULT : language, null, null);
    }
}
