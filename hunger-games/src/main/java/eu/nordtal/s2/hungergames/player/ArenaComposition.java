package eu.nordtal.s2.hungergames.player;

import eu.nordtal.s2.packrendering.LanguageFlags;
import eu.nordtal.s2.papercommon.player.Identities;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

/**
 * What a player looks like in a line about them on the hunger games: a flag and a name.
 *
 * The flag is the language of the person looked at. A team colour would need a roster query on the main thread.
 */
public final class ArenaComposition {

    private final Identities identities;

    public ArenaComposition(final Identities identities) {
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    /** Renders the player's flag and name, styled. */
    public Component of(final Player player) {
        return ofName(player.getName(), player.getUniqueId());
    }

    /**
     * The same line for somebody who is not here, such as a body's owner, with the flag in their language.
     *
     * @param name whatever the line should call them
     * @param uuid their Minecraft uuid, for the flag
     */
    public Component ofName(final String name, final UUID uuid) {
        return Component.text(LanguageFlags.of(identities.languageOf(uuid)))
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(" "))
                // Uniform light grey, like the SMP's: a coloured name would read as a team here.
                .append(Component.text(name).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
    }
}
