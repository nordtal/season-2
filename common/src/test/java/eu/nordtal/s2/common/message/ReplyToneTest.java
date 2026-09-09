package eu.nordtal.s2.common.message;

import eu.nordtal.s2.common.RepositoryRoot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every reply a command sends names a {@link Tone}, and the exceptions are named here.
 *
 * <h2>Why this is a text search and not a signature</h2>
 * Because the compiler cannot make it one. {@code NordtalUser#reply} has to keep the two-argument
 * overload - {@code RemoteUser}, {@code ConsoleUser} and the Discord adapter all implement it, and
 * the {@link Tone} overloads default onto it - so a call that names no tone compiles, runs, and
 * produces a line in whatever colour the client happened to be using. There is no version of that
 * failure a running server shows you: the sentence is right, the language is right, and the only
 * thing missing is the one signal that tells a refusal from a confirmation at a glance.
 *
 * <p>It is the same shape as {@code OneMessageFormatTest} and {@code SoundVocabularyTest}, and for
 * the same reason both of those give: written while the answer is complete, an allowlist is a list
 * of decisions; written afterwards, it is an argument.</p>
 *
 * <h2>What it does not check</h2>
 * <b>Whether the tone is the right one.</b> Nothing can: {@code Tone.GOOD} on a failure compiles
 * and reads perfectly well. What this catches is the case that actually happens - a reply added to
 * an existing command, copied from the line above it, with the tone left off.
 *
 * <p>{@code discord-bot} is deliberately not walked. Discord ignores a tone (an embed has one
 * colour for the whole of it), so a tone there would be ceremony, and requiring one would teach the
 * next reader that a tone is paperwork rather than a colour somebody sees.</p>
 */
class ReplyToneTest {

    /** Every source tree that can send a reply through {@code NordtalUser}. */
    private static final List<String> SOURCE_ROOTS = List.of(
            "commands/src/main/java",
            "paper-common/src/main/java",
            "network-control/src/main/java",
            "hunger-games/src/main/java",
            "smp/src/main/java",
            "limbo/src/main/java");

    /**
     * The files that may send a reply without naming a tone, and why.
     *
     * <p>Both entries are cases where naming one here would be <em>wrong</em> rather than merely
     * unnecessary. Keep it that way: an entry added because a tone was hard to choose is an entry
     * that makes the next one easy to add.</p>
     */
    private static final Map<String, String> ALLOWED = Map.of(
            "commands/src/main/java/eu/nordtal/s2/commands/update/UpdateFollower.java",
            "it forwards the tone the updater's own report put on each line - it does not pick one",
            "commands/src/main/java/eu/nordtal/s2/commands/NordtalUser.java",
            "the default overloads, which are what every other call site is measured against");

    /** {@code .reply(} and everything up to its closing bracket. */
    private static final Pattern CALL = Pattern.compile("\\.reply\\s*\\(");

    @Test
    @DisplayName("every reply names a tone, so a refusal is never the colour of a confirmation")
    void everyReplyNamesATone() {
        final List<String> offenders = new ArrayList<>();
        for (final Path source : sources()) {
            final String text = read(source);
            final String name = RepositoryRoot.relative(source);
            if (ALLOWED.containsKey(name)) {
                continue;
            }
            final Matcher matcher = CALL.matcher(text);
            while (matcher.find()) {
                final String call = arguments(text, matcher.end() - 1);
                if (!call.contains("Tone.")) {
                    offenders.add(name + ":" + (text.substring(0, matcher.start()).chars()
                            .filter(c -> c == '\n').count() + 1));
                }
            }
        }
        assertEquals(List.of(), offenders,
                "a reply with no tone is drawn in whatever colour the client was already using -"
                        + " add one of Tone's five, or add the file to ALLOWED with the reason it"
                        + " cannot have one");
    }

    @Test
    @DisplayName("the allowlist names files that exist")
    void theAllowlistIsNotStale() {
        for (final String name : ALLOWED.keySet()) {
            assertTrue(Files.isRegularFile(RepositoryRoot.resolve(name)),
                    name + " is allowed to send an untoned reply and does not exist - a stale"
                            + " allowlist entry is a hole nobody can see");
        }
    }

    /** @return the text between {@code (} at {@code open} and its matching {@code )} */
    private static String arguments(final String text, final int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(open, i + 1);
                }
            }
        }
        return text.substring(open);
    }

    private static List<Path> sources() {
        final List<Path> found = new ArrayList<>();
        for (final String root : SOURCE_ROOTS) {
            final Path directory = RepositoryRoot.resolve(root);
            if (!Files.isDirectory(directory)) {
                throw new IllegalStateException(root + " is not a directory - the roots have moved");
            }
            try (Stream<Path> tree = Files.walk(directory)) {
                tree.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .forEach(found::add);
            } catch (final IOException e) {
                throw new UncheckedIOException("cannot walk " + root, e);
            }
        }
        return found;
    }

    private static String read(final Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + source, e);
        }
    }
}
