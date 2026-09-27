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
 * Loads the bot's config files and validates every value, stopping the process on a bad one.
 *
 * Each file has its own environment prefix; only the guild and the admin role are required.
 */
@Slf4j
public final class Configs {

    /** System property for the config directory, which the tests point at a temporary one. */
    static final String DIRECTORY_PROPERTY = "access.config.dir";

    /** The one language {@code access.yml} may not leave out, the same one {@link AccessSpec#languages()} protects. */
    private static final String FALLBACK_LANGUAGE = Languages.FALLBACK_TAG;

    /**
     * The longest tag {@code managed_message.kind}, a {@code varchar(32)} holding {@code "CONTRIBUTION_" + TAG}, fits.
     */
    private static final int MAX_TAG_LENGTH = 32 - "CONTRIBUTION_".length();

    /** The usable shape of the language list, with a slot for why the current one is not. */
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
     * Returns where an operator's message overrides go, beside the YAML files.
     *
     * @return the override directory, which {@code Messages.load} creates if it is not there
     */
    public static Path messagesDirectory() {
        return directory().resolve("messages");
    }

    private static Path directory() {
        return Path.of(System.getProperty(DIRECTORY_PROPERTY, "config"));
    }

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

    /** Loads {@code bot.yml}, which holds the Discord token. */
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

    /** Validates {@code access.yml}; snowflakes must be numeric, not merely non-empty. */
    private static void validateAccess(final AccessSpec config) {
        // No guild means nothing to act on, no admin role means nobody can administer.
        requireSnowflake("guild-id", config.guildId());
        requireSnowflake("roles.admin", config.roles().admin());

        // Everything below is optional; empty means the feature is not served.
        requireSnowflakeIfSet("roles.access", config.roles().access());
        requireSnowflakeIfSet("roles.donor", config.roles().donor());
        requireSnowflakeIfSet("roles.admin-ping", config.roles().adminPing());

        requireSnowflakeIfSet("channels.admin", config.channels().admin());

        validateTiers(config.tiers());
        validateLanguages(config.languages());

        requirePositive("donation-cents", config.donationCents());
        requirePositive("expiry-reminder-lead-days", config.expiryReminderLeadDays());
        requirePositive("link-code-attempts-per-hour", config.linkCodeAttemptsPerHour());
        requirePositive("role-reconcile-interval-minutes", config.roleReconcileIntervalMinutes());
        requirePositive("payment.poll-interval-seconds", config.payment().pollIntervalSeconds());
        requirePositive("payment.request-ttl-hours", config.payment().requestTtlHours());
    }

    /** Validates the price list, which may be empty but must rise in price with its day count. */
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
                // A tier is identified by its day count, so a duplicate is ambiguous.
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

    /** Validates the language list: non-empty, unique lower case tags, {@code en} present. */
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
                // A longer tag would load here and fail on the INSERT instead.
                throw new IllegalArgumentException(path + ".tag is longer than " + MAX_TAG_LENGTH
                        + " characters, which is as long as a managed message's key can be: " + tag);
            }
            if (!tags.add(tag)) {
                throw new IllegalArgumentException(path + " uses the tag '" + tag + "', which "
                        + "another entry already uses. Tags identify a language and must be unique.");
            }

            // All optional; present, each must be a snowflake.
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

    /** Writes {@code handle}'s environment overrides next to its file, best effort, for steward-worker to show. */
    private static void recordEnvironmentOverrides(final ConfigHandle<?> handle) {
        try {
            EnvOverrideFile.write(handle.file(), handle.environmentOverrides());
        } catch (final IOException e) {
            log.warn("Could not write the environment-override marker beside {}: {}", handle.file(), e.getMessage());
        }
    }

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

    /** Empty is allowed; anything else must be a snowflake. */
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
