package eu.nordtal.s2.database;

import eu.nordtal.s2.database.registration.Game;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Writes a round of Hunger Games registration as discord-bot does, and games of it, for a test of what reads them.
 *
 * As the test container's superuser: what a role may write is {@code DatabaseRoleIntegrationTest}'s.
 */
public final class RoundSeed {

    private final DataSource dataSource;

    public RoundSeed(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Returns a new round in {@code state}: {@code OPEN}, {@code CLOSED} or {@code ENDED}. */
    public UUID round(final String state) {
        return uuid(
                "INSERT INTO registration (game, state) VALUES (?, ?) RETURNING id", Game.HUNGER_GAMES.key(), state);
    }

    /** Returns a new team of a round. */
    public UUID team(final UUID round, final String name) {
        return uuid("INSERT INTO team (registration_id, name) VALUES (?, ?) RETURNING id", round, name);
    }

    /** Returns a new member of a team in {@code state}, writing the Discord account first when it is new. */
    public UUID member(final UUID team, final String discordId, final String state) {
        execute("INSERT INTO discord_user (discord_id) VALUES (?) ON CONFLICT (discord_id) DO NOTHING", discordId);
        return uuid("""
                INSERT INTO team_member (team_id, registration_id, discord_id, state)
                SELECT id, registration_id, ?, ? FROM team WHERE id = ? RETURNING id
                """, discordId, state, team);
    }

    /** Links a member's Discord account to a Minecraft account, as redeeming a link code does. */
    public void link(final String discordId, final UUID mcUuid, final String mcName) {
        execute("INSERT INTO account_link (discord_id, mc_uuid, mc_name) VALUES (?, ?, ?)", discordId, mcUuid, mcName);
    }

    /** Returns a new game of a round in {@code state}, as hunger-games starts one. */
    public UUID game(final UUID round, final String state) {
        return uuid("INSERT INTO hg_game (registration_id, state) VALUES (?, ?) RETURNING id", round, state);
    }

    /** Runs one statement with its parameters in order. */
    public void execute(final String sql, final Object... parameters) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, sql, parameters)) {
            statement.execute();
        } catch (final SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }

    /** Returns the one text value a query answers, or {@code null} for no row or a null. */
    public @Nullable String text(final String sql, final Object... parameters) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, sql, parameters);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getString(1) : null;
        } catch (final SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }

    private UUID uuid(final String sql, final Object... parameters) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, sql, parameters);
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getObject(1, UUID.class);
        } catch (final SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }

    private static PreparedStatement prepare(final Connection connection, final String sql, final Object... parameters)
            throws SQLException {
        final PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
        return statement;
    }
}
