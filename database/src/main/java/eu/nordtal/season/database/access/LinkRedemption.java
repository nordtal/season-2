package eu.nordtal.season.database.access;

import eu.nordtal.season.common.id.DiscordId;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The result of {@link AccessDirectory#redeemLinkCode(DiscordId, String)}. Ordinary results are not exceptions.
 *
 * @param mcUuid the Minecraft account that was linked, present only for {@link Status#LINKED}
 */
public record LinkRedemption(Status status, @Nullable UUID mcUuid) {

    public static LinkRedemption linked(final UUID mcUuid) {
        return new LinkRedemption(Status.LINKED, Objects.requireNonNull(mcUuid, "mcUuid"));
    }

    public static LinkRedemption invalidCode() {
        return new LinkRedemption(Status.INVALID_CODE, null);
    }

    public static LinkRedemption alreadyLinked() {
        return new LinkRedemption(Status.ALREADY_LINKED, null);
    }

    public boolean linked() {
        return status == Status.LINKED;
    }

    /** The three results of a redemption. */
    public enum Status {
        LINKED,
        INVALID_CODE,
        ALREADY_LINKED
    }
}
