package eu.nordtal.s2.steward.alert;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.alert.AlertChannel;
import eu.nordtal.s2.database.alert.AlertType;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/**
 * On which channels each admin wants each alert type, where a missing row is the type's default.
 *
 * A switch turned back to its start writes a row rather than deleting one: "chose this" and "never looked" differ.
 */
public final class AlertPreferences {

    private final Jdbi jdbi;

    public AlertPreferences(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /** Returns one admin's effective answer for every type and channel, defaults where nothing was chosen. */
    public Map<AlertType, Map<AlertChannel, Boolean>> of(final DiscordId admin) {
        final Chosen chosen = new Chosen(rows("WHERE discord_id = :discordId", admin.value()));
        final Map<AlertType, Map<AlertChannel, Boolean>> answer = new EnumMap<>(AlertType.class);
        for (final AlertType type : AlertType.values()) {
            final Map<AlertChannel, Boolean> channels = new EnumMap<>(AlertChannel.class);
            for (final AlertChannel channel : AlertChannel.values()) {
                channels.put(channel, chosen.wants(admin, type, channel));
            }
            answer.put(type, channels);
        }
        return answer;
    }

    /** Returns every admin's choices in one query, for one round of routing. */
    public Chosen all() {
        return new Chosen(rows("", null));
    }

    /** Sets one switch for one admin. */
    public void set(final DiscordId admin, final AlertType type, final AlertChannel channel, final boolean enabled) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO steward_alert_preference (discord_id, alert_type, channel, enabled, updated_at)
                        VALUES (:discordId, :type, :channel, :enabled, now())
                        ON CONFLICT (discord_id, alert_type, channel) DO UPDATE SET
                            enabled = excluded.enabled,
                            updated_at = now()""")
                .bind("discordId", admin.value())
                .bind("type", type.key())
                .bind("channel", channel.name())
                .bind("enabled", enabled)
                .execute());
    }

    private List<Row> rows(final String where, final @Nullable String discordId) {
        return jdbi.withHandle(handle -> {
            final var query = handle.createQuery(
                    "SELECT discord_id, alert_type, channel, enabled FROM steward_alert_preference " + where);
            if (discordId != null) {
                query.bind("discordId", discordId);
            }
            return query.map((rows, context) -> new Row(
                            rows.getString("discord_id"),
                            rows.getString("alert_type"),
                            rows.getString("channel"),
                            rows.getBoolean("enabled")))
                    .list();
        });
    }

    private record Row(String discordId, String type, String channel, boolean enabled) {}

    /** The stored choices of every admin; a type or channel this build does not know is ignored. */
    public static final class Chosen {

        private final Map<String, Boolean> switches = new HashMap<>();

        private Chosen(final List<Row> rows) {
            for (final Row row : rows) {
                final AlertType type = AlertType.of(row.type());
                final AlertChannel channel = AlertChannel.of(row.channel().toLowerCase(java.util.Locale.ROOT));
                if (type != null && channel != null) {
                    switches.put(key(row.discordId(), type, channel), row.enabled());
                }
            }
        }

        /** Returns whether {@code admin} wants {@code type} on {@code channel}. */
        public boolean wants(final DiscordId admin, final AlertType type, final AlertChannel channel) {
            return switches.getOrDefault(key(admin.value(), type, channel), type.wantedByDefault(channel));
        }

        private static String key(final String admin, final AlertType type, final AlertChannel channel) {
            return admin + "/" + type.name() + "/" + channel.name();
        }
    }
}
