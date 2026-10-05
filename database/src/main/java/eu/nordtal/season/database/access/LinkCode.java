package eu.nordtal.season.database.access;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code link_code}: the code shown on the login screen, and how long it stays usable.
 *
 * @param code    what the player types into the link modal in Discord
 * @param mcUuid  the Minecraft account it was issued for
 * @param expires when it stops working
 */
public record LinkCode(String code, UUID mcUuid, Instant expires) {

    /** Returns whether this code can still be redeemed at {@code now}. */
    public boolean isValidAt(final Instant now) {
        return now.isBefore(expires);
    }
}
