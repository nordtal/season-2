package eu.nordtal.s2.hungergames.player;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.message.PlayerLocales;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * What a player looks like in a line about them, on the hunger games.
 *
 * <p>A flag and a name, and nothing else. The SMP's composition adds a prestige crest earned over a
 * season and an aura balance; neither exists here - the event runs for an evening, and everybody
 * standing in the arena arrived at the same moment. Writing the same class with two of its three
 * pieces deleted is what {@code SystemLines.Composition} exists to avoid.</p>
 *
 * <h2>Whose language the flag is</h2>
 * <b>The person being looked at, never the person reading.</b> The flag exists so you know what to
 * greet somebody in, which is a fact about them. The sentence around it is rendered in the reader's
 * language, by {@code SystemLines}; the two are two different people on purpose.
 *
 * <h2>Why there is no team colour here</h2>
 * It would be the obvious thing and it cannot be done from this method. A team is a row in
 * {@code hg_member}, and this is called on the main thread for a join or a death and on Paper's chat
 * thread for a chat line - a query on either is the rule this repository has broken once already
 * (finding 96). A cache of the roster, refreshed where the roster is written, is what a team colour
 * here would need first; that is a decision to take with the owner rather than a lookup to slip in.
 */
public final class ArenaComposition {

    private final PlayerLocales locales;

    public ArenaComposition(final PlayerLocales locales) {
        this.locales = Objects.requireNonNull(locales, "locales");
    }

    /**
     * @param player whoever the line is about
     * @return their flag and their name, styled
     */
    public Component of(final Player player) {
        return Component.text(Glyphs.flagFor(locales.of(player.getUniqueId())))
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(" "))
                // Uniform light grey, the same as the SMP's: the name is never a rank and is never
                // coloured like one, and on this server a coloured name would read as a team.
                .append(Component.text(player.getName()).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
    }
}
