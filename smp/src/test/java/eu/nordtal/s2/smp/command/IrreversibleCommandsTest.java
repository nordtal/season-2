package eu.nordtal.s2.smp.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the three {@code /smp} subcommands which cannot be undone still ask first.
 *
 * <h2>Why a text search, and why it is this one</h2>
 * The gate itself is ordinary code and is covered properly by {@code :commands}'
 * {@code ConfirmationsTest} - the window, the keying, the consuming, the expiry. What no unit test
 * in this module can reach is <b>whether a handler calls it</b>: building the tree needs
 * {@code io.papermc.paper.command.brigadier.Commands} and running a handler needs a
 * {@code CommandSourceStack}.
 *
 * <p>What is at stake is not symmetrical with the usual "somebody forgets a check". {@code /smp
 * farmreset now} <b>deletes a world folder</b> - it is the one command in this repository with a
 * shell test of its own, for exactly that reason ({@code entrypoint-test.sh}). A refactor that drops
 * the guard produces a command that works perfectly and destroys the farm world on a typo.</p>
 *
 * <h2>The list of what is NOT guarded is the other half</h2>
 * A flag on everything that writes is a flag nobody reads. {@code /smp aura} is left alone because
 * applying the negative is an exact undo, and {@code /smp update restart} has a confirmation of its
 * own shape - a minute of countdown every player sees, which an admin who mistyped can cancel. Both
 * are asserted here so that adding a guard to them is a deliberate act rather than a tidy-up.
 */
class IrreversibleCommandsTest {

    private static final String SOURCE =
            "smp/src/main/java/eu/nordtal/s2/smp/command/SmpCommand.java";

    /** handler method -> the command line it must confirm on. */
    private static final Map<String, String> GUARDED = Map.of(
            "handleFarmReset", "/smp farmreset now",
            "handleCompleteObjective", "/smp objective complete ",
            "handleUnlockMilestone", "/smp milestone unlock ");

    /** Handlers that must NOT confirm, and the reason each one may not. */
    private static final Map<String, String> DELIBERATELY_OPEN = Map.of(
            "handleAura", "applying the negative is an exact undo",
            "handleReload", "re-reading a file changes nothing that was not already on disk");

    @Test
    @DisplayName("every irreversible subcommand confirms before it acts")
    void theThreeThatCannotBeUndoneAsk() throws IOException {
        final String text = read();
        final List<String> unguarded = new ArrayList<>();

        for (final Map.Entry<String, String> entry : GUARDED.entrySet()) {
            final String body = bodyOf(text, entry.getKey());
            if (!body.contains("confirmed(context, \"" + entry.getValue())) {
                unguarded.add(entry.getKey() + " (expected a confirmation on \"" + entry.getValue()
                        + "\")");
            }
        }

        assertEquals(List.of(), unguarded,
                "one of the /smp subcommands that cannot be undone no longer confirms. farmreset"
                        + " deletes a world folder; the other two advance the season track and pay"
                        + " aura out, and neither can be un-paid cleanly.");
    }

    @Test
    @DisplayName("the confirmation is keyed on the arguments, not just on the subcommand")
    void thePendingConfirmationNamesItsTarget() throws IOException {
        // A pending "/smp milestone unlock ancient-debris" must not be spendable on a different
        // milestone typed thirty seconds later. Confirmations keys on the whole string, so what has
        // to hold here is that the key carries the argument at all.
        final String text = read();
        for (final String handler : List.of("handleCompleteObjective", "handleUnlockMilestone")) {
            assertTrue(bodyOf(text, handler).contains("\" + key"),
                    handler + " confirms on a command line that does not include its argument, so a"
                            + " confirmation for one key would be spent on another");
        }
    }

    @Test
    @DisplayName("the reversible subcommands are still not guarded, deliberately")
    void nothingIsGuardedByReflex() throws IOException {
        final String text = read();
        final List<String> overGuarded = new ArrayList<>();

        for (final Map.Entry<String, String> entry : DELIBERATELY_OPEN.entrySet()) {
            if (bodyOf(text, entry.getKey()).contains("confirmed(")) {
                overGuarded.add(entry.getKey() + " - " + entry.getValue());
            }
        }

        assertEquals(List.of(), overGuarded,
                "a confirmation was added to a command that does not need one. A flag on everything"
                        + " that writes trains an admin to type every command twice, which is how a"
                        + " confirmation stops being read - and this repository has exactly one"
                        + " command that deletes a world.");
    }

    /**
     * The source of one method, from its signature to the next method's.
     *
     * <p>Crude on purpose: anything cleverer would be a parser, and the property being checked is
     * "this call appears inside this method", which brace counting answers.</p>
     */
    private static String bodyOf(final String text, final String method) {
        final int start = text.indexOf("private int " + method + "(");
        assertTrue(start >= 0, method + " no longer exists in " + SOURCE
                + " - if it was renamed, this test has to move with it");

        int depth = 0;
        boolean opened = false;
        for (int at = start; at < text.length(); at++) {
            final char c = text.charAt(at);
            if (c == '{') {
                depth++;
                opened = true;
            } else if (c == '}') {
                depth--;
                if (opened && depth == 0) {
                    return text.substring(start, at + 1);
                }
            }
        }
        throw new AssertionError("could not find the end of " + method);
    }

    private static String read() throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        final Path source = candidate.resolve(SOURCE);
        assertTrue(Files.isRegularFile(source), SOURCE + " no longer exists");
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
