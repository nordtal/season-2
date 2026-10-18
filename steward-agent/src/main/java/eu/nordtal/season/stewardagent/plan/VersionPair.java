package eu.nordtal.season.stewardagent.plan;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The versions of two filenames, found as the part where the names differ.
 *
 * @param from what is installed now
 * @param to what the run installs
 */
public record VersionPair(String from, String to) {

    private static boolean insideANumber(final char character) {
        return character == '.' || character == '+' || (character >= '0' && character <= '9');
    }

    /**
     * Compares two filenames.
     *
     * @param installed the filename on the volume, {@code null} when nothing is installed
     * @param wanted the filename the source published, {@code null} when nothing resolved
     * @return the pair, or empty when the names are not two builds of one artefact
     */
    public static Optional<VersionPair> of(final @Nullable String installed, final @Nullable String wanted) {
        if (installed == null
                || wanted == null
                || installed.equals(wanted)
                || installed.isEmpty()
                || wanted.isEmpty()) {
            return Optional.empty();
        }
        if (installed.charAt(0) != wanted.charAt(0)) {
            return Optional.empty();
        }

        int head = 0;
        while (head < installed.length() && head < wanted.length() && installed.charAt(head) == wanted.charAt(head)) {
            head++;
        }
        // Back out of any number the prefix ended inside, so `...-1.` gives the `1.` back.
        while (head > 0 && insideANumber(installed.charAt(head - 1))) {
            head--;
        }
        if (head == 0) {
            return Optional.empty();
        }

        int tail = 0;
        while (tail < installed.length() - head
                && tail < wanted.length() - head
                && installed.charAt(installed.length() - 1 - tail) == wanted.charAt(wanted.length() - 1 - tail)) {
            tail++;
        }
        // And out of the one the suffix started inside, so `.0.jar` gives back `.0`.
        while (tail > 0 && insideANumber(installed.charAt(installed.length() - tail))) {
            tail--;
        }

        final String from = trim(installed.substring(head, installed.length() - tail));
        final String to = trim(wanted.substring(head, wanted.length() - tail));
        if (from.isEmpty() || to.isEmpty() || from.equals(to)) {
            return Optional.empty();
        }
        if (!hasADigit(from) || !hasADigit(to)) {
            return Optional.empty();
        }
        return Optional.of(new VersionPair(from, to));
    }

    private static String trim(final String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isEdge(value.charAt(start))) {
            start++;
        }
        while (end > start && isEdge(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isEdge(final char character) {
        return character == '-'
                || character == '_'
                || character == '.'
                || character == '+'
                || Character.isWhitespace(character);
    }

    private static boolean hasADigit(final String value) {
        return value.chars().anyMatch(Character::isDigit);
    }
}
