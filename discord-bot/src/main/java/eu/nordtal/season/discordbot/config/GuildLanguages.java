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
 * The role name and channels of each network language, keyed by its tag.
 *
 * An empty id means the channel is not set.
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
     * @throws IllegalArgumentException if the entry tags are not exactly the network's tags
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
     * Builds the languages from entries, one per network language in any order. The result has the network's order.
     *
     * @throws IllegalArgumentException if the entry tags are not exactly the network's tags
     */
    public static GuildLanguages of(final List<Language> languages, final Languages network) {
        final List<String> tags = languages.stream().map(Language::tag).toList();
        requireTagsOf(network, tags);
        final Map<String, Language> entries = new LinkedHashMap<>();
        languages.forEach(language -> entries.put(language.tag(), language));
        return new GuildLanguages(network.tags().stream().map(entries::get).toList());
    }

    /**
     * Requires the tags to be exactly the network's languages.
     *
     * @throws IllegalArgumentException naming the first tag that is listed twice, missing or not spoken
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

    public List<Language> all() {
        return ordered;
    }

    public Language fallback() {
        return fallback;
    }

    public Locale[] locales() {
        return ordered.stream().map(Language::locale).toArray(Locale[]::new);
    }

    public Optional<Language> byTag(final String tag) {
        return Optional.ofNullable(tag == null ? null : byTag.get(tag.toLowerCase(Locale.ROOT)));
    }

    /** Returns the language of a locale, or the default language when none is configured for it. */
    public Language forLocale(final Locale locale) {
        return byTag(Locales.tag(locale)).orElse(fallback);
    }

    /** Returns the configured role name, or the language's own name when none is configured. */
    static String roleNameOf(final AccessSpec.LanguageSpec language) {
        final String configured =
                language.roleName() == null ? "" : language.roleName().strip();
        if (!configured.isEmpty()) {
            return configured;
        }
        return Locales.ownName(Locales.parse(language.tag() == null ? "" : language.tag()));
    }

    /**
     * One configured language. Every channel id may be empty.
     *
     * @param tag the lower-case language tag, which is the bundle file name and the {@code discord_user.locale} value
     * @param roleName the name of the role the bot finds or creates for the language
     * @param contributionChannelId the channel of the buy-access message and the donation thank-yous
     * @param linkChannelId the channel of the account-link message
     * @param hungerGamesChannelId the channel of the hunger games Register message
     * @param announcementChannelId the channel of milestones and phase changes
     * @param statusChannelId the channel whose name shows the status
     */
    public record Language(
            String tag,
            String roleName,
            String contributionChannelId,
            String linkChannelId,
            String hungerGamesChannelId,
            String statusChannelId,
            String announcementChannelId) {

        public Language(
                final String tag,
                final String roleName,
                final String contributionChannelId,
                final String linkChannelId,
                final String hungerGamesChannelId,
                final String statusChannelId) {
            this(tag, roleName, contributionChannelId, linkChannelId, hungerGamesChannelId, statusChannelId, "");
        }

        public boolean hasStatusChannel() {
            return statusChannelId != null && !statusChannelId.isBlank();
        }

        public boolean hasAnnouncementChannel() {
            return announcementChannelId != null && !announcementChannelId.isBlank();
        }

        /** Returns the name of the language in itself, such as Deutsch, whatever the role is called. */
        public String ownName() {
            return Locales.ownName(locale());
        }

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
