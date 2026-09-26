package eu.nordtal.s2.discordbot.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.ConfigValidator;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.config.EnvOverrideFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Where the bot's config files live, and every rule about what a valid value is.
 *
 * Each file gets its own environment namespace - {@code NORDTAL_DATABASE_*}, {@code NORDTAL_BOT_*},
 * {@code NORDTAL_ACCESS_*} - because one shared prefix would make generic keys such as {@code password} collide
 * across files.
 *
 * Every check here runs at startup and stops the process. A value that is present is never lenient: a stray
 * character in a snowflake must not become a {@code null} role deep inside a role assignment hours later, so
 * anything that is not digits is refused by name.
 *
 * An ABSENT value is a different question. Two ids decide whether the bot can work at all - the guild it lives in
 * and the admin role that says who may administer it - and those two are required. Every other id is a feature:
 * leave it empty and that feature is not served, which {@link Configured} says out loud once at startup.
 */
@Slf4j
public final class Configs {

    /**
     * Where the config files live. Mounted as a Docker volume; see the module Dockerfile.
     *
     * Overridable with {@code -Daccess.config.dir=...} so the tests can point it at a temporary directory. Nothing in
     * production sets it.
     */
    static final String DIRECTORY_PROPERTY = "access.config.dir";

    /**
     * The one language {@code access.yml} may not leave out.
     *
     * The same constant {@link AccessSpec#languages()} 's {@code @Protected} annotation carries, which is what
     * steward-worker's schema-reading side refuses to let an operator remove through the API. The two cannot drift
     * apart while both name this field, and {@code ConfigsTest} fails the build if the annotation is ever changed
     * to say something else.
     */
    private static final String FALLBACK_LANGUAGE = Languages.FALLBACK_TAG;

    /**
     * How long a language tag may be - a rule about the schema, not about languages.
     *
     * {@code managed_message.kind} is {@code varchar(32)} and holds {@code "CONTRIBUTION_" + TAG}.
     */
    private static final int MAX_TAG_LENGTH = 32 - "CONTRIBUTION_".length();

    /**
     * What to write when the language list is unusable, with a slot for why.
     *
     * The YAML is in the message because this is the moment somebody has a file that does not load and no example.
     */
    private static final String SHAPE_OF_LANGUAGES = """
            %s Write at least the fallback:

              languages:
              - tag: en
                role: '000000000000000000'
                contribution-channel: '000000000000000000'
                link-channel: '000000000000000000'
                hunger-games-channel: '000000000000000000'""";

    private Configs() {}

    /**
     * Where an operator's message overrides go: beside the YAML files, in the volume a deployment already mounts.
     *
     * @return the override directory, which {@code Messages.load} creates if it is not there
     */
    public static Path messagesDirectory() {
        return directory().resolve("messages");
    }

    private static Path directory() {
        return Path.of(System.getProperty(DIRECTORY_PROPERTY, "config"));
    }

    // The three configs.

    public static ConfigHandle<DatabaseSpec> database() throws ConfigException {
        return load("database", DatabaseSpec.class, "NORDTAL_DATABASE", config -> {
            requireText("jdbc-url", config.jdbcUrl());
            requireText("username", config.username());
            if (!config.jdbcUrl().startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException(
                        "jdbc-url must be a PostgreSQL URL (jdbc:postgresql://host:port/database)");
            }
            if (config.maximumPoolSize() < 1) {
                throw new IllegalArgumentException("maximum-pool-size must be at least 1");
            }
        });
    }

    /**
     * {@code bot.yml}, which holds one setting: the Discord token.
     *
     * The "both halves of bunq or neither" check lives in {@code steward-worker} 's {@code Configs.requireBunq}, the
     * process that has something to be half-configured.
     */
    public static ConfigHandle<BotSpec> bot() throws ConfigException {
        return load(
                "bot",
                BotSpec.class,
                "NORDTAL_BOT",
                config -> requireSecret("token", "NORDTAL_BOT_TOKEN", config.token()));
    }

    public static ConfigHandle<AccessSpec> access() throws ConfigException {
        return load("access", AccessSpec.class, "NORDTAL_ACCESS", Configs::validateAccess);
    }

