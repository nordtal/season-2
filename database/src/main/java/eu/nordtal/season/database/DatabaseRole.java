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

/** A role the schema grants to. {@code V1} names it by the Flyway placeholder {@link #placeholder()}. */
public enum DatabaseRole {
    DISCORD_BOT(Login.PASSWORD),

    PROXY(Login.PASSWORD),

    LIMBO(Login.PASSWORD),

    HUNGER_GAMES(Login.PASSWORD),

    SMP(Login.PASSWORD),

    /** Steward, which V1 grants to by its old name {@code role_steward_ui}. */
    STEWARD(Login.PASSWORD, "role_steward_ui"),

    /** The shared read models. Every role above is a member of it. Nobody logs in as it. */
    READ(Login.NONE),

    /** {@code pg_dump}, through the socket of the postgres container and without a password. */
    BACKUP(Login.SOCKET);

    public static final String PREFIX = "nordtal_";

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

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Returns the Flyway placeholder of this role, such as {@code role_hunger_games}. */
    public String placeholder() {
        return placeholder != null ? placeholder : "role_" + key();
    }

    /** Returns the role name under {@link #PREFIX}. */
    public String roleName() {
        return roleName(PREFIX);
    }

    /** Returns the role name under another prefix. */
    public String roleName(final String prefix) {
        return prefix + key();
    }

    public boolean hasPassword() {
        return login == Login.PASSWORD;
    }

    /** Returns every placeholder with its role name. */
    public static Map<String, String> placeholders(final String prefix) {
        final Map<String, String> named = new LinkedHashMap<>();
        for (final DatabaseRole role : values()) {
            named.put(role.placeholder(), role.roleName(prefix));
        }
        return named;
    }

    /**
     * Creates every missing role and sets each password.
     *
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

    /** Runs the statement that {@code format} builds from bound values. */
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
