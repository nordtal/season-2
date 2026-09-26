package eu.nordtal.s2.common.access;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of {@link AccessDirectory#redeemLinkCode(String, String)}; ordinary outcomes, not exceptions.
 *
 * @param mcUuid the Minecraft account that was linked, present only for {@link Status#LINKED}
 */
public record LinkRedemption(Status status, @Nullable UUID mcUuid) {

    /** @return a freshly written link */
    public static LinkRedemption linked(final UUID mcUuid) {
        return new LinkRedemption(Status.LINKED, Objects.requireNonNull(mcUuid, "mcUuid"));
    }

    /** @return the code does not exist, or has expired */
    public static LinkRedemption invalidCode() {
        return new LinkRedemption(Status.INVALID_CODE, null);
    }

    /**
     * @return the code was valid, but the 1:1 link could not be written - either this Discord
     *         account already has a different Minecraft account linked, or (in practice
     *         unreachable, since a code only exists for an unlinked account) that Minecraft
     *         account is somehow already linked to somebody else. The database constraints are
     *         what actually enforced this; this is just their result surfaced as a value.
     */
    public static LinkRedemption alreadyLinked() {
        return new LinkRedemption(Status.ALREADY_LINKED, null);
    }

    /** @return whether the link was written */
    public boolean linked() {
        return status == Status.LINKED;
    }

    /** The three things that can happen when a code is redeemed. */
    public enum Status {
        LINKED,
        INVALID_CODE,
        ALREADY_LINKED
    }
}
