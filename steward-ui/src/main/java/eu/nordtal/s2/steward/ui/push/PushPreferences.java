package eu.nordtal.s2.steward.ui.push;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Jdbis;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jspecify.annotations.Nullable;

/**
 * Which kinds of alert each account wants pushed, where a missing row is the default.
 *
 * A switch turned back to its start writes a row rather than deleting one: "chose this" and "never looked" differ.
 */
public final class PushPreferences {

    private final PushPreferenceDao dao;

    public PushPreferences(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbis.over(dataSource).onDemand(PushPreferenceDao.class);
    }

    /** One account's effective answer for every type, defaults where it has never chosen. */
    public Map<AlertType, Boolean> of(final DiscordId discordId) {
        return effective(dao.forAccount(discordId));
    }

    /** Every account's own choices in one query; an account with no row is absent, and {@link #enabled} answers it. */
    public Map<String, Map<AlertType, Boolean>> all() {
        final Map<String, Map<AlertType, Boolean>> chosen = new HashMap<>();
        for (final Row row : dao.all()) {
            final AlertType type = AlertType.of(row.alertType());
            if (type == null) {
                continue;
            }
            chosen.computeIfAbsent(row.discordId().value(), ignored -> new EnumMap<>(AlertType.class))
                    .put(type, row.enabled());
        }
        return chosen;
    }

    /** Whether an account wants this type, given its map out of {@link #all}, or null when it has chosen nothing. */
    public static boolean enabled(final @Nullable Map<AlertType, Boolean> chosen, final AlertType type) {
        if (chosen == null) {
            return type.enabledByDefault();
        }
        return chosen.getOrDefault(type, type.enabledByDefault());
    }

    /** Sets one switch, for the account it belongs to. */
    public void set(final DiscordId discordId, final AlertType type, final boolean enabled) {
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
            @ColumnName("discord_id") DiscordId discordId,
            @ColumnName("alert_type") String alertType,
            boolean enabled) {}
}
