package eu.nordtal.s2.smp.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That this plugin actually wires the command layer up, and wires it up the one way that works.
 *
 * <h2>Why a text search, again</h2>
 * The same reason every wiring test here is one: what it protects is <em>whether a call is made</em>
 * during {@code onEnable}, and reaching {@code onEnable} needs a server. The rules themselves are
 * ordinary code and are covered properly - the confirmations in {@code :commands}'
 * {@code ConfirmationsTest}, the six commands' decisions in {@code SmpCommandsTest}, the inbox in
 * {@code CommandInboxTest}.
 *
 * <h2>The failure it exists for</h2>
 * {@code AdminOperators#refresh} was written, tested and called by nothing for a day, on the one
 * question where doing nothing looks identical to working. The command inbox has exactly that
 * shape: a plugin that builds one and never starts it answers no requests at all, and the only
 * symptom is that {@code /smp} in Discord says "no answer within 30 seconds" - which reads as the
 * server being down.
 */
class SmpCommandWiringTest {

    private static final String PLUGIN = "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java";

    @Test
    @DisplayName("the inbox is built, filled with every /smp command, and started")
    void theInboxIsWiredUp() throws IOException {
        final String source = read(PLUGIN);

        assertTrue(source.contains("new PaperCommandInbox("), "no command inbox is built");
        assertTrue(source.contains("SmpCommands.all().forEach(command -> inbox.register("),
                "the inbox is built and no command is registered on it, so every /smp typed in"
                        + " Discord would time out as though this server were down");
        assertTrue(source.contains("inbox.start(this)"),
                "the inbox is built and filled and never started - it would claim nothing");
    }

    @Test
    @DisplayName("the inbox's effects run inline and the chat ones do not")
    void theTwoEffectsAreNotTheSameOne() {
        // CommandInbox#register also refuses scheduled effects at startup, which is the real
        // guard. This one is here because that guard fires on a running server and this fires on
        // every build - and because the pair is easy to "tidy" into one field.
        final String source = readOrFail();

        assertTrue(source.contains("new BukkitSmpEffects(this, Runnable::run,"),
                "the command inbox does not have inline effects. It settles a request row when the"
                        + " command returns, so scheduled effects would write the answer before the"
                        + " command produced it.");
        assertTrue(source.contains("new BukkitSmpEffects(this, BukkitSmpEffects.async(this),"),
                "/smp in chat does not have async effects, so a Brigadier handler would run a"
                        + " database query on the main thread");
    }

    @Test
    @DisplayName("the inbox rides on the admin watch's LISTEN connection rather than opening its own")
    void oneConnectionCarriesBothChannels() {
        // NotificationListener takes several channels and several refreshes and never inspects
        // which one woke it, which is what makes sharing strictly cheaper than not.
        final String source = readOrFail();
        assertTrue(source.contains("inbox.refreshes(), inbox.channels()"),
                "the command inbox is not on the admin watch's listener, so this plugin opens two"
                        + " dedicated LISTEN connections where one would do");
    }

    @Test
    @DisplayName("the command waiter is shut down before the pool it reads through")
    void shutdownOrder() {
        final String source = readOrFail();
        final int waiter = source.indexOf("commandWaiter.shutdownNow()");
        final int pool = source.indexOf("pool.close()");
        assertTrue(waiter > 0 && pool > 0, "one of the two shutdowns is missing");
        assertTrue(waiter < pool,
                "the pool is closed before the thread that is still polling a request row through"
                        + " it, which turns an ordinary shutdown into a stack trace");
    }

    @Test
    @DisplayName("/navigate and /poi are still their own commands, deliberately")
    void theTwoThatDidNotTravel() {
        // Both are about being somewhere: one opens an inventory, the other reads the caller's
        // position. A Discord half of either would be a different command wearing the same name.
        final String source = readOrFail();
        assertTrue(source.contains("event.registrar().register(commands.navigate())"));
        assertTrue(source.contains("event.registrar().register(commands.poi())"));
        assertEquals(0, count(source, "SmpCommands.NAVIGATE"),
                "/navigate was folded into :commands, which it should not be");
    }

    private static String readOrFail() {
        try {
            return read(PLUGIN);
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
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
