package eu.nordtal.s2.commands;

/**
 * A command: its {@link Declaration}, and what it does with an effect the platform supplies.
 *
 * <p>{@code E} is the effect interface of the process that owns the command, so the decision half
 * lives here (and is testable with a fake effect and a fake {@link NordtalUser}) while the acting
 * half stays in the JVM that can carry it out. Three things must therefore not appear here:</p>
 * <ul>
 *   <li><b>A platform type.</b> This module compiles against no platform; a {@code Player} in a
 *       signature is a command the Discord adapter can never call.</li>
 *   <li><b>A sentence.</b> Every string a command produces is a message key rendered against the
 *       asker's locale.</li>
 *   <li><b>A blocking call.</b> {@link #run} is invoked from a Brigadier handler on the main thread
 *       and from a JDA gateway thread. Work that waits belongs behind the effect.</li>
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
     * {@link Declaration#adminOnly()} - twice for a command that travelled, since the admin flag can
     * change while a request row waits. A command does not re-check it.</p>
     *
     * @param user    who asked, in which language, and where to answer
     * @param values  the arguments, already parsed and validated against the declaration
     * @param effects the process's own implementation of everything this command has to touch
     */
    void run(NordtalUser user, Values values, E effects);

    /**
     * Whether the arguments are wrong in a way this command can see before doing anything.
     *
     * <p>Separate from {@link #run} because an irreversible command is confirmed before it runs;
     * every adapter asks this first, so {@code /phase set NOT_A_PHASE} is refused instead of
     * demanding a retype and only then rejecting the name.</p>
     *
     * <p>Only for what the arguments say, never for the world: "that is not a phase name" belongs
     * here, "no milestone is active" does not - the second needs the effects and can change between
     * the question and the answer.</p>
     *
     * @return a message key naming the problem, with the placeholders to render it - or empty when
     *         the arguments are usable
     */
    default java.util.Optional<java.util.Map.Entry<String, java.util.Map<String, ?>>> problem(
            final Values values) {
        return java.util.Optional.empty();
    }

    /**
     * Everything wrong with the arguments: the checks every command gets, then {@link #problem}.
     *
     * <p>A {@link Argument.Kind#CHOICE} is validated here rather than per adapter because Brigadier
     * has no enum type - both chat adapters type a choice as a plain word and would accept anything
     * typed.</p>
     */
    default java.util.Optional<java.util.Map.Entry<String, java.util.Map<String, ?>>> check(
            final Values values) {
        for (final Argument argument : declaration().arguments()) {
            if (argument.kind() != Argument.Kind.CHOICE) {
                continue;
            }
            final java.util.Optional<Object> supplied = values.raw(argument.name());
            // Values has already normalised a recognised choice, so this only asks whether it is
            // one at all - through the same match(), so the two cannot disagree.
            if (supplied.isPresent() && argument.match(String.valueOf(supplied.get())).isEmpty()) {
                return java.util.Optional.of(java.util.Map.entry("command.not-a-choice",
                        java.util.Map.of("argument", argument.name(),
                                "typed", String.valueOf(supplied.get()),
                                "choices", String.join(", ", argument.choices()))));
            }
        }
        return problem(values);
    }
}
