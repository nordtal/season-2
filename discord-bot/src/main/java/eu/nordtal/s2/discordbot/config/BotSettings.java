package eu.nordtal.s2.discordbot.config;

import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.settings.Checks;
import eu.nordtal.s2.settings.DatabasePool;
import eu.nordtal.s2.settings.DatabaseSettings;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.Environment;
import eu.nordtal.s2.settings.EnvironmentSettings;
import eu.nordtal.s2.settings.Group;
import eu.nordtal.s2.settings.Setting;
import eu.nordtal.s2.settings.Settings;
import eu.nordtal.s2.settings.SettingsException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;

/**
 * Loads the bot's settings and validates every value: the connection from the environment, the rest from the database.
 *
 * Each group has its own environment prefix, {@code NORDTAL_<GROUP>}; only the guild and the admin role are required.
 */
public final class BotSettings {

    /** The service whose settings these are. */
    public static final String SERVICE = "discord-bot";

    /** The bot's environment: every group under {@code NORDTAL_<GROUP>}. */
    static final Environment ENVIRONMENT = Environment.of("NORDTAL");

    /** The guild, its roles and channels, the price list and the languages. */
    static final Group<AccessSpec> ACCESS = Group.of("access", AccessSpec.class).checkedBy(BotSettings::validateAccess);

    /** System property for the config directory, which the tests point at a temporary one. */
    static final String DIRECTORY_PROPERTY = "access.config.dir";

    /** The one language the access settings may not leave out, the one {@link AccessSpec#languages()} protects. */
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

    private BotSettings() {}

    /**
     * Returns where an operator's message overrides go.
     *
     * @return the override directory, which {@code Messages.load} creates if it is not there
     */
    public static Path messagesDirectory() {
        return directory().resolve("messages");
    }

    private static Path directory() {
        return Path.of(System.getProperty(DIRECTORY_PROPERTY, "config"));
    }

    /** Loads the connection to the database, which the environment alone holds. */
    public static Setting<DatabaseSpec> database() throws SettingsException {
        return EnvironmentSettings.of(ENVIRONMENT)
                .load(Group.of("database", DatabaseSpec.class).checkedBy(DatabasePool::check));
    }

    /** Returns the bot's settings in the database behind {@code dataSource}, importing its last files once. */
    public static DatabaseSettings stored(final DataSource dataSource) {
        return DatabaseSettings.over(
                        SettingStore.using(dataSource),
                        SERVICE,
                        ENVIRONMENT,
                        LoggerFactory.getLogger(BotSettings.class))
                .importingFrom(directory(), Set.of());
    }

    /** Loads the bot group, whose Discord token comes from the environment alone. */
    public static Setting<BotSpec> bot(final Settings settings) throws SettingsException {
        return settings.load(Group.of("bot", BotSpec.class)
                .checkedBy(config -> Checks.requireSecret("token", "NORDTAL_BOT_TOKEN", config.token())));
    }

    /** Loads the guild, its roles and channels, the price list and the languages. */
    public static Setting<AccessSpec> access(final Settings settings) throws SettingsException {
        return settings.load(ACCESS);
    }

    /** Validates the access settings; snowflakes must be numeric, not merely non-empty. */
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

        Checks.requirePositive("donation-cents", config.donationCents());
        Checks.requirePositive("expiry-reminder-lead-days", config.expiryReminderLeadDays());
        Checks.requirePositive("link-code-attempts-per-hour", config.linkCodeAttemptsPerHour());
        Checks.requirePositive("role-reconcile-interval-minutes", config.roleReconcileIntervalMinutes());
        Checks.requirePositive("payment.request-ttl-hours", config.payment().requestTtlHours());
    }

    /** Validates the price list, which may be empty but must rise in price with its day count. */
    private static void validateTiers(final List<AccessSpec.TierSpec> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return;
        }

        final Set<Integer> days = new HashSet<>();
        for (int index = 0; index < tiers.size(); index++) {
            final AccessSpec.TierSpec tier = tiers.get(index);
            Checks.requirePositive("tiers[" + index + "].days", tier.days());
            Checks.requirePositive("tiers[" + index + "].price-cents", tier.priceCents());
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
}
