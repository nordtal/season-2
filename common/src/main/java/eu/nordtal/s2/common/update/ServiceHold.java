package eu.nordtal.s2.common.update;

import java.time.Instant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One service that was deliberately stopped and must stay stopped (season-2-ops/125).
 *
 * <p>The reason this exists as data at all: a stopped container says nothing about <em>why</em> it
 * is stopped, and the one difference an operator needs to see at a glance is between a service
 * somebody put down on purpose and a service that fell over. That difference cannot be read from
 * the container runtime, so it is written down when the decision is made.</p>
 *
 * @param service   the compose service name, the same string {@code update_request.scope} carries
 * @param since     when the hold was written, on the database's clock
 * @param heldBy    who asked for it, in the shape {@code update_request.requested_by} uses, or
 *                  {@code null} when it was not a person
 * @param requestId the DOWN run that put it there, or {@code null} when that row has been deleted
 *                  since - the hold outliving its explanation is the safer of the two directions
 */
public record ServiceHold(
        @NotNull String service,
        @NotNull Instant since,
        @Nullable String heldBy,
        @Nullable Long requestId) {}
