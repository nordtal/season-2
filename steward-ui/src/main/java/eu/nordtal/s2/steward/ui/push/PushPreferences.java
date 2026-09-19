package eu.nordtal.s2.steward.ui.push;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Which kinds of alert each account wants pushed (steward/98, Till's review of 2026-09-18) - rows in
 * {@code steward_push_preference}, and the rule that a missing row is the type's own default.
 *
 * <h2>A missing row is not "off"</h2>
 * Nothing here ever writes a row for an account that has not touched a switch, and that is the whole
 * design rather than an omission. Steward has no accounts table (see {@code V20}), so there is no
 * moment at which defaults could be materialised - and if there were, the answer an account got
 * would depend on which release it first signed in under. Reading a default out of {@link AlertType}
 * every time makes it a property of the code: changeable in one place, identical for everyone.
 *
 * <p>Turning a switch back to where it started writes a row saying so rather than deleting one.
 * "I chose this" and "I have never looked" are different facts, and only the second one should
 * follow a changed default.</p>
 */
public final class PushPreferences {

    private final PushPreferenceDao dao;

    public PushPreferences(final @NotNull DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(PushPreferenceDao.class);
    }

    /** One account's effective answer for every type - defaults where it has never chosen. */
    public @NotNull Map<AlertType, Boolean> of(final @NotNull String discordId) {
        return effective(dao.forAccount(discordId));
    }

    /**
     * Every account's effective answer, in one query - what {@link AlertWatch} asks once per push.
     *
     * <p>An account with no row at all is simply absent from this map; {@link #enabled} answers the
     * default for it, which is the same thing said in one less row.</p>
     */
    public @NotNull Map<String, Map<AlertType, Boolean>> all() {
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
    public static boolean enabled(final Map<AlertType, Boolean> chosen, final @NotNull AlertType type) {
        if (chosen == null) {
            return type.enabledByDefault();
        }
        return chosen.getOrDefault(type, type.enabledByDefault());
    }

    /** One switch, set by the account it belongs to. */
    public void set(final @NotNull String discordId, final @NotNull AlertType type,
                    final boolean enabled) {
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
    public record Row(@ColumnName("discord_id") @NotNull String discordId,
                      @ColumnName("alert_type") @NotNull String alertType,
                      boolean enabled) {
    }
}
