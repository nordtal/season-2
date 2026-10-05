package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.settings.Checks;
import eu.nordtal.season.settings.DatabasePool;
import eu.nordtal.season.settings.DatabaseSettings;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.Environment;
import eu.nordtal.season.settings.EnvironmentSettings;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.Setting;
import eu.nordtal.season.settings.Settings;
import eu.nordtal.season.settings.SettingsException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;

/**
 * Loads the bot's settings and validates every value: the connection from the environment, the rest from the database.
 *
 * Each group has its own environment prefix, {@code NORDTAL_<GROUP>}; only the guild is required.
 */
public final class BotSettings {

    /** The service whose settings these are. */
    public static final String SERVICE = "discord-bot";

    /** The bot's environment: every group under {@code NORDTAL_<GROUP>}. */
    static final Environment ENVIRONMENT = Environment.of("NORDTAL");

    /** The guild, its roles and channels, and the languages. */
    static final Group<AccessSpec> ACCESS = Group.of("access", AccessSpec.class).checkedBy(BotSettings::validateAccess);

    /** Where a member chooses a language and a region, and the lock; taken while the bot runs. */
    static final Group<OnboardingSpec> ONBOARDING = Group.of("onboarding", OnboardingSpec.class)
            .checkedBy(BotSettings::validateOnboarding)
            .whileRunning();

    /** As many options as one select menu holds. */
    static final int MAX_CHOICES = 25;

    /** As long as Discord lets a role's name be. */
    private static final int MAX_ROLE_NAME = 100;

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
                role-name: English
                contribution-channel: '000000000000000000'
                link-channel: '000000000000000000'
                hunger-games-channel: '000000000000000000'""";

    private BotSettings() {}

    /** Loads the connection to the database, which the environment alone holds. */
    public static Setting<DatabaseSpec> database() throws SettingsException {
        return EnvironmentSettings.of(ENVIRONMENT)
                .load(Group.of("database", DatabaseSpec.class).checkedBy(DatabasePool::check));
    }

    /** Returns the bot's settings in the database behind {@code dataSource}. */
    public static DatabaseSettings stored(final DataSource dataSource) {
        return DatabaseSettings.over(
                SettingStore.using(dataSource), SERVICE, ENVIRONMENT, LoggerFactory.getLogger(BotSettings.class));
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

    /** Loads the onboarding: its channel, the lock and the regions. */
    public static Setting<OnboardingSpec> onboarding(final Settings settings) throws SettingsException {
        return settings.load(ONBOARDING);
    }

    /** Validates the access settings; snowflakes must be numeric, not merely non-empty. */
    private static void validateAccess(final AccessSpec config) {
        // No guild means nothing to act on.
        requireSnowflake("guild-id", config.guildId());

        final Set<String> names = new HashSet<>();
        requireRoleName("role-names.access", config.roleNames().access(), names);
        requireRoleName("role-names.donor", config.roleNames().donor(), names);
        requireRoleName("role-names.admin", config.roleNames().admin(), names);

        // Optional; empty means the feature is not served.
        requireSnowflakeIfSet("channels.admin", config.channels().admin());

        validateLanguages(config.languages());
        for (final AccessSpec.LanguageSpec language : config.languages()) {
            requireRoleName("languages[" + language.tag() + "].role-name", Languages.roleNameOf(language), names);
        }

        Checks.requirePositive("expiry-reminder-lead-days", config.expiryReminderLeadDays());
        Checks.requirePositive("link-code-attempts-per-hour", config.linkCodeAttemptsPerHour());
        Checks.requirePositive("role-reconcile-interval-minutes", config.roleReconcileIntervalMinutes());
        Checks.requirePositive("payment.request-ttl-hours", config.payment().requestTtlHours());
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
            requireSnowflakeIfSet(path + ".contribution-channel", language.contributionChannel());
            requireSnowflakeIfSet(path + ".link-channel", language.linkChannel());
            requireSnowflakeIfSet(path + ".hunger-games-channel", language.hungerGamesChannel());
            requireSnowflakeIfSet(path + ".status-channel", language.statusChannel());
            requireSnowflakeIfSet(path + ".announcement-channel", language.announcementChannel());
        }

        if (languages.size() > MAX_CHOICES) {
            throw new IllegalArgumentException("languages holds " + languages.size() + " entries; a member chooses"
                    + " from at most " + MAX_CHOICES + ".");
        }
        if (!tags.contains(FALLBACK_LANGUAGE)) {
            throw new IllegalArgumentException(SHAPE_OF_LANGUAGES.formatted(
                    "languages has no '" + FALLBACK_LANGUAGE + "' entry. English is the fallback "
                            + "every missing translation degrades to and cannot be left out."));
        }
    }

    /** Validates the onboarding: an optional channel, a lock role, and between one and 25 regions of unique zones. */
    private static void validateOnboarding(final OnboardingSpec config) {
        requireSnowflakeIfSet("channel", config.channel());
        final Set<String> names = new HashSet<>();
        requireRoleName("lock-role", config.lockRole(), names);

        final List<OnboardingSpec.RegionSpec> regions = config.regions();
        if (regions == null || regions.isEmpty() || regions.size() > MAX_CHOICES) {
            throw new IllegalArgumentException("regions holds " + (regions == null ? 0 : regions.size())
                    + " entries; a member chooses from one to " + MAX_CHOICES + ".");
        }
        final Set<String> zones = new HashSet<>();
        for (int index = 0; index < regions.size(); index++) {
            final OnboardingSpec.RegionSpec region = regions.get(index);
            final String path = "regions[" + index + "]";
            requireRoleName(path + ".name", region.name(), names);
            final String zone = region.zone() == null ? "" : region.zone();
            if (!ZoneId.getAvailableZoneIds().contains(zone)) {
                throw new IllegalArgumentException(
                        path + ".zone must be an IANA time zone such as Europe/Berlin, was: " + zone);
            }
            if (!zones.add(zone)) {
                throw new IllegalArgumentException(path + " uses the zone " + zone + ", which another region"
                        + " already uses. A zone is what a region stands for, so each is one region's.");
            }
        }
    }

    /** A role's name: not empty, as long as Discord allows, and no other role's in the same group. */
    private static void requireRoleName(final String key, final String name, final Set<String> taken) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(key + " is empty. Name the role; the bot takes or creates it.");
        }
        if (name.strip().length() > MAX_ROLE_NAME) {
            throw new IllegalArgumentException(key + " is longer than " + MAX_ROLE_NAME + " characters: " + name);
        }
        if (!taken.add(name.strip())) {
            throw new IllegalArgumentException(
                    key + " is " + name + ", which another role already is. Every role needs its own name.");
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
