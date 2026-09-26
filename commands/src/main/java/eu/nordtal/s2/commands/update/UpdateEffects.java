package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import java.util.Optional;

/**
 * What {@code /update} touches, which is one table and nothing else.
 *
 * <h2>Why this is the whole interface</h2>
 * The command does not update anything and cannot: steward-worker is a different container with the
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
     * <p>The user and not a name: which surface asked and who is recorded as asking are both read
     * off {@link NordtalUser#origin()}, in one place, since 2026-09-08. The proxy handed in a fixed
     * {@code CONSOLE} for every player who typed there, and the console arrived as the literal word
     * "console" where the column means "nobody in particular".</p>
     *
     * @param kind what is being asked for
     * @param user who is asking
     * @return the row, whose id is what a surface watches
     */
    default UpdateRequest submit(final UpdateKind kind, final NordtalUser user) {
        return submit(kind, user, java.util.List.of());
    }

    /**
     * The same row, for the services it names (season-2-ops/127, season-2-ops/125).
     *
     * <p>Empty is the whole network, which is what every run was before a scope existed and what
     * {@link #submit(UpdateKind, NordtalUser)} above therefore hands in. It is <b>not</b> "no
     * services": a run for nothing at all is not something any surface offers, and reading the
     * empty list that way would turn a forgotten argument into a run that quietly does nothing.</p>
     *
     * @param services compose service names, or empty for the whole network
     */
    UpdateRequest submit(UpdateKind kind, NordtalUser user, java.util.List<String> services);

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
     * <h2>The proxy is a surface with players on it</h2>
     * Velocity executes every command it knows itself and never forwards it to a backend. So for
     * anybody <em>playing</em>, the process that serves {@code /update} is the proxy - not the
     * server they are standing on - and a proxy wired with a watcher that draws nothing left every
     * admin in the network with the acknowledgement and never the answer (2026-09-08). The Paper
     * watchers are reached by the Paper consoles alone.
     *
     * @param id   the request just written
     * @param user who to show it to
     */
    default void watch(long id, NordtalUser user) {
        // A surface with nothing to draw is the honest default rather than an abstract method
        // several processes would implement as an empty body. Today only the bot's console-less
        // effects use it, and the bot has no console.
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
