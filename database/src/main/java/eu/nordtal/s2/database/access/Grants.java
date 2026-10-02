package eu.nordtal.s2.database.access;

import eu.nordtal.s2.common.id.DiscordId;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jspecify.annotations.Nullable;

/**
 * Appends an access period and sets the donor flag through a transaction the caller holds.
 * So a change and the access it buys commit together; {@link AccessDirectory} goes through here too.
 */
public final class Grants {

    private Grants() {}

    /**
     * Appends a period after the person's last running one, creating their {@code discord_user} row if needed.
     *
     * @param paymentRequestId the request it was bought with, {@code null} for an admin's grant
     */
    public static AccessGrant append(
            final Handle handle,
            final DiscordId discordId,
            final int days,
            final AccessSource source,
            final @Nullable UUID paymentRequestId) {
        Objects.requireNonNull(discordId, "discordId");
        Objects.requireNonNull(source, "source");
        if (days <= 0) {
            throw new IllegalArgumentException("days must be positive, got: " + days);
        }
        final AccessDao dao = handle.attach(AccessDao.class);
        dao.ensureUser(discordId);
        return dao.grantAccess(discordId, days, source.name(), paymentRequestId);
    }

    /** Marks somebody a donor, which is never taken back. */
    public static void markDonor(final Handle handle, final DiscordId discordId) {
        handle.attach(AccessDao.class).setDonor(Objects.requireNonNull(discordId, "discordId"), true);
    }
}
