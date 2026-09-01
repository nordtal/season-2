package eu.nordtal.s2.smp.player;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.smp.prestige.Prestige;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;

/**
 * What a player looks like, in the three places they are drawn.
 *
 * <p>One composition, shown in full where there is room and trimmed where there is not
 * (docs/smp.md#what-a-player-looks-like):
 *
 * <table>
 *   <caption>the three surfaces</caption>
 *   <tr><th>surface</th><th>shows</th></tr>
 *   <tr><td>tab list</td><td>all six, sorted by online time</td></tr>
 *   <tr><td>nametag</td><td>flag, name, admin/donor, crest - <b>no aura</b></td></tr>
 *   <tr><td>chat</td><td>flag, name, crest</td></tr>
 * </table>
 *
 * <p><b>The nametag deliberately omits the aura</b>, and that is a performance decision rather than
 * a design preference: aura changes on every death, every hand-in and every duel, and a nametag
 * that carried it would send a packet to everyone in range each time.
 *
 * <p>Everything here is drawn from a {@link Identity} and nothing touches a server, so the whole
 * composition is asserted in tests rather than looked at on a screen.
 */
public final class PlayerComposition {

    private final Prestige prestige;

    public PlayerComposition(final Prestige prestige) {
        this.prestige = prestige;
    }

    /** All six, for the tab list. */
    public Component tabList(final String name, final Identity identity) {
        return flag(identity.locale())
                .append(Component.text(" "))
                .append(name(name))
                .append(badges(identity))
                .append(crest(identity))
                .append(Component.text(" "))
                .append(aura(identity.aura()));
    }

    /** Everything except the aura, for the nametag DisplayTags renders. */
    public Component nameTag(final String name, final Identity identity) {
        return flag(identity.locale())
                .append(Component.text(" "))
                .append(name(name))
                .append(badges(identity))
                .append(crest(identity));
    }

    /** Flag, name and crest, for a chat line. */
    public Component chatPrefix(final String name, final Identity identity) {
        return flag(identity.locale())
                .append(Component.text(" "))
                .append(name(name))
                .append(crest(identity));
    }

    // ------------------------------------------------------------------ pieces

    /**
     * The wearer's language, as a flag glyph.
     *
     * <p>Whose language is worth being explicit about: it is the person being <em>looked at</em>,
     * not the person looking. The flag exists so you know what to greet somebody in.
     */
    private Component flag(final Locale locale) {
        return Component.text(Glyphs.flagFor(locale)).decoration(TextDecoration.ITALIC, false);
    }

    /** Uniform light grey, always - the name is never a rank and is never coloured like one. */
    private Component name(final String name) {
        return Component.text(name).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component badges(final Identity identity) {
        Component out = Component.empty();
        if (identity.admin()) {
            out = out.append(Component.text(" " + Glyphs.TAG_ADMIN));
        }
        if (identity.donor()) {
            out = out.append(Component.text(" " + Glyphs.BADGE_DONOR_STAR));
        }
        return out;
    }

    /**
     * The crest for however long somebody has been here.
     *
     * <p>Everybody has one - {@link Prestige#tierOf} floors at tier 1 - so this is never empty and
     * the composition never has to reflow around a missing piece. Thirteen designs, thirteen tiers,
     * and a fourteenth would have nothing to render as.
     */
    private Component crest(final Identity identity) {
        final int tier = prestige.tierOf(identity.playtimeSeconds());
        return Component.text(" " + Glyphs.PRESTIGE_CRESTS[tier - 1])
                .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Green when positive, red at zero or below.
     *
     * <p>Zero is red rather than neutral on purpose: aura is recognition, and having none is the
     * same state as having spent it all on deaths.
     */
    private Component aura(final int amount) {
        return Component.text(String.valueOf(amount))
                .color(amount > 0 ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false);
    }
}
