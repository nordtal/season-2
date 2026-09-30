package eu.nordtal.s2.database.access;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of {@link AccessDirectory#redeemLinkCode(String, String)}; ordinary outcomes, not exceptions.
 *
 * @param mcUuid the Minecraft account that was linked, present only for {@link Status#LINKED}
 */
public record LinkRedemption(Status status, @Nullable UUID mcUuid) {

    /** Returns a freshly written link. */
    public static LinkRedemption linked(final UUID mcUuid) {
        return new LinkRedemption(Status.LINKED, Objects.requireNonNull(mcUuid, "mcUuid"));
    }

    /** Returns the outcome for a code that does not exist or has expired. */
    public static LinkRedemption invalidCode() {
        return new LinkRedemption(Status.INVALID_CODE, null);
    }

    /**
     * Returns the outcome for a valid code whose 1:1 link could not be written, because either side is already linked.
     */
    public static LinkRedemption alreadyLinked() {
        return new LinkRedemption(Status.ALREADY_LINKED, null);
    }

    /** Returns whether the link was written. */
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
