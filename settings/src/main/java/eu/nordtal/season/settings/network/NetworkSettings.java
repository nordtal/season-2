package eu.nordtal.season.settings.network;

import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.access.Prestige;
import eu.nordtal.season.database.payment.Tier;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.messages.Palette;
import eu.nordtal.season.messages.context.MessageEnvironment;
import eu.nordtal.season.messages.context.SeasonContext;
import eu.nordtal.season.settings.Checks;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.spec.Specs;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The groups every process shares, which an admin changes once for the whole network, and what a valid one is.
 *
 * The players and the prestige are taken while a process runs; the season and the languages at its next start.
 */
public final class NetworkSettings {

    /** How many players the network takes and what they may type. */
    public static final Group<PlayersSpec> PLAYERS = Group.of("players", PlayersSpec.class)
            .checkedBy(NetworkSettings::checkPlayers)
            .whileRunning()
            .networkWide();

    /** The season this installation runs. */
    public static final Group<SeasonSpec> SEASON = Group.of("season", SeasonSpec.class)
            .checkedBy(NetworkSettings::checkSeason)
            .networkWide();

    /** The languages the network speaks and the zone it tells time in. */
    public static final Group<LanguageAndTimeSpec> LANGUAGE_AND_TIME = Group.of(
                    "language-and-time", LanguageAndTimeSpec.class)
            .checkedBy(NetworkSettings::checkLanguageAndTime)
            .networkWide();

    /** When each crest is reached and the colour a name is drawn in; every name's card shows the crest. */
    public static final Group<PrestigeSpec> PRESTIGE = Group.of("prestige", PrestigeSpec.class)
            .checkedBy(NetworkSettings::checkPrestige)
            .whileRunning()
            .networkWide();

    /** What access costs, which the bot offers and steward books by. */
    public static final Group<PricesSpec> PRICES = Group.of("prices", PricesSpec.class)
            .checkedBy(NetworkSettings::checkPrices)
            .networkWide();

    private NetworkSettings() {}

    /** Returns the price list and the rule that turns arrived money into a grant. */
    public static Tiers tiers(final PricesSpec spec) {
        return Tiers.of(
                spec.tiers().stream()
                        .map(tier -> new Tier(tier.days(), tier.priceCents()))
                        .toList(),
                spec.donationCents());
    }

    /** Returns the crest table {@code spec} declares, which {@link #checkPrestige} has already let through. */
    public static Prestige prestige(final PrestigeSpec spec) {
        return new Prestige(prestigeHours(spec));
    }

    /**
     * Refuses bad crest hours, since {@link Prestige}'s constructor is the whole rule for them.
     *
     * @throws IllegalArgumentException naming what is wrong with the hours
     */
    public static void checkPrestige(final PrestigeSpec spec) {
        final Prestige _ = prestige(spec);
    }

    /** The thirteen tier colours, in tier order, as a server that paints names in them parses them. */
    public static List<String> prestigeColours(final PrestigeSpec spec) {
        final PrestigeSpec.TierColoursSpec tiers = spec.colours();
        return List.of(
                tiers.tier01(),
                tiers.tier02(),
                tiers.tier03(),
                tiers.tier04(),
                tiers.tier05(),
                tiers.tier06(),
                tiers.tier07(),
                tiers.tier08(),
                tiers.tier09(),
                tiers.tier10(),
                tiers.tier11(),
                tiers.tier12(),
                tiers.tier13());
    }

    /** The thirteen tier hours, in the same order, as {@link Prestige} takes them. */
    public static List<Integer> prestigeHours(final PrestigeSpec spec) {
        final PrestigeSpec.TierHoursSpec tiers = spec.hours();
        return List.of(
                tiers.tier01(),
                tiers.tier02(),
                tiers.tier03(),
                tiers.tier04(),
                tiers.tier05(),
                tiers.tier06(),
                tiers.tier07(),
                tiers.tier08(),
                tiers.tier09(),
                tiers.tier10(),
                tiers.tier11(),
                tiers.tier12(),
                tiers.tier13());
    }

    /**
     * Refuses a price list the purchase flow cannot offer.
     * A tier is identified by its days, so they are unique, and more days cost more.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    public static void checkPrices(final PricesSpec spec) {
        Checks.requirePositive("donation-cents", spec.donationCents());
        final List<PricesSpec.TierSpec> tiers = spec.tiers();
        final Set<Integer> days = new HashSet<>();
        for (int index = 0; index < tiers.size(); index++) {
            final PricesSpec.TierSpec tier = tiers.get(index);
            Checks.requirePositive("tiers[" + index + "].days", tier.days());
            Checks.requirePositive("tiers[" + index + "].price-cents", tier.priceCents());
            if (!days.add(tier.days())) {
                throw new IllegalArgumentException("tiers[" + index + "] offers " + tier.days()
                        + " days, which another tier already offers. Day counts identify a tier and must be unique.");
            }
        }
        final List<PricesSpec.TierSpec> byDays = tiers.stream()
                .sorted(Comparator.comparingInt(PricesSpec.TierSpec::days))
                .toList();
        for (int index = 1; index < byDays.size(); index++) {
            if (byDays.get(index).priceCents() <= byDays.get(index - 1).priceCents()) {
                throw new IllegalArgumentException("tiers must get more expensive as they get longer: "
                        + byDays.get(index).days() + " days costs "
                        + byDays.get(index).priceCents() + "c but "
                        + byDays.get(index - 1).days() + " days costs "
                        + byDays.get(index - 1).priceCents() + "c");
            }
        }
    }

    /** Returns the languages a process loads its bundles in, the default first. */
    public static Languages languages(final LanguageAndTimeSpec spec) {
        final List<String> tags = new ArrayList<>(List.of(spec.defaultLanguage()));
        spec.languages().stream()
                .filter(tag -> !tag.equals(spec.defaultLanguage()))
                .forEach(tags::add);
        return new Languages(tags);
    }

