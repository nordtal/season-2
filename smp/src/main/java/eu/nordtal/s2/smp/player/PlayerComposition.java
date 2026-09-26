package eu.nordtal.s2.smp.player;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.smp.prestige.Prestige;
import eu.nordtal.s2.smp.prestige.PrestigeColours;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * What a player looks like, in the three places they are drawn.
 *
 * <p>One composition, shown in full where there is room and trimmed where there is not:
 *
 * <table>
 *   <caption>the three surfaces</caption>
 *   <tr><th>surface</th><th>shows</th></tr>
 *   <tr><td>tab list</td><td>all six, sorted by online time</td></tr>
 *   <tr><td>nametag</td><td>flag, name, admin/donor, crest - <b>no aura</b></td></tr>
 *   <tr><td>chat</td><td>flag, name, crest</td></tr>
 * </table>
 *
 * <p><b>The nametag omits the aura</b> for performance: aura changes on every death, hand-in and
 * duel, and a nametag that carried it would send a packet to everyone in range each time.
 *
 * <h2>The name carries the prestige colour (season-2-ingame/23)</h2>
 * {@link #name} is the single seam every one of these three surfaces paints a name through - the
 * tab list, the nametag DisplayTags renders and the chat prefix {@code SystemLines} also uses for
 * every join, leave, death and advancement line - so colouring it once here reaches all of them by
 * construction rather than by four call sites agreeing to do the same thing. An admin's colour wins
 * over their prestige tier; see {@link PrestigeColours} for why that is not a fourteenth tier.
 */
public final class PlayerComposition {

    /** The join line's colour, reused for aura somebody has. */
    private static final TextColor AURA_POSITIVE = TextColor.fromHexString("#8ba888");

    /** ...and the leaving one, for aura somebody has spent or never earned. */
    private static final TextColor AURA_EMPTY = TextColor.fromHexString("#a8888b");

    private final Supplier<Prestige> prestige;

    /**
     * A supplier, not a captured value, for the same reason {@code SmpPlugin.track} is one: a
     * reference held here at construction would not notice {@code /smp reload} replacing the field
     * it was read from.
     */
    private final Supplier<PrestigeColours> colours;

    public PlayerComposition(final Supplier<Prestige> prestige, final Supplier<PrestigeColours> colours) {
        // A supplier since steward/130: the ladder moved into `prestige.yml` beside the colours,
        // and that file is re-read by `/smp reload` - so the table this composes from has to be
        // asked for each time, exactly as the palette beside it already was.
        this.prestige = Objects.requireNonNull(prestige, "prestige");
        this.colours = Objects.requireNonNull(colours, "colours");
    }

    /** All six, for the tab list. */
    public Component tabList(final String name, final Identity identity) {
        return flag(identity.locale())
                .append(Component.text(" "))
                .append(name(name, identity))
                .append(badges(identity))
                .append(crest(identity))
                .append(Component.text(" "))
                .append(aura(identity.aura()));
    }

    /** Everything except the aura, for the nametag DisplayTags renders. */
    public Component nameTag(final String name, final Identity identity) {
        return flag(identity.locale())
                .append(Component.text(" "))
                .append(name(name, identity))
                .append(badges(identity))
                .append(crest(identity));
    }

    /** Flag, name and crest, for a chat line. */
    public Component chatPrefix(final String name, final Identity identity) {
        return flag(identity.locale())
                .append(Component.text(" "))
                .append(name(name, identity))
                .append(crest(identity));
    }

    // ------------------------------------------------------------------ pieces

    /**
     * The wearer's language, as a flag glyph.
     *
     * <p>The language of the person being <em>looked at</em>, not of the person looking.
     */
    private Component flag(final Locale locale) {
        return Component.text(Glyphs.flagFor(locale)).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * The prestige colour, or the admin colour if it wins (season-2-ingame/23).
     *
     * <p>Until this ticket the name was uniform light grey everywhere, on purpose - see the git
     * history for the comment this replaced. That was the one thing every surface agreed on and the
     * reason a tier-4 and a tier-13 player were indistinguishable at a glance; the whole point of
     * this method existing is that they no longer are.
     */
    private Component name(final String name, final Identity identity) {
        return Component.text(name).color(nameColour(identity)).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * @return the admin colour if {@link Identity#admin()} is set, otherwise this identity's
     *         prestige-tier colour - never both, and never a fourteenth tier of its own
     */
    private TextColor nameColour(final Identity identity) {
        final PrestigeColours palette = colours.get();
        return identity.admin() ? palette.admin() : palette.tier(tierOf(identity));
    }

    /** @return the tier {@link #crest} also draws - one derivation, read from both places. */
    private int tierOf(final Identity identity) {
        return prestige.get().tierOf(identity.playtimeSeconds());
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
     * <p>Everybody has one - {@link Prestige#tierOf} floors at tier 1 - so this is never empty.
     * Thirteen designs, thirteen tiers; a fourteenth would have nothing to render as.
     */
    private Component crest(final Identity identity) {
        final int tier = tierOf(identity);
        return Component.text(" " + Glyphs.PRESTIGE_CRESTS.get(tier - 1)).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Green when positive, red at zero or below.
     *
     * <p>The two values are the palette's, not {@code NamedTextColor}'s, so that they match the
     * join and leave lines the tab-list header sits under.
     */
    private Component aura(final int amount) {
        return Component.text(String.valueOf(amount))
                .color(amount > 0 ? AURA_POSITIVE : AURA_EMPTY)
                .decoration(TextDecoration.ITALIC, false);
    }
}
