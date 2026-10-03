package eu.nordtal.s2.hungergames.player;

import eu.nordtal.s2.messagerendering.Names;
import eu.nordtal.s2.messages.value.DisplayName;
import eu.nordtal.s2.packrendering.LanguageFlags;
import eu.nordtal.s2.papercommon.player.Identities;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * What a player looks like in a line about them on the hunger games: a flag and a name.
 *
 * The flag is the language of the person looked at. A team colour would need a roster query on the main thread.
 */
public final class ArenaComposition implements Names {

    private final Identities identities;

    public ArenaComposition(final Identities identities) {
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    /** Draws the player's flag, in their own language, and their name; a body's owner is drawn the same. */
    @Override
    public Component draw(final DisplayName name, final Locale reader) {
        return Component.text(
                        LanguageFlags.of(identities.languageOf(name.player().value())))
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(" "))
                // Uniform light grey, like the SMP's: a coloured name would read as a team here.
                .append(Component.text(name.name())
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
    }
}
