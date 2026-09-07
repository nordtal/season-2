package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;

import java.util.Optional;

/**
 * What {@code /update} touches, which is one table and nothing else.
 *
 * <h2>Why this is the whole interface</h2>
 * The command does not update anything and cannot: the updater is a different container with the
 * volumes mounted. What every surface actually does is <b>write a row and read the answer back</b> -
 * and every one of the five processes already has that pool open, which is why the declaration is
 * {@link eu.nordtal.s2.commands.Target#LOCAL} and never travels.
 *
 * <p>Everything a surface does <em>around</em> that stays with the surface: Discord's live embed and
 * its buttons, the game's chat lines. Those are drawings of the same row, not decisions about it.</p>
 */
public interface UpdateEffects extends CommandEffects {

    /**
     * Writes the request.
     *
     * @param kind      what is being asked for
     * @param requester the Discord id or Minecraft name to record, or {@code null} for the console
     * @return the row, whose id is what a surface watches
     */
    UpdateRequest submit(UpdateKind kind, String requester);

    /** @return the row, if it is still there */
    Optional<UpdateRequest> find(long id);

    /**
     * Follow this request and show its answer, the way this surface shows things.
     *
     * <h2>Why a surface hook sits in an effects interface</h2>
     * Because the answer to {@code /update} is not a sentence - it is a row that keeps changing for
     * minutes, and every surface draws that differently: Discord edits one embed into a field per
     * service, a chat window prints the report when it lands, a console prints nothing extra
     * because it already has the log. None of that is a decision, so none of it belongs in the
     * command; all of it is bound to the process, which is what this interface is for.
     *
     * @param id   the request just written
     * @param user who to show it to
     */
    default void watch(long id, eu.nordtal.s2.commands.NordtalUser user) {
        // A surface with nothing to draw - the console - is the honest default rather than an
        // abstract method three processes would implement as an empty body.
    }

    /**
     * Withdraws the countdown that is running, if one still is.
     *
     * @param reason what goes into {@code result}, naming who stopped it
     * @return the cancelled row, or empty when it was already claimed - which is exactly the
     *         sentence the asker needs, and is why this answers a value rather than a boolean
     */
    Optional<UpdateRequest> cancel(String reason);
}
