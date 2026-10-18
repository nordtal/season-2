package eu.nordtal.season.hungergames.player;

import eu.nordtal.season.messagerendering.Names;
import eu.nordtal.season.messages.value.DisplayName;
import eu.nordtal.season.packrendering.LanguageFlags;
import eu.nordtal.season.papercommon.player.Identities;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Draws a player in the Hunger Games as a flag and a name. */
public final class ArenaComposition implements Names {

    private final Identities identities;

    public ArenaComposition(final Identities identities) {
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    /** Draws the flag of the language of the player and the name in grey. */
    @Override
    public Component draw(final DisplayName name, final Locale reader) {
        return Component.text(
                        LanguageFlags.of(identities.languageOf(name.player().value())))
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(" "))
                .append(Component.text(name.name())
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
    }
}
