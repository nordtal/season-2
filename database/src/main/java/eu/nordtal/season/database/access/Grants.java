package eu.nordtal.season.database.access;

import eu.nordtal.season.common.id.DiscordId;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jspecify.annotations.Nullable;

/** Appends an access period and sets the donor flag in the transaction of the caller, so they commit together. */
public final class Grants {

    private Grants() {}

    /**
     * Appends a period after the last running period of the person, and creates their {@code discord_user} row.
     *
     * @param paymentRequestId the request it was bought with, null for an admin's grant
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

    /** Marks somebody as donor, which is never taken back. */
    public static void markDonor(final Handle handle, final DiscordId discordId) {
        handle.attach(AccessDao.class).setDonor(Objects.requireNonNull(discordId, "discordId"), true);
    }
}
