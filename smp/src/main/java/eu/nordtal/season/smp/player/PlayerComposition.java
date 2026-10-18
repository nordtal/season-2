package eu.nordtal.season.smp.player;

import eu.nordtal.season.database.access.PlayerIdentity;
import eu.nordtal.season.database.access.Prestige;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.LanguageFlags;
import eu.nordtal.season.smp.prestige.PrestigeColours;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Builds the text of a player for the tab list, the nametag and chat.
 *
 * The nametag omits the aura.
 */
public final class PlayerComposition {

    /** The colour of positive aura. It equals the colour of the join line. */
    private static final TextColor AURA_POSITIVE = Objects.requireNonNull(TextColor.fromHexString("#8ba888"));

    /** The colour of aura at zero or below. It equals the colour of the leave line. */
    private static final TextColor AURA_EMPTY = Objects.requireNonNull(TextColor.fromHexString("#a8888b"));

    private static final TextColor DONOR = Objects.requireNonNull(TextColor.fromHexString("#f2b84b"));

    private final Supplier<Prestige> prestige;

    /** Read on every call, because a settings change replaces the colour table. */
    private final Supplier<PrestigeColours> colours;

    public PlayerComposition(final Supplier<Prestige> prestige, final Supplier<PrestigeColours> colours) {
        this.prestige = Objects.requireNonNull(prestige, "prestige");
        this.colours = Objects.requireNonNull(colours, "colours");
    }

    public Component tabList(final String name, final PlayerIdentity identity) {
        return flag(identity.language())
                .append(Component.text(" "))
                .append(name(name, identity))
                .append(badges(identity))
                .append(crest(identity))
                .append(Component.text(" "))
                .append(aura(identity.aura()));
    }

    public Component nameTag(final String name, final PlayerIdentity identity) {
        return flag(identity.language())
                .append(Component.text(" "))
                .append(name(name, identity))
                .append(badges(identity))
                .append(crest(identity));
    }

    public Component chatPrefix(final String name, final PlayerIdentity identity) {
        return flag(identity.language())
                .append(Component.text(" "))
                .append(name(name, identity))
                .append(crest(identity));
    }

    /** Returns the flag of the language of the player, not of the viewer. */
    private Component flag(final Locale locale) {
        return Component.text(LanguageFlags.of(locale)).decoration(TextDecoration.ITALIC, false);
    }

    private Component name(final String name, final PlayerIdentity identity) {
        return Component.text(name).color(nameColour(identity)).decoration(TextDecoration.ITALIC, false);
    }

    /** Returns the admin colour for an admin, otherwise the colour of the prestige tier. */
    private TextColor nameColour(final PlayerIdentity identity) {
        final PrestigeColours palette = colours.get();
        return identity.admin() ? palette.admin() : palette.tier(tierOf(identity));
    }

    private int tierOf(final PlayerIdentity identity) {
        return prestige.get().tierOf(identity.playtimeSeconds());
    }

    private Component badges(final PlayerIdentity identity) {
        Component out = Component.empty();
        if (identity.admin()) {
            out = out.append(Component.text(" " + Glyphs.TAG_ADMIN));
        }
        if (identity.donor()) {
            out = out.append(Component.text(" " + Glyphs.BADGE_DONOR_STAR)
                    .color(DONOR)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return out;
    }

    private Component crest(final PlayerIdentity identity) {
        final int tier = tierOf(identity);
        return Component.text(" " + Glyphs.PRESTIGE_CRESTS.get(tier - 1))
                .color(colours.get().tier(tier))
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component aura(final int amount) {
        return Component.text(String.valueOf(amount))
                .color(amount > 0 ? AURA_POSITIVE : AURA_EMPTY)
                .decoration(TextDecoration.ITALIC, false);
    }
}
