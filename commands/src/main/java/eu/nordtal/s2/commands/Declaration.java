package eu.nordtal.s2.commands;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything about a command except what it does: its path, its arguments, who may use it, where it
 * can be typed, and which process runs it.
 *
 * <h2>The path is the command, on every surface</h2>
 * {@code ["smp", "aura"]} is {@code /smp aura} in chat and {@code /smp aura} in Discord, decided
 * 2026-09-04 - grouped by target rather than flattened, so that somebody who knows one surface knows
 * the other and the target is visible in what they typed. The adapters do not get to rename
 * anything; a command with two names is a command people report bugs about twice.
 *
 * <h2>What the invariants are protecting</h2>
 * Each of the checks below is something that fails late and quietly if it is not caught here:
 *
 * <ul>
 *   <li><b>A greedy argument that is not last.</b> Brigadier hands it the whole remainder and then
 *       calls the next argument unexpected. It parses, it just never works.</li>
 *   <li><b>A required argument after an optional one.</b> There is no way to supply the second
 *       without the first, so the declaration describes a command that cannot be typed.</li>
 *   <li><b>Two arguments with one name.</b> Brigadier takes the last silently.</li>
 *   <li><b>No surface at all.</b> Nothing would register the command, and nothing would say so.</li>
 * </ul>
 *
 * <h2>Two things this record carries but cannot enforce</h2>
 * {@link #irreversible()} is an obligation on the adapters, not a checked invariant: every surface
 * confirms an irreversible command, decided 2026-09-04 - a second command inside a short window in
 * chat, a button in Discord. The flag lives here so that "which commands are dangerous" is one list
 * rather than two, and so neither adapter has to keep its own; whether an adapter honours it is a
 * property of that adapter, and belongs in that adapter's test.
 *
 * <p>{@link Surface#DISCORD} on a command whose {@link #target()} is not {@link Target#BOT} is
 * likewise a claim about wiring: it only works if something is carrying request rows to that
 * target. A declaration cannot see whether it is, which is exactly why it is stated here instead of
 * being checked and forgotten.</p>
 *
 * @param path         the command and its subcommands, e.g. {@code ["smp", "aura"]}
 * @param target       which process runs the effect
 * @param surfaces     where it can be typed
 * @param adminOnly    whether {@code discord_user.admin} is required
 * @param irreversible whether it needs a confirmation step on every surface
 * @param arguments    in order
 */
public record Declaration(List<String> path, Target target, Set<Surface> surfaces,
                          boolean adminOnly, boolean irreversible, List<Argument> arguments) {

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
            throw new IllegalArgumentException(
                    name(path) + " is declared on no surface, so nothing would register it");
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
                throw new IllegalArgumentException(name(path) + ": required argument '"
                        + argument.name() + "' follows an optional one, so it cannot be supplied");
            }
        }

        final long names = arguments.stream().map(Argument::name).distinct().count();
        if (names != arguments.size()) {
            throw new IllegalArgumentException(
                    name(path) + " has two arguments with the same name");
        }
    }

    /** {@code /smp aura}, for a log line or an error message. Never parsed. */
    public String name() {
        return name(path);
    }

    private static String name(final List<String> path) {
        return "/" + String.join(" ", path);
    }

    /**
     * Whether this command has to travel through {@code command_request} to reach its target, when
     * it is asked for on {@code host}.
     *
     * <p>The question takes the asking <em>process</em> and not the {@link Surface}, because a
     * surface does not identify one: {@link Surface#GAME} is four different processes, and
     * {@code /hg start} is local on the hunger games server and remote from the SMP's chat. Asking
     * by surface would have quietly answered "local" for both.</p>
     */
    public boolean isRemoteOn(final Target host) {
        return target != Objects.requireNonNull(host, "host");
    }
}
