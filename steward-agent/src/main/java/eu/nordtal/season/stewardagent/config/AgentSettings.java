package eu.nordtal.season.stewardagent.config;

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
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;

/**
 * steward-agent's settings: the connection from the environment, the {@code runs} group from the database.
 *
 * Every value is checked once at load, so a bad one names its key and group before a run uses it.
 */
public final class AgentSettings {

    /** The service whose settings these are. */
    public static final String SERVICE = "steward-agent";

    /** {@code NORDTAL_STEWARD_AGENT} for the runs group, {@code NORDTAL_STEWARD_AGENT_<GROUP>} for the others. */
    public static final Environment ENVIRONMENT =
            Environment.of("NORDTAL_STEWARD_AGENT").withMain("runs");

    /** {@code owner/name}, the only form the GitHub API takes. */
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    /** A Modrinth id is eight characters of its own base62 alphabet. */
    private static final Pattern MODRINTH_ID = Pattern.compile("[A-Za-z0-9]{8}");

    private AgentSettings() {}

    /** Returns the runs group, read again by every run, so a change in Steward counts from the next one. */
    public static Group<RunSpec> runsGroup() {
        return Group.of("runs", RunSpec.class)
                .checkedBy(AgentSettings::checkRuns)
                .whileRunning();
    }

    /** Loads the runs group. */
    public static Setting<RunSpec> runs(final Settings settings) throws SettingsException {
        return settings.load(runsGroup());
    }

    /** Loads the connection to the database from the environment, refusing an empty password. */
    public static Setting<DatabaseSpec> database() throws SettingsException {
        return EnvironmentSettings.of(ENVIRONMENT)
                .load(Group.of("database", DatabaseSpec.class).checkedBy(AgentSettings::checkDatabase));
    }

    /** Returns the agent's settings in the database behind {@code dataSource}. */
    public static DatabaseSettings stored(final DataSource dataSource, final Logger logger) {
        return DatabaseSettings.over(SettingStore.using(dataSource), SERVICE, ENVIRONMENT, logger);
    }

    static void checkRuns(final RunSpec config) {
        requireRepo("season-repo", config.seasonRepo());
        requireRepo("display-tags-repo", config.displayTagsRepo());
        requireModrinthId("packetevents-project", config.packetEventsProject());
        Checks.requireText("volumes-root", config.volumesRoot());
        Checks.requirePositive("http-timeout-seconds", config.httpTimeoutSeconds());
        Checks.requirePositive("download-timeout-seconds", config.downloadTimeoutSeconds());
        Checks.requirePositive("backup.patience-minutes", config.backup().patienceMinutes());
    }

    private static void checkDatabase(final DatabaseSpec config) {
        DatabasePool.check(config);
        if (config.jdbcUrl() == null || !config.jdbcUrl().startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("jdbc-url must be a PostgreSQL URL (jdbc:postgresql://host:port/db)");
        }
        if (config.password() == null || config.password().isBlank()) {
            throw new IllegalArgumentException("password is empty."
                    + " NORDTAL_STEWARD_AGENT_DATABASE_PASSWORD is what compose.yml sets from POSTGRES_PASSWORD;"
                    + " an empty one means the variable did not reach this container");
        }
        Checks.requirePositive("maximum-pool-size", config.maximumPoolSize());
    }

    private static void requireRepo(final String key, final String value) {
        Checks.requireText(key, value);
        if (!REPO.matcher(value).matches()) {
            throw new IllegalArgumentException(key + " must be a GitHub repository as owner/name"
                    + " - not a URL and not just the name - was '" + value + "'");
        }
    }

    private static void requireModrinthId(final String key, final String value) {
        Checks.requireText(key, value);
        if (!MODRINTH_ID.matcher(value).matches()) {
            // A slug only fails as an id when the author renames it, so it is caught here.
            throw new IllegalArgumentException(key + " must be a Modrinth project id: eight"
                    + " alphanumeric characters, not the slug. Read it from the 'project_id' field"
                    + " of any version, or from a cdn.modrinth.com/data/<id>/ URL. Was '"
                    + value + "'");
        }
    }
}
