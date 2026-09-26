package eu.nordtal.s2.common.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps a {@code payment_notice} row by hand, reading {@code timestamptz} through {@link OffsetDateTime}. */
public final class PaymentNoticeMapper implements RowMapper<PaymentNotice> {

    @Override
    public PaymentNotice map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final OffsetDateTime reported =
                Objects.requireNonNull(rs.getObject("reported", OffsetDateTime.class), "reported");
        return new PaymentNotice(
                rs.getLong("bunq_payment_id"), rs.getString("reason"), rs.getString("detail"), reported.toInstant());
    }
}
