package eu.nordtal.s2.discordbot.config;

import eu.nordtal.s2.common.message.Locales;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The language list from {@code access.yml}, and every rule that reads it.
 *
 * <h2>Why this exists</h2>
 * Until 2026-08-31 the same Discord ids lived twice in {@code access.yml}: once in this list, and
 * once in the fixed {@code roles.german} / {@code roles.english} pair and the four fixed
 * {@code channels.contribution-*} / {@code channels.link-*} keys, which were what the code actually
 * read. That made a third language a code change, which is exactly what {@code docs/i18n.md} says
 * it must never be. The fixed keys are gone; this class is what replaced them, and it is the only
 * place in the bot that turns a language tag, a role id or a locale into anything.
 *
 * <h2>What it guarantees</h2>
 * The list is non-empty, its tags are unique and lower case, {@code en} is present and every id is
 * a snowflake - all validated by {@link Configs} before the bot touches a guild, so nothing here
 * has to cope with a broken list. The same shape is asserted in {@link #of(List)} so a test cannot
 * build one this class's callers could not survive.
 *
 * <h2>Order</h2>
 * The configured order is preserved end to end: it is the order the managed messages are published
 * in, the order the bundles are loaded in, and the tie-break when a member holds more than one
 * language role. {@link DefaultLanguages} writes {@code en} first for that reason.
 *
 * @see AccessSpec#languages()
 */
public final class Languages {

    /** The one tag that has to be configured; see {@code docs/i18n.md}. */
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
        this.fallback = index.get(FALLBACK_TAG);
    }

    /**
     * Reads the language list out of the configuration.
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
                        language.linkChannel()))
                .toList());
    }

    /**
     * The same list without a config file behind it. This is what the tests use, and it is the only
     * reason anything here is expressed in terms of {@link Language} rather than of the spec
     * interface.
     *
     * @param languages the languages, in the order they should be used
     * @return the languages
     * @throws IllegalArgumentException if the list is empty, has a duplicate tag, or has no
     *                                  {@code en} entry - the three things {@link Configs} refuses
     *                                  to start on
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
            throw new IllegalArgumentException("'" + FALLBACK_TAG + "' is the fallback and must be "
                    + "present: " + copy.stream().map(Language::tag).toList());
        }
        return new Languages(copy);
    }

    /** @return every configured language, in the order {@code access.yml} lists them */
    public List<Language> all() {
        return ordered;
    }

    /** @return the {@code en} entry, which is guaranteed to exist */
    public Language fallback() {
        return fallback;
    }

    /**
     * @return the locales to load message bundles for. A language with no bundle of its own is not
     *         an error - {@code Messages} logs it once and that language reads English, which is
     *         what makes an incomplete translation safe to ship.
     */
    public Locale[] locales() {
        return ordered.stream().map(Language::locale).toArray(Locale[]::new);
    }

    /**
     * @param tag a language tag
     * @return the entry with that tag, if it is configured
     */
    public Optional<Language> byTag(final String tag) {
        return Optional.ofNullable(tag == null ? null : byTag.get(tag.toLowerCase(Locale.ROOT)));
    }

    /**
     * The configured language a locale belongs to.
     *
     * @param locale a locale, typically read out of {@code discord_user.locale}
     * @return the matching entry, or the {@code en} entry when that language is not configured -
     *         the same degradation the message bundles do, so a stored tag from a language that has
     *         since been removed from the file still resolves to a real channel
     */
    public Language forLocale(final Locale locale) {
        return byTag(Locales.tag(locale)).orElse(fallback);
    }

    /**
     * @param roleId a Discord role id
     * @return whether it is one of the configured language roles - the test that decides whether a
     *         role change is worth re-reading a member's language for
     */
    public boolean isLanguageRole(final String roleId) {
        return ordered.stream().anyMatch(language -> language.roleId().equals(roleId));
    }

    /**
     * Which language a member holding these roles should be recorded as speaking.
     *
     * <h2>The rule</h2>
     * A member holding exactly one language role has that language. A member holding none has no
     * answer at all - {@link Optional#empty()}, which the caller turns into "leave whatever is
     * stored", because the column already defaults to English and overwriting a real choice because
     * onboarding is mid-flight is worse than being a little stale.
     *
     * <h2>More than one role</h2>
     * <b>The fallback language loses to any other.</b> Somebody holding {@code de} and {@code en} is
     * recorded as {@code de}: they picked a language and then also picked the thing everything
     * already degrades to, so the specific choice is the informative one. This is the generalisation
     * of the rule this replaced, which took German over English for the same reason and could not
     * express anything else because there were only ever two roles.
     * <p>
     * Between two <b>non-fallback</b> languages - {@code de} and {@code fr} both held - the
     * configured order wins, first entry in {@code access.yml}. That case is not settled by
     * {@code docs/i18n.md}: it says {@code en} is the fallback and nothing about ranking two real
     * choices against each other. Configured order is deterministic and is itself a config edit,
     * which is the least surprising answer available, but it is a choice made here rather than one
     * the documentation made.
     * </p>
     *
     * @param heldRoleIds the role ids the member currently holds
     * @return the language to record, or empty when the member holds no language role
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
                // The first real choice in configured order, and nothing after it can beat it.
                return Optional.of(language);
            }
            fallbackHeld = language;
        }
        // Only the fallback role was held, or none was.
        return Optional.ofNullable(fallbackHeld);
    }

    /**
     * One configured language.
     *
     * @param tag                   the language tag, lower case; the bundle file name and the value
     *                              stored in {@code discord_user.locale}
     * @param roleId                the onboarding role that chooses it - read-only for the bot
     * @param contributionChannelId where the buy-access message and the donation thank-yous go
     * @param linkChannelId         where the account-link message goes
     */
    public record Language(String tag, String roleId, String contributionChannelId, String linkChannelId) {

        /** @return the tag as a {@link Locale}, for the message bundles and {@code discord_user.locale} */
        public Locale locale() {
            return Locales.parse(tag);
        }

        /**
         * @return the {@code managed_message.kind} of the buy-access message in this language, e.g.
         *         {@code CONTRIBUTION_EN}. The primary key of a row the bot has already written, so
         *         it is derived from the tag and never renamed.
         */
        public String contributionKind() {
            return "CONTRIBUTION_" + tag.toUpperCase(Locale.ROOT);
        }

        /** @return the {@code managed_message.kind} of the account-link message, e.g. {@code LINK_EN} */
        public String linkKind() {
            return "LINK_" + tag.toUpperCase(Locale.ROOT);
        }
    }
}
