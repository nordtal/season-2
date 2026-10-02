package eu.nordtal.s2.settings.network;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.s2.common.language.Languages;
import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.messages.context.SeasonContext;
import eu.nordtal.s2.settings.Checks;
import eu.nordtal.s2.settings.Group;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * The players and the MOTD are taken while a process runs; the season and the languages at its next start.
 */
public final class NetworkSettings {

    /** How many players the network takes and what they may type. */
    public static final Group<PlayersSpec> PLAYERS = Group.of("players", PlayersSpec.class)
            .checkedBy(NetworkSettings::checkPlayers)
            .whileRunning()
            .networkWide();

    /** What the server browser shows in each season phase. */
    public static final Group<MotdSpec> MOTD = Group.of("motd", MotdSpec.class)
            .checkedBy(NetworkSettings::checkMotd)
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

    private NetworkSettings() {}

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
     * Refuses an empty MOTD, which the server browser would show as an empty entry.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    public static void checkMotd(final MotdSpec spec) {
        Checks.requireText("pre-launch", spec.preLaunch());
        Checks.requireText("pre-event", spec.preEvent());
        Checks.requireText("start-event", spec.startEvent());
        Checks.requireText("smp", spec.smp());
        Checks.requireText("maintenance", spec.maintenance());
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
