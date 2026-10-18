package eu.nordtal.season.database.access;

import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code access_grant}: a period of access with a start, an end and a reason.
 * Grants are appended, never extended. A revoked grant keeps its window but stops counting.
 *
 * @param validFrom the start of the period: {@code max(now, current valid_until)} when written
 * @param validUntil the end of the period, after {@code validFrom}
 * @param source purchase or admin grant
 * @param paymentRequestId the request that paid for it, null for an admin grant
 * @param revoked when an admin revoked it, null while it counts
 */
public record AccessGrant(
        UUID id,
        DiscordId discordId,
        Instant validFrom,
        Instant validUntil,
        AccessSource source,
        @Nullable UUID paymentRequestId,
        @Nullable Instant revoked,
        Instant created) {

    public boolean coversAt(final Instant at) {
        return revoked == null && !validFrom.isAfter(at) && validUntil.isAfter(at);
    }
}
