package eu.nordtal.s2.commands;

/**
 * A command: its {@link Declaration}, and what it does with an effect the platform supplies.
 *
 * <h2>The type parameter is the whole design</h2>
 * {@code E} is the effect interface of the process that owns this command - {@code SmpEffects},
 * {@code HungerGamesEffects}, and so on - implemented by that plugin and by nothing else. It is what
 * lets the front half of a command live here while the back half stays bound to the JVM that can
 * actually carry it out.
 *
 * <p>The gain is not tidiness. It is that {@link #run} can be called in a test with a fake effect
 * and a fake {@link NordtalUser}, so the decisions inside a command - who may, what the argument
 * means, which message comes back - are assertable without a server. Before this module, exactly one
 * thing in the whole repository could be: {@code SmpCommand.mayUse}, which is package-visible and
 * static for precisely that reason and says so in its own javadoc.</p>
 *
 * <h2>What must not be in here</h2>
 * <ul>
 *   <li><b>A platform type.</b> This module is compiled against no platform, like {@code :common}.
 *       A {@code Player} in a signature here is a command the Discord adapter can never call.</li>
 *   <li><b>A sentence.</b> Every string a command produces is a message key rendered against the
 *       asker's locale - docs/architecture.md calls a hardcoded English string a bug rather than a
 *       shortcut, and that rule does not soften because the code moved.</li>
 *   <li><b>A blocking call.</b> {@link #run} is invoked from a Brigadier handler on the main thread
 *       and from a JDA gateway thread; both have a few milliseconds. Work that waits belongs behind
 *       the effect, which is implemented by something that knows its own scheduler.</li>
 * </ul>
 *
 * @param <E> the effect interface of the process that runs this command
 */
public interface NordtalCommand<E> {

    /** Where it lives, what it takes, who may use it. */
    Declaration declaration();

    /**
     * Do it.
     *
     * <p>Authorisation has already been checked by the caller against
     * {@link Declaration#adminOnly()} - twice, for a command that travelled: once where it was asked
     * for, so a refusal is immediate and says why, and once after the row was claimed, because the
     * admin flag can change while a row is waiting. A command does not re-check it and must not
     * assume it is the only one that did.</p>
     *
     * @param user    who asked, in which language, and where to answer
     * @param values  the arguments, already parsed and validated against the declaration
     * @param effects the process's own implementation of everything this command has to touch
     */
    void run(NordtalUser user, Values values, E effects);
}
