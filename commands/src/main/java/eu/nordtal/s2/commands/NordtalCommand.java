package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;

/**
 * A command: its {@link Declaration}, and what it does with an effect the platform supplies.
 * It holds no platform type, no sentence and no blocking call.
 *
 * @param <E> the effect interface of the process that runs this command
 */
public interface NordtalCommand<E> {

    /** Where it lives, what it takes, who may use it. */
    Declaration declaration();

    /**
     * Runs the command; the caller has already checked {@link Declaration#adminOnly()}.
     *
     * @param user    who asked, in which language, and where to answer
     * @param values  the arguments, already parsed and validated against the declaration
     * @param effects the process's own implementation of everything this command has to touch
     */
    void run(NordtalUser user, Values values, E effects);

    /**
     * Returns what is wrong with the arguments alone, asked before any confirmation.
     *
     * @return the message naming the problem, or empty when the arguments are usable
     */
    default java.util.Optional<MessageRef> problem(final Values values) {
        return java.util.Optional.empty();
    }

    /** Returns everything wrong with the arguments: an undeclared choice, then {@link #problem}. */
    default java.util.Optional<MessageRef> check(final Values values) {
        for (final Argument argument : declaration().arguments()) {
            if (argument.kind() != Argument.Kind.CHOICE) {
                continue;
            }
            final java.util.Optional<Object> supplied = values.raw(argument.name());
            // Values has already normalised a recognised choice, so this only asks whether it is one.
            if (supplied.isPresent()
                    && argument.match(String.valueOf(supplied.get())).isEmpty()) {
                return java.util.Optional.of(CommandMessages.MESSAGES
                        .command()
                        .notAChoice(
                                String.valueOf(supplied.get()),
                                argument.name(),
                                String.join(", ", argument.choices())));
            }
        }
        return problem(values);
    }
}
