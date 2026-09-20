package eu.nordtal.s2.smp.prestige;

import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The fourteen colours a player's name is drawn in: one per {@link Prestige} tier, plus the one that
 * overrides every tier - the admin colour (season-2-ingame/23).
 *
 * <p>This is {@code smp}'s own half of the same split {@code :common}'s
 * {@link eu.nordtal.s2.common.message.ToneColours} drew for the five tone colours
 * (season-2-ingame/22): the parsed result lives beside the thing it paints - here, because
 * {@link Prestige} itself is {@code smp}-only and nothing outside this module has a prestige tier to
 * colour - and a bad hex value is corrected rather than refused, for the same reason a bad tone is.
 *
 * <h2>Everywhere is one seam</h2>
 * {@code PlayerComposition#name} is the only place a name is painted, and the tab list, the chat
 * prefix and the nametag DisplayTags renders all call through it - so this class has exactly one
 * caller and "everywhere" follows from that, rather than from four places agreeing to do the same
 * thing.
 *
 * <h2>The admin colour is not a fourteenth tier</h2>
 * An admin at prestige tier 13 still shows the admin colour, never tier 13's - authority is a role, a
 * player can lose in an instant when a Discord role is revoked (see {@code Identity#withAdmin}), and
 * a rank earned over a season is not. Keeping it a sibling setting rather than a fourteenth entry in
 * the tier list is what keeps the two ideas from merging into one config key.
 *
 * <h2>The thirteen, and why none of them is a saturated blue or red held down in brightness</h2>
 * Every default here reads at or above roughly 6:1 contrast against pure black - Minecraft's own chat
 * background is {@code rgba(0,0,0,0.5)}, painted over whatever is behind it, so pure black is the
 * worst case worth designing for - which is well past the WCAG AA text threshold of 4.5:1. The
 * fourteen hues sweep 300 degrees from teal through blue, violet, magenta, rose, orange and gold, so
 * that tier 4 and tier 5 differ in hue as well as in brightness and two adjacent tiers are never the
 * same colour with the lightness dialled up one notch. Tier 13 is the brightest and warmest of all
 * fourteen on purpose - a season's most dedicated player gets the one crest that reads as "further
 * than everyone else" rather than a fourteenth stop on an otherwise flat ramp.
 */
public final class PrestigeColours {

    private static final List<TextColor> DEFAULT_TIERS = List.of(
            required("#5fbfae"), // 1 - teal: the colour of a crest nobody has worn for long
            required("#5ea9d6"), // 2 - sky blue
            required("#6f93e0"), // 3 - cornflower
            required("#8f83e6"), // 4 - periwinkle
            required("#a878e0"), // 5 - violet
            required("#c96fd6"), // 6 - orchid
            required("#dd6fae"), // 7 - rose
            required("#e07d78"), // 8 - coral
            required("#e2984f"), // 9 - orange
            required("#dbb043"), // 10 - gold
            required("#e8d35a"), // 11 - bright gold
            required("#f0dc70"), // 12 - radiant gold
            required("#fff6d8")  // 13 - legend: the brightest, warmest colour of the fourteen
    );

    /**
     * Vanilla's own {@code NamedTextColor.RED} ({@code #ff5555}), spelled out for the same reason
     * {@link eu.nordtal.s2.common.message.ToneColours}'s own {@code MUTED} default is: a configured
     * colour has to be a hex string. No prestige tier is within 60 units of it in plain RGB distance,
     * so an admin's name never reads as "maybe a high tier" by accident.
     */
    private static final TextColor DEFAULT_ADMIN = required("#ff5555");

    public static final PrestigeColours DEFAULTS = new PrestigeColours(DEFAULT_TIERS, DEFAULT_ADMIN);

    private final List<TextColor> tiers;
    private final TextColor admin;

    private PrestigeColours(final List<TextColor> tiers, final TextColor admin) {
        this.tiers = tiers;
        this.admin = admin;
    }

    /**
     * Parses the {@code colours} block of {@code prestige.yml}.
     *
     * @param declaredTiers exactly {@link Prestige#TIER_COUNT} hex strings, tier 1 first - the shape
     *                       {@code PrestigeSpec.TierColoursSpec} always hands back, never a partial map
     * @param declaredAdmin the admin colour's hex string
     * @param problems       where a value that had to be replaced by its default is reported, once
     *                       each. A plugin passes {@code getLogger()::warning}
     * @throws IllegalArgumentException if {@code declaredTiers} is not exactly
     *                                   {@link Prestige#TIER_COUNT} entries - a structural bug in the
     *                                   caller, never a bad value a player or operator typed
     */
    public static PrestigeColours parse(final List<String> declaredTiers, final String declaredAdmin,
                                        final Consumer<String> problems) {
        Objects.requireNonNull(declaredTiers, "declaredTiers");
        Objects.requireNonNull(problems, "problems");
        if (declaredTiers.size() != Prestige.TIER_COUNT) {
            throw new IllegalArgumentException("there are exactly " + Prestige.TIER_COUNT
                    + " prestige tiers, so there must be exactly that many declared colours; got "
                    + declaredTiers.size());
        }

        final List<TextColor> parsedTiers = new ArrayList<>(Prestige.TIER_COUNT);
        for (int index = 0; index < Prestige.TIER_COUNT; index++) {
            parsedTiers.add(parseOne("tier " + (index + 1), declaredTiers.get(index),
                    DEFAULT_TIERS.get(index), problems));
        }
        final TextColor parsedAdmin = parseOne("admin", declaredAdmin, DEFAULT_ADMIN, problems);
        return new PrestigeColours(List.copyOf(parsedTiers), parsedAdmin);
    }

    /** @param tier between {@link Prestige#MINIMUM_TIER} and {@link Prestige#TIER_COUNT} */
    public TextColor tier(final int tier) {
        if (tier < Prestige.MINIMUM_TIER || tier > Prestige.TIER_COUNT) {
            throw new IllegalArgumentException("tier must be between " + Prestige.MINIMUM_TIER
                    + " and " + Prestige.TIER_COUNT + ", was " + tier);
        }
        return tiers.get(tier - 1);
    }

    /** @return the colour that wins over every tier - never a fourteenth entry in {@link #tier} */
    public TextColor admin() {
        return admin;
    }

    private static TextColor parseOne(final String label, final String hex, final TextColor fallback,
                                      final Consumer<String> problems) {
        if (hex == null || hex.isBlank()) {
            return fallback;
        }
        final TextColor colour = TextColor.fromHexString(hex.trim());
        if (colour == null) {
            problems.accept("the prestige colour for " + label + " is '" + hex + "', which is not a"
                    + " hex colour (it has to look like #5fbfae); using the default "
                    + fallback.asHexString());
            return fallback;
        }
        return colour;
    }

    private static TextColor required(final String hex) {
        final TextColor colour = TextColor.fromHexString(hex);
        if (colour == null) {
            throw new IllegalStateException("built-in default '" + hex + "' is not a hex colour");
        }
        return colour;
    }
}
