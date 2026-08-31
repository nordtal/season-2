package eu.nordtal.s2.discordbot.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.ConfigValidator;
import eu.nordtal.jcore.config.exception.ConfigException;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where the bot's config files live, and every rule about what a valid value is.
 * <p>
 * Each file gets its own environment namespace - {@code NORDTAL_DATABASE_*}, {@code NORDTAL_BOT_*},
 * {@code NORDTAL_ACCESS_*}. A single shared {@code NORDTAL} prefix would make generic keys collide
 * across files: {@code password} in {@code database.yml} and a {@code password} anywhere else
 * would both be {@code NORDTAL_PASSWORD}.
 * </p>
 * <p>
 * <b>Every check here runs at startup and stops the process.</b> Season 1's bot loaded its config
 * inside the service that used it, caught the failure and carried on with hardcoded defaults, so a
 * broken file ran the bot against the wrong Discord channels. Nothing here is lenient.
 * </p>
 * <p>
 * The one-time jcore 1.x {@code config/*.json} conversion that lived here until stage B is gone.
 * It existed to carry season 1's deployed config volume forward, and season 2 does not carry
 * anything forward - new bot, new database, new Discord application, new config volume (see the
 * workspace {@code CLAUDE.md}: nothing is ever migrated between seasons). {@code access.yml} did
 * not exist in season 1, so there is nothing it could have converted anyway.
 * </p>
 */
@Slf4j
public final class Configs {

    /**
     * Where the config files live. Mounted as a Docker volume; see the module Dockerfile.
     * <p>
     * Overridable with {@code -Daccess.config.dir=...} so the tests can point it at a temporary
     * directory. Nothing in production sets it.
     */
    static final String DIRECTORY_PROPERTY = "access.config.dir";

    /** The one language {@code access.yml} may not leave out; see {@code docs/i18n.md}. */
    private static final String FALLBACK_LANGUAGE = "en";

    /**
     * What to write when the language list is unusable, with a slot for why it is.
     * <p>
     * The YAML is in the message rather than only in the file's comments because this is exactly
     * the moment somebody has a file that does not load and no example to copy from.
     * </p>
     */
    private static final String SHAPE_OF_LANGUAGES = """
            %s Write at least the fallback:

              languages:
              - tag: en
                role: '000000000000000000'
                contribution-channel: '000000000000000000'
                link-channel: '000000000000000000'""";

    private Configs() {
    }

    private static Path directory() {
        return Path.of(System.getProperty(DIRECTORY_PROPERTY, "config"));
    }

    // ------------------------------------------------------------------ the three configs

    public static @NotNull ConfigHandle<DatabaseSpec> database() throws ConfigException {
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

    public static @NotNull ConfigHandle<BotSpec> bot() throws ConfigException {
        return load("bot", BotSpec.class, "NORDTAL_BOT", config -> {
            requireSecret("token", "NORDTAL_BOT_TOKEN", config.token());
            requireSecret("bunq.api-key", "NORDTAL_BOT_BUNQ_API_KEY", config.bunq().apiKey());
            requireSecret("bunq.account-id", "NORDTAL_BOT_BUNQ_ACCOUNT_ID", config.bunq().accountId());
            try {
                Long.parseLong(config.bunq().accountId().trim());
            } catch (final NumberFormatException e) {
                // The season 1 code called Long.parseLong inside the poll loop, so a wrong value
                // surfaced as a NumberFormatException minutes into a run.
                throw new IllegalArgumentException("bunq.account-id must be a number");
            }
            final String environment = config.bunq().environment();
            if (!"PRODUCTION".equals(environment) && !"SANDBOX".equals(environment)) {
                throw new IllegalArgumentException(
                        "bunq.environment must be PRODUCTION or SANDBOX, was: " + environment);
            }
        });
    }

    public static @NotNull ConfigHandle<AccessSpec> access() throws ConfigException {
        return load("access", AccessSpec.class, "NORDTAL_ACCESS", Configs::validateAccess);
    }

    /**
     * Everything {@code access.yml} has to get right before the bot is allowed to touch a guild.
     * <p>
     * Snowflakes are checked for being numeric rather than merely non-empty: a role id with a
     * stray character is otherwise a {@code null} role deep inside a role assignment, hours later.
     * </p>
     */
    private static void validateAccess(final AccessSpec config) {
        requireSnowflake("guild-id", config.guildId());

        requireSnowflake("roles.access", config.roles().access());
        requireSnowflake("roles.donor", config.roles().donor());
        requireSnowflake("roles.german", config.roles().german());
        requireSnowflake("roles.english", config.roles().english());
        requireSnowflake("roles.admin", config.roles().admin());
        requireSnowflake("roles.admin-ping", config.roles().adminPing());

        requireSnowflake("channels.contribution-en", config.channels().contributionEn());
        requireSnowflake("channels.contribution-de", config.channels().contributionDe());
        requireSnowflake("channels.link-en", config.channels().linkEn());
        requireSnowflake("channels.link-de", config.channels().linkDe());
        requireSnowflake("channels.admin", config.channels().admin());

        validateTiers(config.tiers());
        validateLanguages(config.languages());

        requirePositive("donation-cents", config.donationCents());
        requirePositive("expiry-reminder-lead-days", config.expiryReminderLeadDays());
        requirePositive("role-reconcile-interval-minutes", config.roleReconcileIntervalMinutes());
        requirePositive("payment.poll-interval-seconds", config.payment().pollIntervalSeconds());
        requirePositive("payment.request-ttl-hours", config.payment().requestTtlHours());
        requirePositive("payment.recent-payment-count", config.payment().recentPaymentCount());

        // Blank is the normal case: the bot stamps its own first-start instant into the database
        // and uses that. A value here is an explicit override and has to be readable.
        final String watermark = config.payment().watermark();
        if (watermark != null && !watermark.isBlank()) {
            try {
                Instant.parse(watermark.trim());
            } catch (final DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "payment.watermark must be empty or an ISO-8601 instant such as "
                                + "2026-09-01T00:00:00Z, was: " + watermark);
            }
        }
    }

    /**
     * The price list.
     * <p>
     * The ordering is a validation rather than a sort, because the tiers are what the purchase
     * buttons offer and what the downgrade rule walks. A list where a longer period is cheaper is
     * not something to quietly reorder - it is a mistake, and the person who made it is the only
     * one who knows which of the two numbers is wrong.
     * </p>
     */
    private static void validateTiers(final List<AccessSpec.TierSpec> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("""
                    tiers is empty, so there is nothing to buy. Write at least one entry:

                      tiers:
                      - days: 30
                        price-cents: 300
                      - days: 60
                        price-cents: 500
                      - days: 90
                        price-cents: 700""");
        }

        final Set<Integer> days = new HashSet<>();
        for (int index = 0; index < tiers.size(); index++) {
            final AccessSpec.TierSpec tier = tiers.get(index);
            requirePositive("tiers[" + index + "].days", tier.days());
            requirePositive("tiers[" + index + "].price-cents", tier.priceCents());
            if (!days.add(tier.days())) {
                // A tier is identified by its day count - that is what a purchase button carries -
                // so two entries offering the same number of days is an ambiguous lookup.
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
                throw new IllegalArgumentException(
                        "tiers must get more expensive as they get longer: " + byDays.get(index).days()
                                + " days costs " + byDays.get(index).priceCents() + "c but "
                                + byDays.get(index - 1).days() + " days costs "
                                + byDays.get(index - 1).priceCents() + "c");
            }
        }
    }

    /**
     * The language list.
     * <p>
     * The rules are {@code docs/i18n.md}'s, enforced by hand like every other rule here.
     * {@code en} is mandatory because it is what a missing translation falls back to: a list
     * without it has no floor, and the failure would surface as a message key on a disconnect
     * screen rather than at startup. Tags are unique because a tag identifies a language
     * everywhere else - it is the bundle file name and the value in {@code discord_user.locale} -
     * and they are lower case for the same reason, since nothing downstream case-folds a file name.
     * </p>
     */
    private static void validateLanguages(final List<AccessSpec.LanguageSpec> languages) {
        if (languages == null || languages.isEmpty()) {
            throw new IllegalArgumentException(SHAPE_OF_LANGUAGES.formatted(
                    "languages is empty, so nothing can be said to anybody."));
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
            if (!tags.add(tag)) {
                throw new IllegalArgumentException(path + " uses the tag '" + tag + "', which "
                        + "another entry already uses. Tags identify a language and must be unique.");
            }

            requireSnowflake(path + ".role", language.role());
            requireSnowflake(path + ".contribution-channel", language.contributionChannel());
            requireSnowflake(path + ".link-channel", language.linkChannel());
        }

        if (!tags.contains(FALLBACK_LANGUAGE)) {
            throw new IllegalArgumentException(SHAPE_OF_LANGUAGES.formatted(
                    "languages has no '" + FALLBACK_LANGUAGE + "' entry. English is the fallback "
                            + "every missing translation degrades to and cannot be left out."));
        }
    }

    // ------------------------------------------------------------------ loading

    private static <T> ConfigHandle<T> load(final String name, final Class<T> specType,
                                            final String envPrefix,
                                            final ConfigValidator<T> validator) throws ConfigException {
        final Path file = directory().resolve(name + ".yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<T> handle = ConfigLoader.builder(file, specType)
                .envPrefix(envPrefix)
                .validator(validator)
                .load();

        if (fresh) {
            log.warn("No config existed at {} - defaults were written and are almost certainly "
                    + "not what you want", file.toAbsolutePath());
        }
        return handle;
    }

    // ------------------------------------------------------------------ validation helpers

    private static void requireText(final String key, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
    }

    private static void requireSecret(final String key, final String variable, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    key + " is empty. Set " + variable + " in the environment.");
        }
    }

    private static void requireSnowflake(final String key, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    key + " is empty. Fill in the Discord id; the bot will not guess one.");
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                throw new IllegalArgumentException(
                        key + " must be a Discord snowflake (digits only), was: " + value);
            }
        }
    }

    private static void requirePositive(final String key, final long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero, was " + value);
        }
    }
}
