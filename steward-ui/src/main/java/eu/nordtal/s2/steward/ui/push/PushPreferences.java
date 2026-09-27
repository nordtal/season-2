package eu.nordtal.s2.steward.ui.push;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Which kinds of alert each account wants pushed, and the rule that a missing row is the default.
 *
 * Nothing here ever writes a row for an account that has not touched a switch. Turning a switch
 * back to where it started writes a row saying so rather than deleting one: "chose this" and
 * "never looked" are different facts.
 */
public final class PushPreferences {

    private final PushPreferenceDao dao;

    public PushPreferences(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(PushPreferenceDao.class);
    }

    /** One account's effective answer for every type - defaults where it has never chosen. */
    public Map<AlertType, Boolean> of(final String discordId) {
        return effective(dao.forAccount(discordId));
    }

    /**
     * Every account's effective answer, in one query - what {@link AlertWatch} asks once per push.
     *
     * An account with no row at all is simply absent from this map; {@link #enabled} answers the
     * default for it.
     */
    public Map<String, Map<AlertType, Boolean>> all() {
        final Map<String, Map<AlertType, Boolean>> chosen = new HashMap<>();
        for (final Row row : dao.all()) {
            final AlertType type = AlertType.of(row.alertType());
            if (type == null) {
                continue;
            }
            chosen.computeIfAbsent(row.discordId(), ignored -> new EnumMap<>(AlertType.class))
                    .put(type, row.enabled());
        }
        return chosen;
    }

    /**
     * Whether this account wants this type, given whatever {@link #all} found for it.
     *
     * @param chosen that account's own map out of {@link #all}, or null when it has chosen nothing
     */
    public static boolean enabled(final @Nullable Map<AlertType, Boolean> chosen, final AlertType type) {
        if (chosen == null) {
            return type.enabledByDefault();
        }
        return chosen.getOrDefault(type, type.enabledByDefault());
    }

    /** One switch, set by the account it belongs to. */
    public void set(final String discordId, final AlertType type, final boolean enabled) {
        dao.set(discordId, type.key(), enabled);
    }

    private static Map<AlertType, Boolean> effective(final Iterable<Row> rows) {
        final Map<AlertType, Boolean> chosen = new EnumMap<>(AlertType.class);
        for (final Row row : rows) {
            final AlertType type = AlertType.of(row.alertType());
            if (type != null) {
                chosen.put(type, row.enabled());
            }
        }
        final Map<AlertType, Boolean> answer = new EnumMap<>(AlertType.class);
        for (final AlertType type : AlertType.values()) {
            answer.put(type, chosen.getOrDefault(type, type.enabledByDefault()));
        }
        return answer;
    }

    /** One row of {@code steward_push_preference}. */
    public record Row(
            @ColumnName("discord_id") String discordId,
            @ColumnName("alert_type") String alertType,
            boolean enabled) {}
}
