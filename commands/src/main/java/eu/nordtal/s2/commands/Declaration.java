package eu.nordtal.s2.commands;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything about a command except what it does; its path is the same command on every surface.
 * {@link #irreversible()} is an obligation on the adapters, not a checked invariant.
 *
 * @param path         the command and its subcommands, e.g. {@code ["smp", "aura"]}
 * @param target       which process runs the effect
 * @param surfaces     where it can be typed
 * @param adminOnly    whether {@code discord_user.admin} is required
 * @param irreversible whether it needs a confirmation step on every surface
 * @param arguments    in order
 */
public record Declaration(
        List<String> path,
        Target target,
        Set<Surface> surfaces,
        boolean adminOnly,
        boolean irreversible,
        List<Argument> arguments) {

    public Declaration {
        path = List.copyOf(Objects.requireNonNull(path, "path"));
        Objects.requireNonNull(target, "target");
        surfaces = Set.copyOf(Objects.requireNonNull(surfaces, "surfaces"));
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));

        if (path.isEmpty()) {
            throw new IllegalArgumentException("a command needs a path");
        }
        if (path.stream().anyMatch(segment -> segment == null || segment.isBlank())) {
            throw new IllegalArgumentException("a path segment cannot be blank: " + path);
        }
        if (surfaces.isEmpty()) {
            throw new IllegalArgumentException(name(path) + " is declared on no surface, so nothing would register it");
        }

        for (int i = 0; i < arguments.size(); i++) {
            final Argument argument = arguments.get(i);
            final boolean last = i == arguments.size() - 1;
            if (argument.kind() == Argument.Kind.GREEDY_STRING && !last) {
                throw new IllegalArgumentException(name(path) + ": greedy argument '"
                        + argument.name() + "' has to be the last one, or Brigadier gives it the"
                        + " whole line and calls the next argument unexpected");
            }
            if (argument.required() && i > 0 && !arguments.get(i - 1).required()) {
                throw new IllegalArgumentException(name(path) + ": required argument '" + argument.name()
                        + "' follows an optional one, so it cannot be supplied");
            }
        }

        final long names = arguments.stream().map(Argument::name).distinct().count();
        if (names != arguments.size()) {
            throw new IllegalArgumentException(name(path) + " has two arguments with the same name");
        }
    }

    /** {@code /smp aura}, for a log line or an error message; never parsed. */
    public String name() {
        return name(path);
    }

    /** Returns what to type, with required arguments in angle brackets and optional ones in square. */
    public String usage() {
        final StringBuilder text = new StringBuilder(name());
        for (final Argument argument : arguments) {
            text.append(argument.required() ? " <" : " [")
                    .append(argument.name())
                    .append(argument.required() ? '>' : ']');
        }
        return text.toString();
    }

    /** Returns the message key saying what this command is for, derived from the path. */
    public String describeKey() {
        return "command.describe." + String.join(".", path);
    }

    /** Returns {@link #describeKey()} as a message. */
    public eu.nordtal.s2.common.message.MessageRef describe() {
        return eu.nordtal.s2.common.message.MessageRef.of(describeKey());
    }

    private static String name(final List<String> path) {
        return "/" + String.join(" ", path);
    }

    /** Returns whether this command travels through {@code command_request} when asked from that process. */
    public boolean isRemoteOn(final Target host) {
        Objects.requireNonNull(host, "host");
        return target != host;
    }
}
