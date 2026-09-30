package eu.nordtal.s2.database.payment;

import eu.nordtal.s2.common.id.DiscordId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jspecify.annotations.Nullable;

/**
 * Maps a {@code payment_request} row, reading every {@code timestamptz} through {@link OffsetDateTime}.
 * Written out, for the reason {@code MetricPointMapper} gives.
 */
public final class PaymentRequestMapper implements RowMapper<PaymentRequest> {

    @Override
    public PaymentRequest map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new PaymentRequest(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                DiscordId.of(rs.getString("discord_id")),
                rs.getInt("days"),
                rs.getInt("amount_cents"),
                rs.getInt("donation_cents"),
                PaymentRequestStatus.valueOf(rs.getString("status")),
                nullableLong(rs, "bunq_tab_id"),
                rs.getString("share_url"),
                nullableLong(rs, "bunq_payment_id"),
                Objects.requireNonNull(instant(rs, "created"), "created"),
                Objects.requireNonNull(instant(rs, "expires"), "expires"),
                instant(rs, "settled"),
                rs.getString("tab_failed"),
                instant(rs, "tab_cancelled"),
                nullableInt(rs, "matched_cents"),
                match(rs, "matched_by"));
    }

    private static @Nullable Long nullableLong(final ResultSet rs, final String column) throws SQLException {
        final long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static @Nullable Integer nullableInt(final ResultSet rs, final String column) throws SQLException {
        final int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static @Nullable PaymentMatch match(final ResultSet rs, final String column) throws SQLException {
        final String value = rs.getString(column);
        return value == null ? null : PaymentMatch.valueOf(value);
    }

    private static @Nullable Instant instant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
