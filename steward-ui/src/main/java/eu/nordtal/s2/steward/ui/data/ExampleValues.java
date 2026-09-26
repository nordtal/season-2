package eu.nordtal.s2.steward.ui.data;

import eu.nordtal.s2.common.message.context.SeasonContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/**
 * What a translation editor fills a placeholder with, per context type rather than per message: one
 * player, one team, one milestone for every text that names one.
 *
 * <p>Real rows first - the asking admin's own account before anybody else's - and a fixed value per
 * type when the table has nothing, so a preview never shows an empty hole. A milestone is its config
 * key: its name is a translation itself, and may be the very text being edited.</p>
 */
public final class ExampleValues {

    private final DataSource dataSource;

    public ExampleValues(final @NotNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * @param discordId   the admin asking, whose own account is the example player when it has one
     * @param displayName the admin's name from the session, the last resort for a Discord member
     * @return type to property to value, one entry per context type
     */
    public @NotNull Map<String, Map<String, String>> of(
            final @NotNull String discordId, final @NotNull String displayName) {
        final Map<String, Map<String, String>> answer = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            answer.put(
                    "player",
                    named(first(
                                    connection,
                                    "SELECT mc_name FROM account_link WHERE mc_name IS NOT NULL"
                                            + " ORDER BY discord_id = ? DESC, linked DESC NULLS LAST LIMIT 1",
                                    discordId)
                            .or(() -> first(
                                    connection, "SELECT mc_name FROM online_player ORDER BY updated DESC LIMIT 1"))
                            .orElse("Steve")));
            answer.put(
                    "discord-member",
                    named("@"
                            + first(
                                            connection,
                                            "SELECT coalesce(discord_display_name, discord_username) FROM discord_user"
                                                    + " WHERE discord_id = ?",
                                            discordId)
                                    .orElse(displayName)));
            answer.put(
                    "team",
                    named(first(connection, "SELECT name FROM hg_team ORDER BY created DESC NULLS LAST LIMIT 1")
                            .orElse("Nordlichter")));
            answer.put(
                    "milestone",
                    named(first(
                                    connection,
                                    "SELECT key FROM smp_milestone ORDER BY state = 'ACTIVE' DESC, unlocked DESC NULLS LAST"
                                            + " LIMIT 1")
                            .orElse("frontier")));
        } catch (final SQLException failure) {
            throw new IllegalStateException("could not read the example values", failure);
        }
        answer.put("service", named("smp"));
        answer.put("season", Map.of("number", String.valueOf(SeasonContext.CURRENT.number())));
        return answer;
    }

    private static Map<String, String> named(final String name) {
        return Map.of("name", name);
    }

    /** The first column of the first row, or empty when there is none or it is null. */
    private static Optional<String> first(final Connection connection, final String sql, final String... parameters) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
            }
        } catch (final SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
