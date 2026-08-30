package eu.nordtal.s2.common.access;

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

    /** @return whether this code can still be redeemed */
    public boolean isValid() {
        return Instant.now().isBefore(expires);
    }
}
