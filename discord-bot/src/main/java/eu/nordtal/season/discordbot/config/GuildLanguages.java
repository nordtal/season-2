package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.common.language.Locales;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The Discord side of the network's languages: each one's role name and channels, keyed by its tag.
 *
 * One entry for each network language, in its order with the default first; an empty id is unset.
 */
public final class GuildLanguages {

    private final List<Language> ordered;
    private final Map<String, Language> byTag;
    private final Language fallback;

    private GuildLanguages(final List<Language> ordered) {
        this.ordered = ordered;
        final Map<String, Language> index = new LinkedHashMap<>();
        for (final Language language : ordered) {
            index.put(language.tag(), language);
        }
        this.byTag = Map.copyOf(index);
        this.fallback = Objects.requireNonNull(index.get(Locales.DEFAULT_TAG), "fallback language");
    }

    /**
     * Reads the Discord data of each network language from the configuration.
     *
     * @param config the loaded and validated access configuration
     * @param network the languages the network speaks
     * @return the languages, in the network's order
     * @throws IllegalArgumentException if the entries and the network's tags are not the same set
     */
    public static GuildLanguages of(final AccessSpec config, final Languages network) {
        return of(
                config.languages().stream()
                        .map(language -> new Language(
                                language.tag(),
                                roleNameOf(language),
                                language.contributionChannel(),
                                language.linkChannel(),
                                language.hungerGamesChannel(),
                                language.statusChannel(),
                                language.announcementChannel()))
                        .toList(),
                network);
    }

    /**
     * Builds the list without a config file.
     *
     * @param languages one entry per network language, in any order
     * @param network the languages the network speaks
     * @return the languages, in the network's order
     * @throws IllegalArgumentException if the entries and the network's tags are not the same set
     */
    public static GuildLanguages of(final List<Language> languages, final Languages network) {
        final List<String> tags = languages.stream().map(Language::tag).toList();
        requireTagsOf(network, tags);
        final Map<String, Language> entries = new LinkedHashMap<>();
        languages.forEach(language -> entries.put(language.tag(), language));
        return new GuildLanguages(network.tags().stream().map(entries::get).toList());
    }

    /**
     * Refuses entry tags that are not exactly the network's languages.
     *
     * @param network the languages the network speaks
     * @param tags the tags of the {@code access} group's language entries
     * @throws IllegalArgumentException naming the first tag listed twice, missing or not spoken
     */
    public static void requireTagsOf(final Languages network, final List<String> tags) {
        final Set<String> seen = new HashSet<>();
        for (final String tag : tags) {
            if (!seen.add(tag)) {
                throw new IllegalArgumentException(
                        "languages has two entries for '" + tag + "'. Tags identify a language and must be unique.");
            }
            if (!network.tags().contains(tag)) {
                throw new IllegalArgumentException(
                        "languages has an entry for '" + tag
                                + "', which the network does not speak. Add it to the network's languages or remove the entry.");
            }
        }
        for (final String tag : network.tags()) {
            if (!seen.contains(tag)) {
                throw new IllegalArgumentException(
                        "languages has no entry for '" + tag
                                + "', which the network speaks. Every network language needs the role and channels of its entry.");
            }
        }
    }

    /** Returns every language, in the network's order. */
    public List<Language> all() {
        return ordered;
    }

    /** Returns the default language's entry, which always exists. */
    public Language fallback() {
        return fallback;
    }

    /** Returns the locales to load message bundles for; one without a bundle reads the default's. */
    public Locale[] locales() {
        return ordered.stream().map(Language::locale).toArray(Locale[]::new);
    }

    /**
     * Returns the entry with that tag, if it is configured.
     *
     * @param tag a language tag
     */
    public Optional<Language> byTag(final String tag) {
        return Optional.ofNullable(tag == null ? null : byTag.get(tag.toLowerCase(Locale.ROOT)));
    }

    /**
     * Returns the configured language of a locale, or the default's when that language is not configured.
     *
     * @param locale a locale, typically read out of {@code discord_user.locale}
     */
    public Language forLocale(final Locale locale) {
        return byTag(Locales.tag(locale)).orElse(fallback);
    }

    /** Returns the name of a language's role: the one configured, or else the language's own name in itself. */
    static String roleNameOf(final AccessSpec.LanguageSpec language) {
        final String configured =
                language.roleName() == null ? "" : language.roleName().strip();
        if (!configured.isEmpty()) {
            return configured;
        }
        return ownName(Locales.parse(language.tag() == null ? "" : language.tag()));
    }

    /** Returns a language's own name in itself, capitalised, such as Deutsch, or its tag when the JDK has none. */
    private static String ownName(final Locale locale) {
        final String own = locale.getDisplayLanguage(locale);
        return own.isEmpty() ? Locales.tag(locale) : own.substring(0, 1).toUpperCase(locale) + own.substring(1);
    }

    /**
     * One configured language; every channel id may be empty.
     *
     * @param tag the language tag, lower case; the bundle file name and the {@code discord_user.locale} value
     * @param roleName the name of the role that chooses it, by which the bot first finds or creates it
     * @param contributionChannelId where the buy-access message and the donation thank-yous go
     * @param linkChannelId where the account-link message goes
     * @param hungerGamesChannelId where the hunger games Register message goes
     * @param announcementChannelId where milestones and phase changes are posted, or {@code ""} for none
     * @param statusChannelId the channel whose name carries the status, or {@code ""} for none
     */
    public record Language(
            String tag,
            String roleName,
            String contributionChannelId,
            String linkChannelId,
            String hungerGamesChannelId,
            String statusChannelId,
            String announcementChannelId) {

        /** The six-id form, with no announcement channel. */
        public Language(
                final String tag,
                final String roleName,
                final String contributionChannelId,
                final String linkChannelId,
                final String hungerGamesChannelId,
                final String statusChannelId) {
            this(tag, roleName, contributionChannelId, linkChannelId, hungerGamesChannelId, statusChannelId, "");
        }

        /** Returns whether this language has a status channel to rename. */
        public boolean hasStatusChannel() {
            return statusChannelId != null && !statusChannelId.isBlank();
        }

        /** Returns whether announcements for this language have a channel. */
        public boolean hasAnnouncementChannel() {
            return announcementChannelId != null && !announcementChannelId.isBlank();
        }

        /** Returns the language's own name in itself, such as Deutsch, whatever its role is called. */
        public String ownName() {
            return GuildLanguages.ownName(locale());
        }

        /** Returns the tag as a {@link Locale}. */
        public Locale locale() {
            return Locales.parse(tag);
        }

        /** Returns the {@code managed_message.kind} of the buy-access message, such as {@code CONTRIBUTION_EN}. */
        public String contributionKind() {
            return "CONTRIBUTION_" + tag.toUpperCase(Locale.ROOT);
        }

        /** Returns the {@code managed_message.kind} of the account-link message, such as {@code LINK_EN}. */
        public String linkKind() {
            return "LINK_" + tag.toUpperCase(Locale.ROOT);
        }

        /** Returns the {@code managed_message.kind} of the Register message, such as {@code HG_REGISTER_EN}. */
        public String hungerGamesRegisterKind() {
            return "HG_REGISTER_" + tag.toUpperCase(Locale.ROOT);
        }
    }
}
