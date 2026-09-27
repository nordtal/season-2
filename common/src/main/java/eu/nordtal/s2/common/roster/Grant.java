package eu.nordtal.s2.common.roster;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code access_grant}, with {@code source} as text so a list can show a value no enum knows.
 *
 * @param validFrom        when the period starts; grants are appended and never extended
 * @param source           {@code PURCHASE} or {@code ADMIN}
 * @param paymentRequestId the request that paid for it, {@code null} for an admin grant or a deleted request
 * @param revoked          when an admin revoked it, {@code null} while it counts
 */
public record Grant(
        UUID id,
        String discordId,
        Instant validFrom,
        Instant validUntil,
        String source,
        @Nullable UUID paymentRequestId,
        @Nullable Instant revoked,
        Instant created) {}
