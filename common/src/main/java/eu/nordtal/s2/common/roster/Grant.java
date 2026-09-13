package eu.nordtal.s2.common.roster;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code access_grant}, as a list shows it.
 *
 * <p>It is a near twin of {@code eu.nordtal.s2.common.access.AccessGrant} and that is on purpose:
 * this package is the read-only half of the schema and everything on it is a plain JDK type that
 * serialises to JSON without an adapter. {@code AccessGrant} carries {@code source} as the
 * {@code AccessSource} enum and would throw on a value nobody has taught it; a list must be able
 * to show a row it does not recognise rather than fail to draw the page it is on. That is the only
 * difference, and it is the reason there are two records.
 *
 * @param validFrom        when the period starts - {@code max(now, current valid_until)} at the
 *                         moment it was written, because grants are appended and never extended
 * @param source           {@code PURCHASE} or {@code ADMIN}
 * @param paymentRequestId the request that paid for it, {@code null} for an admin grant (and also
 *                         {@code null} once that request has been deleted - the foreign key is
 *                         {@code ON DELETE SET NULL})
 * @param revoked          when an admin revoked it, {@code null} while it counts. A revoked grant
 *                         keeps its window and stops counting
 */
public record Grant(
        UUID id,
        String discordId,
        Instant validFrom,
        Instant validUntil,
        String source,
        UUID paymentRequestId,
        Instant revoked,
        Instant created) {
}
