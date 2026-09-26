package eu.nordtal.s2.hungergames.player;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.message.PlayerLocales;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

/**
 * What a player looks like in a line about them, on the hunger games.
 *
 * A flag and a name, and nothing else. The SMP's composition adds a prestige crest earned over a
 * season and an aura balance; neither exists here - the event runs for an evening, and everybody
 * standing in the arena arrived at the same moment. Writing the same class with two of its three
 * pieces deleted is what {@code SystemLines.Composition} exists to avoid.
 *
 * Whose language the flag is: <b>the person being looked at, never the person reading.</b> The flag
 * exists so you know what to greet somebody in, which is a fact about them. The sentence around it
 * is rendered in the reader's language, by {@code SystemLines}; the two are two different people on
 * purpose.
 *
 * Why there is no team colour here: it would be the obvious thing and it cannot be done from this
 * method. A team is a row in {@code hg_member}, and this is called on the main thread for a join or
 * a death and on Paper's chat thread for a chat line, and a query on either is a rule this
 * repository has already broken once. A cache of the roster, refreshed where the roster is written,
 * is what a team colour here would need first; that is a decision to take with the owner rather
 * than a lookup to slip in.
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
        return ofName(player.getName(), player.getUniqueId());
    }

    /**
     * The same line for somebody who is not here.
     *
     * A participant who disconnects mid-game leaves an armor stand standing in for them, and a
     * body can be killed. There is no {@link Player} to ask for either half then - the name comes
     * off the marker and the uuid off the body's owner - and the language is still <em>theirs</em>,
     * which is the whole point of the flag: it is a fact about the person, not about who is reading.
     *
     * @param name whatever the line should call them
     * @param uuid their Minecraft uuid, for the flag
     */
    public Component ofName(final String name, final UUID uuid) {
        return Component.text(Glyphs.flagFor(locales.of(uuid)))
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(" "))
                // Uniform light grey, like the SMP's: a coloured name would read as a team here.
                .append(Component.text(name).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
    }
}
