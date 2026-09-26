package eu.nordtal.s2.smp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * That this plugin actually wires the command layer up, and wires it up the one way that works.
 *
 * <b>Why a text search, again</b>
 *
 * The same reason every wiring test here is one: what it protects is <em>whether a call is made</em> during
 * {@code onEnable}, and reaching {@code onEnable} needs a server. The rules themselves are ordinary code and are
 * covered properly - the confirmations in {@code :commands} ' {@code ConfirmationsTest}, the six commands' decisions
 * in {@code SmpCommandsTest}, the inbox in {@code CommandInboxTest}.
 *
 * <b>The failure it exists for</b>
 *
 * {@code AdminOperators#refresh} was written, tested and called by nothing for a day, on the one question where
 * doing nothing looks identical to working. The command inbox has exactly that shape: a plugin that builds one and
 * never starts it answers no requests at all, and the only symptom is that {@code /smp} in Discord says "no answer
 * within 30 seconds" - which reads as the server being down.
 */
class SmpCommandWiringTest {

    private static final String PLUGIN = "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java";
    // SmpStart holds the start sequence SmpPlugin delegates to, so the wiring is read from both.
    private static final String START = "smp/src/main/java/eu/nordtal/s2/smp/SmpStart.java";

    @Test
    void theInboxIsWiredUp() throws IOException {
        final String source = (read(PLUGIN) + "\n" + read(START));

        assertTrue(source.contains("new PaperCommandInbox("), "no command inbox is built");
        assertTrue(
                source.contains("SmpCommands.all().forEach(command -> inbox.register("),
                "the inbox is built and no command is registered on it, so every /smp typed in"
                        + " Discord would time out as though this server were down");
        assertTrue(
                source.contains("inbox.start(plugin)"),
                "the inbox is built and filled and never started - it would claim nothing");
    }

    @Test
    void theTwoEffectsAreNotTheSameOne() {
        // CommandInbox#register refuses scheduled effects at startup; this fires on every build, the other on a server.
        final String source = readOrFail();

        assertTrue(
                source.contains("new BukkitSmpEffects(plugin, Runnable::run,"),
                "the command inbox does not have inline effects. It settles a request row when the"
                        + " command returns, so scheduled effects would write the answer before the"
                        + " command produced it.");
        assertTrue(
                source.contains("new BukkitSmpEffects(plugin, BukkitSmpEffects.async(plugin),"),
                "/smp in chat does not have async effects, so a Brigadier handler would run a"
                        + " database query on the main thread");
    }

    @Test
    void oneConnectionCarriesBothChannels() {
        // NotificationListener shares refreshes across channels without checking which woke it, so sharing is cheaper.
        final String source = readOrFail();
        final int start = source.indexOf("adminWatch.start(");
        assertTrue(start > 0, "this plugin no longer starts the admin watch at all");
        assertTrue(
                source.indexOf("inbox.refreshes()", start) > 0 && source.indexOf("inbox.channels()", start) > 0,
                "the command inbox is not on the admin watch's listener, so this plugin opens two"
                        + " dedicated LISTEN connections where one would do");
    }

    @Test
    void shutdownOrder() {
        final String source = readOrFail();
        // The method-reference form: every disable step goes through Shutdown#quietly, and the order is what matters.
        final int waiter = source.indexOf("commandWaiter::shutdownNow");
        final int pool = source.indexOf("pool::close");
        assertTrue(waiter > 0 && pool > 0, "one of the two shutdowns is missing");
        assertTrue(
                waiter < pool,
                "the pool is closed before the thread that is still polling a request row through"
                        + " it, which turns an ordinary shutdown into a stack trace");
    }

    @Test
    void theTwoThatDidNotTravel() {
        // Both are about being somewhere: one opens an inventory, the other reads a position; a Discord copy differs.
        final String source = readOrFail();
        assertTrue(source.contains("event.registrar().register(commands.navigate())"));
        assertTrue(source.contains("event.registrar().register(commands.poi())"));
        assertEquals(
                0,
                count(source, "SmpCommands.NAVIGATE"),
                "/navigate was folded into :commands, which it should not be");
    }

    @Test
    void theArgumentsThatNeedSuggesting() throws IOException {
        // Brigadier asks for suggestions per keystroke, per client; a querying source means a round trip per character.
        final String source = read("smp/src/main/java/eu/nordtal/s2/smp/command/SmpCommand.java");

        assertTrue(
                source.contains("commands.suggest(SmpCommands.UNLOCK_MILESTONE, \"key\","),
                "milestone keys are not suggested, so /smp milestone unlock is a key typed from"
                        + " memory into a command that cannot be undone");
        // Through the supplier, not a captured track: /smp reload replaces it, so a capture would offer stale keys.
        assertTrue(
                source.contains("() -> track.get().keys()"),
                "the milestone suggestions read a track captured at enable, so /smp reload would" + " not reach them");
        assertTrue(
                source.contains("commands.suggest(SmpCommands.COMPLETE_OBJECTIVE, \"key\","),
                "objective keys are not suggested");
        assertTrue(
                source.contains("season.active().objectives()"),
                "the objective suggestions do not come from the ACTIVE milestone, so they would"
                        + " offer keys the command always refuses");
    }

    private static String readOrFail() {
        try {
            return (read(PLUGIN) + "\n" + read(START));
        } catch (final IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static int count(final String text, final String needle) {
        int at = 0;
        int found = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            found++;
            at += needle.length();
        }
        return found;
    }

    private static String read(final String relative) throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        final Path source = candidate.resolve(relative);
        assertTrue(Files.isRegularFile(source), relative + " no longer exists");
        return joined(Files.readString(source, StandardCharsets.UTF_8));
    }

    // palantir-java-format wraps a long call anywhere; the checks read each call as one line.
    private static String joined(final String source) {
        return source.replaceAll("\\(\\s*\\n\\s*", "(")
                .replaceAll("\\s*\\n\\s*\\.", ".")
                .replaceAll("(=|,|->)\\s*\\n\\s*", "$1 ");
    }
}
