package eu.nordtal.s2.commands;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything about a command except what it does: its path, arguments, permission, surfaces and target.
 *
 * The path is the command on every surface: {@code ["smp", "aura"]} is {@code /smp aura} in chat
 * and in Discord alike, grouped by target rather than flattened so that somebody who knows one
 * surface knows the other and the target is visible in what they typed. The adapters do not get to
 * rename anything; a command with two names is a command people report bugs about twice.
 *
 * The invariants below each guard something that fails late and quietly otherwise: a greedy
 * argument that is not last (Brigadier hands it the whole remainder and then calls the next
 * argument unexpected - it parses, it just never works), a required argument after an optional one
 * (there is no way to supply the second without the first), two arguments with one name (Brigadier
 * takes the last silently), and no surface at all (nothing would register the command, or say so).
 *
 * Two things this record carries but cannot enforce. {@link #irreversible()} is an obligation on
 * the adapters, not a checked invariant: every surface confirms an irreversible command - a second
 * command inside a short window in chat, a button in Discord. The flag lives here so that "which
 * commands are dangerous" is one list rather than two, and so neither adapter has to keep its own;
 * whether an adapter honours it is a property of that adapter, and belongs in that adapter's test.
 *
 * {@link Surface#DISCORD} on a command whose {@link #target()} is not {@link Target#BOT} is
 * likewise a claim about wiring: it only works if something is carrying request rows to that
 * target. A declaration cannot see whether it is, which is exactly why it is stated here instead of
 * being checked and forgotten.
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

    /** {@code /smp aura}, for a log line or an error message. Never parsed. */
    public String name() {
        return name(path);
    }

    /**
     * {@code /smp aura <player> <delta>} - what to type, with the arguments named.
     *
     * Derived rather than written out per surface: a usage line kept by hand next to a command is the
     * first thing to go stale when an argument is added, and the way it goes stale is that it keeps
     * telling people to type something that no longer parses. Deriving it means the two cannot
     * disagree.
     *
     * Angle brackets for required, square for optional - the convention every Minecraft server
     * and every man page already uses, so it needs no explaining.
     */
    public String usage() {
        final StringBuilder text = new StringBuilder(name());
        for (final Argument argument : arguments) {
            text.append(argument.required() ? " <" : " [")
                    .append(argument.name())
                    .append(argument.required() ? '>' : ']');
        }
        return text.toString();
    }

    /**
     * The message key for one sentence saying what this command is for.
     *
     * Derived from the path, so every command has one and no command can have two.
     * {@code CatalogueTest} asserts that every declaration's key exists in both languages - which is
     * what makes it safe for the help output to name it without checking, and what stops a new
     * command from shipping with the literal string {@code command.describe.smp.aura} as its own
     * explanation.
     */
    public String describeKey() {
        return "command.describe." + String.join(".", path);
    }

    /**
     * {@link #describeKey()} as a message.
     *
     * One of the few keys still built from parts: the set of commands is open, so the spec lists
     * the keys and this names one of them.
     */
    public eu.nordtal.s2.common.message.MessageRef describe() {
        return eu.nordtal.s2.common.message.MessageRef.of(describeKey());
    }

    private static String name(final List<String> path) {
        return "/" + String.join(" ", path);
    }

    /**
     * Whether this command has to travel through {@code command_request} to reach its target.
     *
     * The question takes the asking <em>process</em> and not the {@link Surface}, because a
     * surface does not identify one: {@link Surface#GAME} is four different processes, and
     * {@code /hg start} is local on the hunger games server and remote from the SMP's chat. Asking
     * by surface would have quietly answered "local" for both.
     */
    public boolean isRemoteOn(final Target host) {
        Objects.requireNonNull(host, "host");
        // Target.LOCAL is never remote: its effect is a row in a table every process already has a pool for.
        return target != Target.LOCAL && target != host;
    }
}
