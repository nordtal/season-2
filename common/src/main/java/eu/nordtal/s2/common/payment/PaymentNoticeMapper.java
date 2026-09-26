package eu.nordtal.s2.common.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * Maps a {@code payment_notice} row, written out for the same two reasons
 * {@link PaymentRequestMapper} is: {@code ConstructorMapper} matches record components by parameter
 * name, which only survives compilation with {@code -parameters}, and {@code timestamptz} has to be
 * read through {@link OffsetDateTime} to reach an {@code Instant} without the JVM's default zone
 * getting a vote.
 */
public final class PaymentNoticeMapper implements RowMapper<PaymentNotice> {

    @Override
    public PaymentNotice map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final OffsetDateTime reported = rs.getObject("reported", OffsetDateTime.class);
        return new PaymentNotice(
                rs.getLong("bunq_payment_id"),
                rs.getString("reason"),
                rs.getString("detail"),
                reported == null ? null : reported.toInstant());
    }
}
