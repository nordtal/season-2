package eu.nordtal.s2.smp.welcome;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The season's opening moment is switched on, and it runs late enough to be in the right language.
 *
 * <b>The two failures it exists for, and neither has a symptom</b>
 *
 * - <b>Nothing calls it.</b> A moment that happens once per player per season, on a path nobody runs twice, is the
 *   definition of a mechanism that can be complete, tested and never invoked - exactly
 *   {@code AdminOperators#refresh}, and exactly the head start, which was configured, migrated and
 *   documented and had no reader at all until it was built. Nobody reports a welcome they were never
 *   told to expect.
 *
 * - <b>It runs too early.</b> {@code PlayerLocales#of} answers English until the row lands, so a moment hung off
 *   {@code PlayerJoinEvent} is a moment in English for every German player, for the whole season, with nothing
 *   anywhere saying so. That is a mistake this module already made once - and the callback in
 *   {@code PresenceListener} is the only place in it where the language is known.
 *
 * <b>Why a text search</b>
 *
 * All three sites are a constructor call and a registration. Reaching them needs a server, a database and a player;
 * what they protect is one line each, which is what a merge drops. The same reason {@code HeadStartIsWiredTest} and
 * {@code AdminWatchWiringTest} are text searches.
 */
class SeasonWelcomeIsWiredTest {

    private static final String PLUGIN = "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java";
    // SmpStart holds the start sequence SmpPlugin delegates to, so the wiring is read from both.
    private static final String START = "smp/src/main/java/eu/nordtal/s2/smp/SmpStart.java";
    private static final String PRESENCE = "smp/src/main/java/eu/nordtal/s2/smp/player/PresenceListener.java";
    private static final String WELCOME = "smp/src/main/java/eu/nordtal/s2/smp/welcome/SeasonWelcome.java";

    @Test
    void theDeviceIsWired() {
        final String plugin = (read(PLUGIN) + "\n" + read(START));

        assertTrue(
                plugin.contains("new BukkitCinematics(")
                        || plugin.contains("new eu.nordtal.s2.papercommon.stage.BukkitCinematics("),
                "nothing builds the staging device, so nothing can run a staged moment");
        assertTrue(
                plugin.contains("registerEvents(cinematics, plugin)"),
                "the staging device is not registered as a listener, so a player who leaves or dies"
                        + " mid-staging keeps the blindness - and on a quit that means it is saved"
                        + " to disk with them");
        assertTrue(
                plugin.contains("cinematics::stop"),
                "nothing stops the stagings at disable. Paper disables plugins BEFORE it saves"
                        + " players, so a staging still running at that point writes its potion"
                        + " effect to disk on somebody who comes back unable to see");
    }

    @Test
    void theMomentIsWired() {
        final String plugin = (read(PLUGIN) + "\n" + read(START));

        assertTrue(
                plugin.contains("new SeasonWelcome(")
                        || plugin.contains("new eu.nordtal.s2.smp.welcome.SeasonWelcome("),
                "nothing builds the season's opening moment");
        assertTrue(
                plugin.contains("presence.systemLines(), presence.welcome()::onLanguageReady), plugin)"),
                "the moment is built but never handed to PresenceListener, which is the only place"
                        + " that knows when a player's language has landed");
    }

    @Test
    void itRunsAfterTheLocaleCallback() {
        final String presence = read(PRESENCE);

        final int announce = presence.indexOf("lines.announceJoin(player);");
        final int welcome = presence.indexOf("languageReady.accept(player);");
        assertTrue(
                announce > 0,
                "PresenceListener no longer announces the join line, which is the"
                        + " landmark this check hangs off - if that moved, the welcome has to move with it");
        assertTrue(
                welcome > 0,
                "nothing calls the season's opening moment. It is complete, it is tested, and it" + " never happens");
        assertTrue(
                welcome > announce,
                "the welcome is called before the join line, so a staged moment would begin while the join"
                        + " is still settling");

        // The join handler runs immediately; the callback runs once the row is back - calling it from onJoin is wrong.
        final int onJoin = presence.indexOf("public void onJoin(");
        final int loadLanguage = presence.indexOf("private void loadLanguage(");
        assertTrue(
                welcome > loadLanguage && loadLanguage > onJoin,
                "the welcome is called from the join handler rather than from loadLanguage's"
                        + " callback, so it runs before the language is known");
    }

    @Test
    void thePicturesAreVisiblyUnfinished() {
        // The sequence is art and belongs to the owner; this pins the placeholder so it fails once real glyphs arrive.
        final String welcome = read(WELCOME);

        assertTrue(
                welcome.contains("placeholder"),
                "the opening moment's frames no longer announce themselves as a placeholder. If the"
                        + " art has arrived, replace this check with one that the frames name their"
                        + " font - a nordtal: code point without one draws another font's glyph");
        assertTrue(
                welcome.contains("minecraft:blindness"),
                "the moment no longer applies blindness, which is what makes the pictures the only"
                        + " thing on the screen");
    }

    /** Anchored on the directory holding {@code settings.gradle.kts}, the way every reader here is. */
    private static String read(final String relative) {
        try {
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
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + relative, e);
        }
    }

    // palantir-java-format wraps a long call anywhere; the checks read each call as one line.
    private static String joined(final String source) {
        return source.replaceAll("\\(\\s*\\n\\s*", "(")
                .replaceAll("\\s*\\n\\s*\\.", ".")
                .replaceAll("(=|,|->)\\s*\\n\\s*", "$1 ");
    }
}
