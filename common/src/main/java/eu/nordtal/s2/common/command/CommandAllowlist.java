package eu.nordtal.s2.common.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Which commands a player who is not an admin may type, anywhere on the network.
 *
 * <h2>Why there is a list at all</h2>
 * Velocity's own {@code /server} is open to every player: its permission check only refuses on an
 * explicit {@code FALSE}, and nothing in this network ever set one. So anybody could type
 * {@code /server hunger-games} during the SMP phase and land there, past every routing decision the
 * proxy takes. The Paper backends have the same shape of hole from the other side - a player sees
 * {@code /me}, {@code /help}, {@code /trigger}, {@code /list}, {@code /tell} and the whole vanilla
 * completion, none of which this season has an answer for.
 *
 * <h2>What an entry is</h2>
 * A path, exactly as it is typed and without the slash: {@code smp status}, {@code hg ready},
 * {@code msg}. Not a permission node and not a regular expression - a path, because that is what a
 * player types and what both platforms hand a filter.
 *
 * <h2>The matching rule, and why it works in both directions</h2>
 * A typed command is allowed when an entry is a prefix of it <em>or</em> it is a prefix of an entry:
 *
 * <ul>
 *   <li>Entry {@code msg} allows {@code /msg Someone hello} - everything under an allowed path is
 *       allowed, because an entry names a command and not one exact invocation.</li>
 *   <li>Entry {@code smp status} allows a bare {@code /smp} - otherwise the one thing a player may
 *       ask the SMP could not be discovered, because typing half a command is how our own help
 *       output is reached. The subcommands they may <em>not</em> run are already refused by
 *       Brigadier's {@code requires}, which is the admin gate proper; this list is about what is
 *       visible and typeable at all.</li>
 * </ul>
 *
 * <h2>Case and namespaces are normalised away</h2>
 * A client can send {@code /minecraft:me} or {@code /Me}, and Bukkit resolves both. A filter that
 * compared the raw text would refuse the plain form and wave the namespaced one straight through,
 * which is the failure that looks exactly like working.
 *
 * @param entries one list of path segments per allowed command, in the order they were configured
 */
public record CommandAllowlist(List<List<String>> entries) {

    /** Nothing is allowed. Not a default anywhere - see {@code CommandFilter}. */
    public static final CommandAllowlist NOTHING = new CommandAllowlist(List.of());

    public CommandAllowlist {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries")).stream()
                .map(List::copyOf)
                .toList();
        if (entries.stream().anyMatch(List::isEmpty)) {
            throw new IllegalArgumentException("an allowlist entry with no segments would allow"
                    + " every command, which is the one value this list cannot express");
        }
    }

    /**
     * Reads configured lines into a list.
     *
     * <p>A leading slash, surrounding space, repeated spaces and letter case are all accepted and
     * normalised: this is read from a YAML file an operator edits, and refusing {@code "/msg "}
     * because of the slash would be a rule nobody can see the point of. A blank line is skipped
     * rather than refused, for the same reason.</p>
     */
    public static CommandAllowlist parse(final Collection<String> lines) {
        Objects.requireNonNull(lines, "lines");
        final List<List<String>> parsed = new ArrayList<>();
        for (final String line : lines) {
            final List<String> segments = segments(line);
            if (!segments.isEmpty()) {
                parsed.add(segments);
            }
        }
        return new CommandAllowlist(parsed);
    }

    /**
     * The list as one string, for the row the proxy publishes it in.
     *
     * <p>One entry per line, because that is the one separator a command path can never contain and
     * the one an operator reading the column by hand can see. It is deliberately not JSON:
     * {@code :common} carries no JSON library and is not gaining one for a list of words.</p>
     */
    public String serialise() {
        return entries.stream().map(entry -> String.join(" ", entry))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    /** The inverse of {@link #serialise()}. */
    public static CommandAllowlist deserialise(final String stored) {
        return stored == null || stored.isBlank()
                ? NOTHING
                : parse(List.of(stored.split("\n", -1)));
    }

    /**
     * Whether a player may run this, with the leading slash optional.
     *
     * @param typed the whole line as the platform hands it over, arguments included
     */
    public boolean allows(final String typed) {
        final List<String> path = segments(typed);
        if (path.isEmpty()) {
            // "/" on its own, or a line of spaces. Not a command; let the platform say so in its
            // own words rather than answering "that command does not exist" to an empty string.
            return true;
        }
        for (final List<String> entry : entries) {
            final int shared = Math.min(entry.size(), path.size());
            if (entry.subList(0, shared).equals(path.subList(0, shared))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether anything at all under this first segment is allowed.
     *
     * <p>This is the question the two completion filters ask, because both of them work on root
     * labels: Paper's {@code PlayerCommandSendEvent} hands over the labels of whole commands, and
     * the command tree Velocity sends a client has one child per root. Neither can hide a
     * subcommand, which is why hiding one is not this list's job - {@code requires} does that.</p>
     */
    public boolean allowsRoot(final String label) {
        final List<String> path = segments(label);
        return !path.isEmpty()
                && entries.stream().anyMatch(entry -> entry.getFirst().equals(path.getFirst()));
    }

    /** Every distinct first segment, for a log line at startup. */
    public Set<String> roots() {
        final Set<String> roots = new LinkedHashSet<>();
        entries.forEach(entry -> roots.add(entry.getFirst()));
        return roots;
    }

    /** {@code /smp status, /msg, /r} - for a log line, never parsed back. */
    @Override
    public String toString() {
        return entries.stream().map(entry -> "/" + String.join(" ", entry))
                .reduce((left, right) -> left + ", " + right)
                .orElse("(nothing)");
    }

    /**
     * {@code "/Minecraft:Me hello there"} to {@code ["me", "hello", "there"]}.
     *
     * <p>The namespace is dropped from the <b>first</b> segment only. {@code minecraft:me} and
     * {@code me} are one command and a list that spelled out both forms would be a list somebody
     * has to remember to keep in step; an argument that happens to contain a colon is not a
     * namespace and is left alone.</p>
     */
    /**
     * Whether one written line names a command at all.
     *
     * <p>Blank is not the only way to write nothing: {@code "/"} and {@code "minecraft:"} both
     * normalise to the empty path, so they pass a blank check, match no command, and sit in
     * {@code network.yml} looking like an entry that does something. The proxy refuses one at load
     * rather than starting with a list that quietly has a hole in it.</p>
     *
     * @param line one entry as an operator wrote it
     * @return {@code true} when it survives normalisation
     */
    public static boolean names(final String line) {
        return !segments(line).isEmpty();
    }

    private static List<String> segments(final String line) {
        if (line == null) {
            return List.of();
        }
        String text = line.strip();
        if (text.startsWith("/")) {
            text = text.substring(1).strip();
        }
        if (text.isEmpty()) {
            return List.of();
        }
        final List<String> segments = new ArrayList<>();
        for (final String piece : text.split("\\s+")) {
            if (!piece.isEmpty()) {
                segments.add(piece.toLowerCase(Locale.ROOT));
            }
        }
        if (!segments.isEmpty()) {
            final String first = segments.getFirst();
            final int colon = first.indexOf(':');
            if (colon >= 0) {
                segments.set(0, first.substring(colon + 1));
            }
        }
        return segments.stream().filter(segment -> !segment.isEmpty()).toList();
    }
}