    /**
     * Everything {@code access.yml} has to get right before the bot is allowed to touch a guild.
     *
     * Snowflakes are checked for being numeric, not merely non-empty: a stray character otherwise becomes a
     * {@code null} role deep inside a role assignment, hours later.
     */
    private static void validateAccess(final AccessSpec config) {
        // The two that are not features: no guild means nothing to act on, no admin role means nobody can administer.
        requireSnowflake("guild-id", config.guildId());
        requireSnowflake("roles.admin", config.roles().admin());

        // Everything below is optional; empty means the feature is not served. Configured names each one at startup.
        requireSnowflakeIfSet("roles.access", config.roles().access());
        requireSnowflakeIfSet("roles.donor", config.roles().donor());
        requireSnowflakeIfSet("roles.admin-ping", config.roles().adminPing());

        requireSnowflakeIfSet("channels.admin", config.channels().admin());

        // The per-language roles and channels live on the `languages` entries; validateLanguages names the wrong one.

        validateTiers(config.tiers());
        validateLanguages(config.languages());

        requirePositive("donation-cents", config.donationCents());
        requirePositive("expiry-reminder-lead-days", config.expiryReminderLeadDays());
        requirePositive("link-code-attempts-per-hour", config.linkCodeAttemptsPerHour());
        requirePositive("role-reconcile-interval-minutes", config.roleReconcileIntervalMinutes());
        requirePositive("payment.poll-interval-seconds", config.payment().pollIntervalSeconds());
        requirePositive("payment.request-ttl-hours", config.payment().requestTtlHours());

        // payment.watermark and payment.recent-payment-count are steward-worker's checks now; see StewardSpec.BunqSpec.
    }

    /**
     * The price list.
     *
     * The ordering is validated rather than sorted: a list where a longer period is cheaper is a mistake, and only the
     * person who made it knows which number is wrong.
     *
     * Empty is allowed. It used to stop the bot, on the reasoning that a price list with nothing in it means there is
     * nothing to buy - which is true, and is a perfectly ordinary state for a deployment that has not decided its
     * prices
     * yet. The contribution message then offers the donation and no tiers, and {@link Configured} says so at startup. A
     * list that has entries still has to make sense, which is everything below.
     */
    private static void validateTiers(final List<AccessSpec.TierSpec> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return;
        }

        final Set<Integer> days = new HashSet<>();
        for (int index = 0; index < tiers.size(); index++) {
            final AccessSpec.TierSpec tier = tiers.get(index);
            requirePositive("tiers[" + index + "].days", tier.days());
            requirePositive("tiers[" + index + "].price-cents", tier.priceCents());
            if (!days.add(tier.days())) {
                // A tier is identified by its day count, what a purchase button carries, so a duplicate is ambiguous.
                throw new IllegalArgumentException(
                        "tiers[" + index + "] offers " + tier.days() + " days, which another tier "
                                + "already offers. Day counts identify a tier and must be unique.");
            }
        }

