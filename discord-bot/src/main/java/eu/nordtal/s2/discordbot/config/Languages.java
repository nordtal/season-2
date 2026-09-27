package eu.nordtal.s2.discordbot.config;

import eu.nordtal.s2.common.message.Locales;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The language list from {@code access.yml}, and every rule that reads it.
 *
 * The configured order is kept end to end; an id may be empty, so callers check {@link Configured#isSet(String)}.
 */
public final class Languages {

    /** The one tag that has to be configured. */
    public static final String FALLBACK_TAG = "en";

    private final List<Language> ordered;
    private final Map<String, Language> byTag;
    private final Language fallback;

    private Languages(final List<Language> ordered) {
        this.ordered = ordered;
        final Map<String, Language> index = new LinkedHashMap<>();
        for (final Language language : ordered) {
            index.put(language.tag(), language);
        }
        this.byTag = Map.copyOf(index);
        this.fallback = Objects.requireNonNull(index.get(FALLBACK_TAG), "fallback language");
    }

    /**
     * Reads the language list from the configuration.
     *
     * @param config the loaded and validated access configuration
     * @return the languages, in the order the file lists them
     */
    public static Languages of(final AccessSpec config) {
        return of(config.languages().stream()
                .map(language -> new Language(
                        language.tag(),
                        language.role(),
                        language.contributionChannel(),
                        language.linkChannel(),
                        language.hungerGamesChannel(),
                        language.statusChannel(),
                        language.announcementChannel()))
                .toList());
    }

    /**
     * Builds the list without a config file.
     *
     * @param languages the languages, in the order they should be used
     * @return the languages
     * @throws IllegalArgumentException if the list is empty, has a duplicate tag, or has no {@code en} entry
     */
    public static Languages of(final List<Language> languages) {
        if (languages == null || languages.isEmpty()) {
            throw new IllegalArgumentException("there has to be at least one language");
        }
        final List<Language> copy = List.copyOf(languages);
        if (copy.stream().map(Language::tag).distinct().count() != copy.size()) {
            throw new IllegalArgumentException("language tags must be unique: "
                    + copy.stream().map(Language::tag).toList());
        }
        if (copy.stream().noneMatch(language -> FALLBACK_TAG.equals(language.tag()))) {
            throw new IllegalArgumentException("'" + FALLBACK_TAG + "' is the fallback and must be " + "present: "
                    + copy.stream().map(Language::tag).toList());
        }
        return new Languages(copy);
    }

    /** Returns every configured language, in the order {@code access.yml} lists them. */
    public List<Language> all() {
        return ordered;
    }

    /** Returns the {@code en} entry, which always exists. */
    public Language fallback() {
        return fallback;
    }

    /** Returns the locales to load message bundles for; one without a bundle reads English. */
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
     * Returns the configured language of a locale, or {@code en} when that language is not configured.
     *
     * @param locale a locale, typically read out of {@code discord_user.locale}
     */
    public Language forLocale(final Locale locale) {
        return byTag(Locales.tag(locale)).orElse(fallback);
    }

    /**
     * Returns whether a role id is one of the configured language roles.
     *
     * @param roleId a Discord role id
     */
    public boolean isLanguageRole(final String roleId) {
        return ordered.stream().anyMatch(language -> language.roleId().equals(roleId));
    }

    /**
     * Returns the language a member holding these roles speaks, or empty when they hold no language role.
     *
     * The fallback loses to any other language; between two others, the configured order wins.
     */
    public Optional<Language> resolve(final Collection<String> heldRoleIds) {
        if (heldRoleIds == null || heldRoleIds.isEmpty()) {
            return Optional.empty();
        }

        Language fallbackHeld = null;
        for (final Language language : ordered) {
            if (!heldRoleIds.contains(language.roleId())) {
                continue;
            }
            if (!FALLBACK_TAG.equals(language.tag())) {
                // The first real choice in configured order wins.
                return Optional.of(language);
            }
            fallbackHeld = language;
        }
        return Optional.ofNullable(fallbackHeld);
    }

    /**
     * One configured language; every id except the tag may be empty.
     *
     * @param tag the language tag, lower case; the bundle file name and the {@code discord_user.locale} value
     * @param roleId the onboarding role that chooses it, read only by the bot
     * @param contributionChannelId where the buy-access message and the donation thank-yous go
     * @param linkChannelId where the account-link message goes
     * @param hungerGamesChannelId where the hunger games Register message goes
     * @param announcementChannelId where milestones and phase changes are posted, or {@code ""} for none
     * @param statusChannelId the channel whose name carries the status, or {@code ""} for none
     */
    public record Language(
            String tag,
            String roleId,
            String contributionChannelId,
            String linkChannelId,
            String hungerGamesChannelId,
            String statusChannelId,
            String announcementChannelId) {

        /** The six-id form, with no announcement channel. */
        public Language(
                final String tag,
                final String roleId,
                final String contributionChannelId,
                final String linkChannelId,
                final String hungerGamesChannelId,
                final String statusChannelId) {
            this(tag, roleId, contributionChannelId, linkChannelId, hungerGamesChannelId, statusChannelId, "");
        }

        /** Returns whether this language has a status channel to rename. */
        public boolean hasStatusChannel() {
            return statusChannelId != null && !statusChannelId.isBlank();
        }

        /** Returns whether announcements for this language have a channel. */
        public boolean hasAnnouncementChannel() {
            return announcementChannelId != null && !announcementChannelId.isBlank();
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
