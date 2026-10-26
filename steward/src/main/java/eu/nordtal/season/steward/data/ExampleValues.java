package eu.nordtal.season.steward.data;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.settings.DatabaseSettings;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.settings.network.NetworkSettings;
import eu.nordtal.season.settings.network.SeasonSpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/** Provides the example values of the translation editor per context type, from database rows or fixed values. */
public final class ExampleValues {

    private final DataSource dataSource;

    public ExampleValues(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Returns the example values by type and property.
     * The player is the account of the asking admin, if it has one.
     */
    public Map<String, Map<String, String>> of(final DiscordId discordId, final String displayName) {
        final Map<String, Map<String, String>> answer = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            answer.put(
                    "player",
                    named(first(
                                    connection,
                                    "SELECT mc_name FROM account_link WHERE mc_name IS NOT NULL"
                                            + " ORDER BY discord_id = ? DESC, linked DESC NULLS LAST LIMIT 1",
                                    discordId.value())
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
                                            discordId.value())
                                    .orElse(displayName)));
            answer.put(
                    "team",
                    named(first(connection, "SELECT name FROM team ORDER BY created DESC LIMIT 1")
                            .orElse("Nordlichter")));
        } catch (final SQLException failure) {
            throw new IllegalStateException("could not read the example values", failure);
        }
        answer.put("service", named("smp"));
        answer.put("season", season());
        return answer;
    }

    /** Returns the season values from the stored settings over the defaults of the spec. */
    private Map<String, String> season() {
        final SeasonSpec season;
        try {
            season = DatabaseSettings.current(
                    SettingStore.using(dataSource), SettingStore.NETWORK, NetworkSettings.SEASON);
        } catch (final SettingsException unreadable) {
            throw new IllegalStateException("could not read the season", unreadable);
        }
        final Map<String, String> answer = new LinkedHashMap<>();
        answer.put("number", String.valueOf(season.number()));
        answer.put("name", season.name());
        return answer;
    }

    private static Map<String, String> named(final String name) {
        return Map.of("name", name);
    }

    /** Returns the first column of the first row, or empty without a row or with {@code null}. */
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
