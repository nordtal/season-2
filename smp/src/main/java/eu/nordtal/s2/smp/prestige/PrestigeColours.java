package eu.nordtal.s2.smp.prestige;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.format.TextColor;

/**
 * The fourteen colours a player's name is drawn in: one per {@link Prestige} tier, and the admin colour over them all.
 *
 * Each default reads at 6:1 or better against black, and the hues sweep from teal to gold so tiers differ in hue.
 */
public final class PrestigeColours {

    private static final List<TextColor> DEFAULT_TIERS = List.of(
            required("#5fbfae"), // 1 teal: a crest nobody has worn for long
            required("#5ea9d6"), // 2 sky blue
            required("#6f93e0"), // 3 cornflower
            required("#8f83e6"), // 4 periwinkle
            required("#a878e0"), // 5 violet
            required("#c96fd6"), // 6 orchid
            required("#dd6fae"), // 7 rose
            required("#e07d78"), // 8 coral
            required("#e2984f"), // 9 orange
            required("#dbb043"), // 10 gold
            required("#e8d35a"), // 11 bright gold
            required("#f0dc70"), // 12 radiant gold
            required("#fff6d8") // 13 legend: the brightest and warmest of the fourteen
            );

    /** Vanilla's {@code NamedTextColor.RED} as a hex string, at least 60 RGB units from every tier's colour. */
    private static final TextColor DEFAULT_ADMIN = required("#ff5555");

    public static final PrestigeColours DEFAULTS = new PrestigeColours(DEFAULT_TIERS, DEFAULT_ADMIN);

    private final List<TextColor> tiers;
    private final TextColor admin;

    private PrestigeColours(final List<TextColor> tiers, final TextColor admin) {
        this.tiers = tiers;
        this.admin = admin;
    }

    /**
     * Parses the {@code colours} block of the {@code prestige} group, replacing a bad value with its default.
     *
     * @param declaredTiers exactly {@link Prestige#TIER_COUNT} hex strings, tier 1 first
     * @param declaredAdmin the admin colour's hex string
     * @param problems       where each replaced value is reported once
     * @throws IllegalArgumentException if {@code declaredTiers} does not have exactly {@link Prestige#TIER_COUNT}
     *     entries
     */
    public static PrestigeColours parse(
            final List<String> declaredTiers, final String declaredAdmin, final Consumer<String> problems) {
        Objects.requireNonNull(declaredTiers, "declaredTiers");
        Objects.requireNonNull(problems, "problems");
        if (declaredTiers.size() != Prestige.TIER_COUNT) {
            throw new IllegalArgumentException("there are exactly " + Prestige.TIER_COUNT
                    + " prestige tiers, so there must be exactly that many declared colours; got "
                    + declaredTiers.size());
        }

        final List<TextColor> parsedTiers = new ArrayList<>(Prestige.TIER_COUNT);
        for (int index = 0; index < Prestige.TIER_COUNT; index++) {
            parsedTiers.add(
                    parseOne("tier " + (index + 1), declaredTiers.get(index), DEFAULT_TIERS.get(index), problems));
        }
        final TextColor parsedAdmin = parseOne("admin", declaredAdmin, DEFAULT_ADMIN, problems);
        return new PrestigeColours(List.copyOf(parsedTiers), parsedAdmin);
    }

    /**
     * Returns a tier's colour.
     *
     * @param tier between {@link Prestige#MINIMUM_TIER} and {@link Prestige#TIER_COUNT}
     */
    public TextColor tier(final int tier) {
        if (tier < Prestige.MINIMUM_TIER || tier > Prestige.TIER_COUNT) {
            throw new IllegalArgumentException(
                    "tier must be between " + Prestige.MINIMUM_TIER + " and " + Prestige.TIER_COUNT + ", was " + tier);
        }
        return tiers.get(tier - 1);
    }

    /** Returns the colour that wins over every tier. */
    public TextColor admin() {
        return admin;
    }

    private static TextColor parseOne(
            final String label, final String hex, final TextColor fallback, final Consumer<String> problems) {
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
