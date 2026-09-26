package eu.nordtal.s2.common.roster;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps a {@code payment_request} row; see {@link PersonMapper} for why it is written out. */
public final class PaymentMapper implements RowMapper<Payment> {

    @Override
    public Payment map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        // getLong answers 0 for NULL, so wasNull is what tells a missing tab apart.
        final long bunqTabId = rs.getLong("bunq_tab_id");
        // Read at once: wasNull() describes the most recent getter call.
        final boolean noTab = rs.wasNull();
        return new Payment(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getString("discord_id"),
                rs.getInt("days"),
                rs.getInt("amount_cents"),
                rs.getInt("donation_cents"),
                rs.getString("status"),
                noTab ? null : bunqTabId,
                rs.getString("share_url"),
                Objects.requireNonNull(PersonMapper.instant(rs, "created"), "created"),
                Objects.requireNonNull(PersonMapper.instant(rs, "expires"), "expires"),
                PersonMapper.instant(rs, "settled"));
    }
}