    /** Returns the languages of the spec's defaults, for a process whose settings could not be read. */
    public static Languages defaultLanguages() {
        return languages(Specs.createDefault(LanguageAndTimeSpec.class));
    }

    /** Returns the allowlist {@code players} holds now, parsed again only when a reload changed it. */
    public static Supplier<CommandAllowlist> allowlist(final PlayersSpec players) {
        final AtomicReference<Map.Entry<List<String>, CommandAllowlist>> parsed = new AtomicReference<>();
        return () -> {
            final List<String> entries = players.commandAllowlist();
            final Map.Entry<List<String>, CommandAllowlist> held = parsed.get();
            if (held != null && held.getKey().equals(entries)) {
                return held.getValue();
            }
            final CommandAllowlist current = CommandAllowlist.parse(entries);
            parsed.set(Map.entry(List.copyOf(entries), current));
            return current;
        };
    }

    /** Returns the season every message names as {@code {season.number}} and {@code {season.name}}. */
    public static SeasonContext season(final SeasonSpec spec) {
        return new SeasonContext(spec.number(), spec.name());
    }

    /**
     * Returns what every message of the service called {@code service} can name, and how it shows times and tones.
     * The globals are the service itself, the season and the network.
     */
    public static MessageEnvironment environment(
            final String service,
            final SeasonSpec season,
            final LanguageAndTimeSpec languageAndTime,
            final Palette palette) {
        return MessageEnvironment.of(service, season(season), zone(languageAndTime), palette);
    }

    /** Returns the zone a date is shown and typed in until a reader has one of their own. */
    public static ZoneId zone(final LanguageAndTimeSpec spec) {
        return ZoneId.of(spec.defaultTimeZone());
    }

    /**
     * Refuses a limit or an allowlist the network cannot run on; an empty allowlist is a choice, not a mistake.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    public static void checkPlayers(final PlayersSpec spec) {
        Checks.requirePositive("max-players", spec.maxPlayers());
        for (final String entry : spec.commandAllowlist()) {
            // A blank entry would be dropped silently: the form shows ten entries, the network acts on nine.
            if (entry == null || entry.isBlank()) {
                throw new IllegalArgumentException("command-allowlist has a blank entry, which allows nothing");
            }
            // CommandAllowlist#parse strips a leading slash and namespace, so "/" also normalises to empty.
            if (!CommandAllowlist.names(entry)) {
                throw new IllegalArgumentException("command-allowlist entry '" + entry
                        + "' is nothing once the slash and namespace are taken off; write a path like 'hg ready'");
            }
        }
    }

    /**
     * Refuses a season without a number or a name.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    public static void checkSeason(final SeasonSpec spec) {
        Checks.requirePositive("number", spec.number());
        Checks.requireText("name", spec.name());
    }

    /**
     * Refuses a language list or zone the network cannot speak or tell time in.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    public static void checkLanguageAndTime(final LanguageAndTimeSpec spec) {
        final String fallback = Locales.tag(Locales.DEFAULT);
        final Set<String> seen = new HashSet<>();
        for (final String tag : spec.languages()) {
            if (tag == null || tag.isBlank() || !tag.equals(tag.strip().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("languages holds '" + tag + "', which is not a lower case tag");
            }
            if (!seen.add(tag)) {
                throw new IllegalArgumentException("languages lists '" + tag + "' twice");
            }
        }
        if (!seen.contains(spec.defaultLanguage())) {
            throw new IllegalArgumentException(
                    "default-language '" + spec.defaultLanguage() + "' is not one of the languages " + seen);
        }
        if (!spec.defaultLanguage().equals(fallback)) {
            // Every bundle is complete only in English, and a reader without a language still falls back to it.
            throw new IllegalArgumentException("default-language must be " + fallback
                    + " until a reader's own language decides what they read, was '" + spec.defaultLanguage() + "'");
        }
        try {
            ZoneId.of(spec.defaultTimeZone());
        } catch (final DateTimeException unknown) {
            throw new IllegalArgumentException(
                    "default-time-zone '" + spec.defaultTimeZone()
                            + "' is not a zone; write an IANA name such as Europe/Berlin",
                    unknown);
        }
    }
}
