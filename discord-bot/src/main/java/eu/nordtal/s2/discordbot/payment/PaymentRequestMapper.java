package eu.nordtal.s2.discordbot.payment;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps a {@code payment_request} row.
 * <p>
 * Written out rather than reached for with {@code ConstructorMapper}, for the same reason as
 * {@code AccessGrantMapper} in {@code :common}: that mapper matches record components by parameter
 * name, which only survives compilation with {@code -parameters}, and a query should not depend on
 * a build flag. It also makes the {@code timestamptz} handling explicit - every point in time is
 * read through {@link OffsetDateTime}, which is the only way to get an {@link Instant} out of the
 * PostgreSQL driver without going through the JVM's default time zone.
 * </p>
 */
public final class PaymentRequestMapper implements RowMapper<PaymentRequest> {

    @Override
    public PaymentRequest map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new PaymentRequest(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getString("discord_id"),
                rs.getInt("days"),
                rs.getInt("amount_cents"),
                rs.getInt("donation_cents"),
                PaymentRequestStatus.valueOf(rs.getString("status")),
                nullableLong(rs, "bunq_tab_id"),
                rs.getString("share_url"),
                nullableLong(rs, "bunq_payment_id"),
                instant(rs, "created"),
                instant(rs, "expires"),
                instant(rs, "settled"));
    }

    private static Long nullableLong(final ResultSet rs, final String column) throws SQLException {
        final long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
