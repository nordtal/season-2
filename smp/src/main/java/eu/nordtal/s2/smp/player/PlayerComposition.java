package eu.nordtal.s2.smp.player;

import eu.nordtal.s2.database.access.PlayerIdentity;
import eu.nordtal.s2.packrendering.Glyphs;
import eu.nordtal.s2.packrendering.LanguageFlags;
import eu.nordtal.s2.smp.prestige.Prestige;
import eu.nordtal.s2.smp.prestige.PrestigeColours;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * What a player looks like on the tab list, the nametag and in chat.
 *
 * The nametag leaves out the aura, which changes too often for a packet to everyone in range.
 */
public final class PlayerComposition {

    /** The join line's colour, reused for positive aura. */
    private static final TextColor AURA_POSITIVE = Objects.requireNonNull(TextColor.fromHexString("#8ba888"));

    /** The leave line's colour, for aura at zero or below. */
    private static final TextColor AURA_EMPTY = Objects.requireNonNull(TextColor.fromHexString("#a8888b"));

    private final Supplier<Prestige> prestige;

    /** A supplier, because {@code /smp reload} replaces the colour table. */
    private final Supplier<PrestigeColours> colours;

    public PlayerComposition(final Supplier<Prestige> prestige, final Supplier<PrestigeColours> colours) {
        // A supplier: the prestige group is re-read by /smp reload, so this table is asked for fresh each time.
        this.prestige = Objects.requireNonNull(prestige, "prestige");
        this.colours = Objects.requireNonNull(colours, "colours");
    }

    /** All six, for the tab list. */
    public Component tabList(final String name, final PlayerIdentity identity) {
        return flag(identity.language())
                .append(Component.text(" "))
                .append(name(name, identity))
                .append(badges(identity))
                .append(crest(identity))
                .append(Component.text(" "))
                .append(aura(identity.aura()));
    }

    /** Everything except the aura, for the nametag DisplayTags renders. */
    public Component nameTag(final String name, final PlayerIdentity identity) {
        return flag(identity.language())
                .append(Component.text(" "))
                .append(name(name, identity))
                .append(badges(identity))
                .append(crest(identity));
    }

    /** Flag, name and crest, for a chat line. */
    public Component chatPrefix(final String name, final PlayerIdentity identity) {
        return flag(identity.language())
                .append(Component.text(" "))
                .append(name(name, identity))
                .append(crest(identity));
    }

    /** The wearer's language as a flag glyph, not the viewer's. */
    private Component flag(final Locale locale) {
        return Component.text(LanguageFlags.of(locale)).decoration(TextDecoration.ITALIC, false);
    }

    /** The prestige colour, or the admin colour, which wins. */
    private Component name(final String name, final PlayerIdentity identity) {
        return Component.text(name).color(nameColour(identity)).decoration(TextDecoration.ITALIC, false);
    }

    /** Returns the admin colour when {@link PlayerIdentity#admin()} is set, otherwise the prestige tier's colour. */
    private TextColor nameColour(final PlayerIdentity identity) {
        final PrestigeColours palette = colours.get();
        return identity.admin() ? palette.admin() : palette.tier(tierOf(identity));
    }

    /** Returns the tier {@link #crest} also draws. */
    private int tierOf(final PlayerIdentity identity) {
        return prestige.get().tierOf(identity.playtimeSeconds());
    }

    private Component badges(final PlayerIdentity identity) {
        Component out = Component.empty();
        if (identity.admin()) {
            out = out.append(Component.text(" " + Glyphs.TAG_ADMIN));
        }
        if (identity.donor()) {
            out = out.append(Component.text(" " + Glyphs.BADGE_DONOR_STAR));
        }
        return out;
    }

    /** The crest for however long somebody has been here, never empty since tiers start at 1. */
    private Component crest(final PlayerIdentity identity) {
        final int tier = tierOf(identity);
        return Component.text(" " + Glyphs.PRESTIGE_CRESTS.get(tier - 1)).decoration(TextDecoration.ITALIC, false);
    }

    /** Green when positive, red at zero or below, in the palette's colours to match the join and leave lines. */
    private Component aura(final int amount) {
        return Component.text(String.valueOf(amount))
                .color(amount > 0 ? AURA_POSITIVE : AURA_EMPTY)
                .decoration(TextDecoration.ITALIC, false);
    }
}
