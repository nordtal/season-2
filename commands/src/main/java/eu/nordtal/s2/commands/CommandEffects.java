package eu.nordtal.s2.commands;

/**
 * The two things every process has to provide, whatever the command: somewhere to do the waiting,
 * and somewhere to report a failure.
 *
 * <h2>{@link #async} is what decides when a command is finished</h2>
 * A command's {@code run} returns at once and the work happens inside {@code async}, because every
 * local caller is on a thread that must not wait - Brigadier's, or a JDA gateway thread where an
 * interaction unacknowledged for three seconds is dead.
 *
 * <p>The far end of a travelling command is the exact opposite. {@link
 * eu.nordtal.s2.commands.remote.CommandInbox} claims a row on a worker thread, runs the command, and
 * writes the answer back into that row <em>when {@code run} returns</em> - so an effects
 * implementation that hands the work to another thread there would settle the request before the
 * command had said anything, and the asker would get an empty answer for work that was about to
 * happen. <b>Effects registered on an inbox must therefore run {@code async} inline</b>, which in
 * practice means the same class constructed with {@code Runnable::run} instead of a scheduler.</p>
 *
 * <p>That is a rule nobody would remember, so {@code CommandInbox#register} checks it: it submits a
 * no-op through {@code async} and refuses effects that have not run it by the time the call returns.
 * The failure is at startup, with a sentence, rather than an empty reply during an incident.</p>
 *
 * <h2>Which thread the work then needs is the implementation's problem</h2>
 * Deliberately. Deleting a world folder has to happen on the server's main thread; a database read
 * must not. Those are facts about the process that owns the effect, and pushing them up here would
 * be a scheduler in a module that is compiled against no platform.
 */
public interface CommandEffects {

    /**
     * Run the part that waits, somewhere it is allowed to wait.
     *
     * <p>Never optional, and never a fire-and-forget on an inbox - see the class comment.</p>
     */
    void async(Runnable work);

    /** Report a failure the way this process reports failures. The user is told separately. */
    void warn(String what, Throwable failure);
}
