package eu.nordtal.s2.steward.worker.plan;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The two versions behind two filenames, read against each other rather than parsed out of either
 * (season-2-ops/142).
 *
 * <h2>Why a comparison and not a parse</h2>
 * Nothing in this project knows the version of an installed jar. A {@link Change} carries the
 * installed <em>filename</em> and the wanted <em>file</em>, and a version parsed out of one name on
 * its own is a guess: {@code voicechat-bukkit-2.6.24.jar} could be version {@code 2.6.24} or
 * {@code bukkit-2.6.24}, and PacketEvents publishes {@code 2.13.0+spigot} as
 * {@code packetevents-spigot-2.13.0.jar}. Two builds of the same artefact, on the other hand,
 * differ in the version and nowhere else - so the part that differs <b>is</b> the version, and
 * finding it is a comparison nobody has to trust.
 *
 * <pre>
 * voicechat-bukkit-2.6.18.jar
 * voicechat-bukkit-2.7.0.jar   ->   2.6.18 -> 2.7.0
 * </pre>
 *
 * <h2>Two ways it refuses, and both matter</h2>
 * The common prefix alone would answer {@code 5.3 -> 6.0} for the pair above, dropping the major
 * version, so both ends are walked back out of the number they landed inside.
 *
 * <p>And the names have to <b>start</b> alike. Without that rule the resource pack - whose
 * "installed" side is a SHA-1 and whose wanted side is a zip's name - comes apart into a hash and a
 * filename and is printed as if it were a version jump. A pair that shares no first character is
 * not two builds of one artefact.</p>
 *
 * <h2>Its twin in the frontend</h2>
 * {@code steward-ui/frontend/src/lib/version-jump.ts} is the same rule in TypeScript, for the
 * Available card, which reads the resolve straight from the worker's API rather than through a
 * report. The two are deliberate copies of one rule; a change to either is a change to both, and
 * both carry the {@code packetevents-spigot-2.13.0.jar} case as the proof that the walking-back
 * half is not decoration.
 *
 * @param from what is installed now
 * @param to   what the run installs
 */
public record VersionPair(@NotNull String from, @NotNull String to) {

    /** Characters a version runs through, and therefore ones a boundary must not sit inside. */
    private static boolean insideANumber(final char character) {
        return character == '.' || character == '+' || (character >= '0' && character <= '9');
    }

    /**
     * @param installed the filename on the volume, or {@code null} when nothing is installed
     * @param wanted    the filename the source published, or {@code null} when nothing resolved
     * @return the pair, or empty when these two names are not two builds of one artefact - in
     *         which case the caller keeps the filename, which is the ticket's own fallback and
     *         reads as the stopgap it is
     */
    public static @NotNull Optional<VersionPair> of(final @Nullable String installed,
                                                    final @Nullable String wanted) {
        if (installed == null || wanted == null || installed.equals(wanted)
                || installed.isEmpty() || wanted.isEmpty()) {
            return Optional.empty();
        }
        if (installed.charAt(0) != wanted.charAt(0)) {
            return Optional.empty();
        }

        int head = 0;
        while (head < installed.length() && head < wanted.length()
                && installed.charAt(head) == wanted.charAt(head)) {
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
        while (tail < installed.length() - head && tail < wanted.length() - head
                && installed.charAt(installed.length() - 1 - tail)
                        == wanted.charAt(wanted.length() - 1 - tail)) {
            tail++;
        }
        // And out of the one the suffix started inside - `.0.jar` gives back `.0`.
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

    /** What a version never starts or ends with, once the two names have been taken apart. */
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
        return character == '-' || character == '_' || character == '.' || character == '+'
                || Character.isWhitespace(character);
    }

    private static boolean hasADigit(final String value) {
        return value.chars().anyMatch(Character::isDigit);
    }
}
