package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import java.util.Optional;

/**
 * What {@code /update} touches: one table and nothing else.
 *
 * steward-worker does the updating; every surface writes a row and reads the answer back, so the command never travels.
 */
public interface UpdateEffects extends CommandEffects {

    /**
     * Writes a request for the whole network, reading surface and requester off {@link NordtalUser#origin()}.
     *
     * @param kind what is being asked for
     * @param user who is asking
     * @return the row, whose id is what a surface watches
     */
    default UpdateRequest submit(final UpdateKind kind, final NordtalUser user) {
        return submit(kind, user, java.util.List.of());
    }

    /**
     * Writes the same row for the services it names; empty is the whole network, never "no services".
     *
     * @param services compose service names, or empty for the whole network
     */
    UpdateRequest submit(UpdateKind kind, NordtalUser user, java.util.List<String> services);

    /** Returns the row, if it is still there. */
    Optional<UpdateRequest> find(long id);

    /**
     * Follows this request and shows its answer, the way this surface shows things.
     * For a player that is the proxy, since Velocity never forwards a command it knows.
     *
     * @param id   the request just written
     * @param user who to show it to
     */
    default void watch(final long id, final NordtalUser user) {
        // A surface with nothing to draw is the honest default, rather than an empty body several processes repeat.
    }

    /**
     * Withdraws the countdown that is running, if one still is.
     *
     * @param reason what goes into {@code result}, naming who stopped it
     * @return the cancelled row, or empty when it was already claimed
     */
    Optional<UpdateRequest> cancel(String reason);
}