        final List<AccessSpec.TierSpec> byDays = tiers.stream()
                .sorted(Comparator.comparingInt(AccessSpec.TierSpec::days))
                .toList();
        for (int index = 1; index < byDays.size(); index++) {
            if (byDays.get(index).priceCents() <= byDays.get(index - 1).priceCents()) {
                throw new IllegalArgumentException("tiers must get more expensive as they get longer: "
                        + byDays.get(index).days()
                        + " days costs " + byDays.get(index).priceCents() + "c but "
                        + byDays.get(index - 1).days() + " days costs "
                        + byDays.get(index - 1).priceCents() + "c");
            }
        }
    }

    /**
     * The language list. It is the one list that may not be empty.
     *
     * {@code en} is the one entry it may not leave out - not as a demand on the operator but because the spec's
     * own default already writes {@code en} and {@code de}
     * with every id blank, so a fresh file satisfies both without anybody typing anything. {@code en} is what a
     * missing translation falls back to; without it the failure surfaces as a message key on a disconnect screen
     * rather than at startup. Tags are unique and lower case because a tag is the bundle file name and the value in
     * {@code discord_user.locale}, and nothing downstream case-folds a file name.
     */
    private static void validateLanguages(final List<AccessSpec.LanguageSpec> languages) {
        if (languages == null || languages.isEmpty()) {
            throw new IllegalArgumentException(
                    SHAPE_OF_LANGUAGES.formatted("languages is empty, so nothing can be said to anybody."));
        }

        final Set<String> tags = new HashSet<>();
        for (int index = 0; index < languages.size(); index++) {
            final AccessSpec.LanguageSpec language = languages.get(index);
            final String path = "languages[" + index + "]";
            final String tag = language.tag() == null ? "" : language.tag();

            if (tag.isBlank()) {
                throw new IllegalArgumentException(path + ".tag is empty. A language is identified "
                        + "by its tag; it is also the name of its .properties bundle.");
            }
            if (!tag.equals(tag.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(path + ".tag must be lower case, was: " + tag);
            }
            if (tag.length() > MAX_TAG_LENGTH) {
                // managed_message.kind is varchar(32); a longer tag loads fine here and fails on the INSERT instead.
                throw new IllegalArgumentException(path + ".tag is longer than " + MAX_TAG_LENGTH
                        + " characters, which is as long as a managed message's key can be: " + tag);
            }
            if (!tags.add(tag)) {
                throw new IllegalArgumentException(path + " uses the tag '" + tag + "', which "
                        + "another entry already uses. Tags identify a language and must be unique.");
            }

            // All six are optional and switch off what they name when empty; present, each still must be a snowflake.
            requireSnowflakeIfSet(path + ".role", language.role());
            requireSnowflakeIfSet(path + ".contribution-channel", language.contributionChannel());
            requireSnowflakeIfSet(path + ".link-channel", language.linkChannel());
            requireSnowflakeIfSet(path + ".hunger-games-channel", language.hungerGamesChannel());
            requireSnowflakeIfSet(path + ".status-channel", language.statusChannel());
            requireSnowflakeIfSet(path + ".announcement-channel", language.announcementChannel());
        }

        if (!tags.contains(FALLBACK_LANGUAGE)) {
            throw new IllegalArgumentException(SHAPE_OF_LANGUAGES.formatted(
                    "languages has no '" + FALLBACK_LANGUAGE + "' entry. English is the fallback "
                            + "every missing translation degrades to and cannot be left out."));
        }
    }

    // Loading.

    private static <T> ConfigHandle<T> load(
            final String name, final Class<T> specType, final String envPrefix, final ConfigValidator<T> validator)
            throws ConfigException {
        final Path file = directory().resolve(name + ".yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<T> handle = ConfigLoader.builder(file, specType)
                .envPrefix(envPrefix)
                .validator(validator)
                .load();

        if (fresh) {
            log.warn(
                    "No config existed at {} - defaults were written and are almost certainly " + "not what you want",
                    file.toAbsolutePath());
        }
        recordEnvironmentOverrides(handle);
        return handle;
    }

    /**
     * Writes {@code handle}'s {@link ConfigHandle#environmentOverrides()} next to its file.
     *
     * That lets steward-worker warn that editing an overridden setting there has no effect until the variable is
     * removed. Best-effort: this is a UI nicety, not a reason for a correctly loaded config to refuse to start
     * the bot.
     */
    private static void recordEnvironmentOverrides(final ConfigHandle<?> handle) {
        try {
            EnvOverrideFile.write(handle.file(), handle.environmentOverrides());
        } catch (final IOException e) {
            log.warn("Could not write the environment-override marker beside {}: {}", handle.file(), e.getMessage());
        }
    }

    // Validation helpers.

    private static void requireText(final String key, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
    }

    private static void requireSecret(final String key, final String variable, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is empty. Set " + variable + " in the environment.");
        }
    }

    /**
     * The lenient form, which is now most of them.
     *
     * Empty is a decision ("this deployment has no donor role yet"), anything else has to be a real snowflake.
     */
    private static void requireSnowflakeIfSet(final String key, final String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        requireSnowflake(key, value);
    }

    private static void requireSnowflake(final String key, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is empty. Fill in the Discord id; the bot will not guess one.");
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                throw new IllegalArgumentException(key + " must be a Discord snowflake (digits only), was: " + value);
            }
        }
    }

    private static void requirePositive(final String key, final long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero, was " + value);
        }
    }
}
