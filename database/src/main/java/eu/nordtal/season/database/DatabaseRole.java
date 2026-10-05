package eu.nordtal.season.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * A role the schema grants to, named in {@code V1} by the Flyway placeholder {@link #placeholder()}.
 *
 * Roles belong to the cluster, so the migrator creates them with {@link #provision} before it migrates.
 */
public enum DatabaseRole {

    /** discord-bot. */
    DISCORD_BOT(Login.PASSWORD),

    /** The proxy. */
    PROXY(Login.PASSWORD),

    /** The waiting room. */
    LIMBO(Login.PASSWORD),

    /** The Hunger Games server. */
    HUNGER_GAMES(Login.PASSWORD),

    /** The SMP server. */
    SMP(Login.PASSWORD),

    /** Steward, which V1 grants to by the name it had when V1 was frozen, {@code role_steward_ui}. */
    STEWARD(Login.PASSWORD, "role_steward_ui"),

    /** The shared read models; every role above is a member, and nobody logs in as it. */
    READ(Login.NONE),

    /** {@code pg_dump}, through the postgres container's own socket, so without a password. */
    BACKUP(Login.SOCKET);

    /** The prefix of every role name outside a test. */
    public static final String PREFIX = "nordtal_";

    /** How a role logs in. */
    private enum Login {
        PASSWORD,
        SOCKET,
        NONE
    }

    private final Login login;
    private final @Nullable String placeholder;

    DatabaseRole(final Login login) {
        this(login, null);
    }

    DatabaseRole(final Login login, final @Nullable String placeholder) {
        this.login = login;
        this.placeholder = placeholder;
    }

    /** Returns this role's part of a name, such as {@code hunger_games}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Returns the Flyway placeholder a migration names this role by, such as {@code role_hunger_games}. */
    public String placeholder() {
        return placeholder != null ? placeholder : "role_" + key();
    }

    /** Returns the role's name under {@link #PREFIX}, which compose.yml gives each service as its user. */
    public String roleName() {
        return roleName(PREFIX);
    }

    /** Returns the role's name under another prefix, for a scratch cluster that must not touch the real roles. */
    public String roleName(final String prefix) {
        return prefix + key();
    }

    /** Returns whether the role logs in with a password, which the installation hands to the migrator. */
    public boolean hasPassword() {
        return login == Login.PASSWORD;
    }

    /** Returns every placeholder with its role name, as Flyway's {@code placeholders} takes them. */
    public static Map<String, String> placeholders(final String prefix) {
        final Map<String, String> named = new LinkedHashMap<>();
        for (final DatabaseRole role : values()) {
            named.put(role.placeholder(), role.roleName(prefix));
        }
        return named;
    }

    /**
     * Creates every role that does not exist yet and sets each password, so a changed one takes effect.
     *
     * @param owner     a connection allowed to create roles, the migrator's
     * @param passwords one per role that {@link #hasPassword()}
     * @throws IllegalArgumentException when a password is missing or blank
     */
    public static void provision(final DataSource owner, final String prefix, final Map<DatabaseRole, String> passwords)
            throws SQLException {
        try (Connection connection = owner.getConnection()) {
            for (final DatabaseRole role : values()) {
                final @Nullable String password = passwords.get(role);
                if (role.hasPassword() && (password == null || password.isBlank())) {
                    throw new IllegalArgumentException("no password for the database role " + role.roleName(prefix));
                }
                final String name = role.roleName(prefix);
                create(connection, name);
                if (role.hasPassword()) {
                    execute(connection, role.alter(), name, Objects.requireNonNull(password, "password"));
                } else {
                    execute(connection, role.alter(), name);
                }
            }
        }
    }

    /** The {@code format} call that builds the statement giving the role its login; its arguments are bound. */
    private String alter() {
        return switch (login) {
            case PASSWORD -> "SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', ?, ?)";
            case SOCKET -> "SELECT format('ALTER ROLE %I LOGIN PASSWORD NULL', ?)";
            case NONE -> "SELECT format('ALTER ROLE %I NOLOGIN', ?)";
        };
    }

    private static void create(final Connection connection, final String name) throws SQLException {
        try (PreparedStatement exists = connection.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
            exists.setString(1, name);
            try (ResultSet row = exists.executeQuery()) {
                if (row.next()) {
                    return;
                }
            }
        }
        execute(connection, "SELECT format('CREATE ROLE %I', ?)", name);
    }

    /** Runs the one statement {@code format} builds from bound values, so nothing is quoted by hand. */
    private static void execute(final Connection connection, final String formatting, final String... values)
            throws SQLException {
        final String statement;
        try (PreparedStatement build = connection.prepareStatement(formatting)) {
            for (int i = 0; i < values.length; i++) {
                build.setString(i + 1, values[i]);
            }
            try (ResultSet row = build.executeQuery()) {
                row.next();
                statement = row.getString(1);
            }
        }
        try (Statement run = connection.createStatement()) {
            run.execute(statement);
        }
    }
}
