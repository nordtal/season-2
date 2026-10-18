package eu.nordtal.season.settings.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** The commands a non-admin player may type anywhere on the network.
 * A command is allowed when an entry, such as {@code smp status}, is a prefix of it or it of the entry.
 *
 * @param entries one list of path segments per allowed command, in configured order
 */
public record CommandAllowlist(List<List<String>> entries) {

    /** Allows nothing. It is never a default, see {@code CommandFilter}. */
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

    /** Reads configured lines, normalising a leading slash, spacing and case. */
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
     * Returns whether a player may run {@code typed}. The leading slash is optional.
     *
     * @param typed the whole line, arguments included
     */
    public boolean allows(final String typed) {
        final List<String> path = segments(typed);
        if (path.isEmpty()) {
            // An empty command is left to the platform to answer.
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

    public boolean allowsRoot(final String label) {
        final List<String> path = segments(label);
        return !path.isEmpty()
                && entries.stream().anyMatch(entry -> entry.getFirst().equals(path.getFirst()));
    }

    public Set<String> roots() {
        final Set<String> roots = new LinkedHashSet<>();
        entries.forEach(entry -> roots.add(entry.getFirst()));
        return roots;
    }

    @Override
    public String toString() {
        return entries.stream()
                .map(entry -> "/" + String.join(" ", entry))
                .reduce((left, right) -> left + ", " + right)
                .orElse("(nothing)");
    }

    /**
     * Returns whether {@code line} names a command. The lines {@code "/"} and {@code "minecraft:"}
     * name none.
     */
    public static boolean names(final String line) {
        return !segments(line).isEmpty();
    }

    /** Splits a line into lowercase segments, dropping the namespace from the first segment only. */
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
        for (final String piece : text.split("\\s+", -1)) {
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
