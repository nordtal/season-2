package eu.nordtal.s2.smp.welcome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The season's opening moment is switched on, and it runs late enough to be in the right language.
 *
 * <h2>The two failures it exists for, and neither has a symptom</h2>
 * <ol>
 *   <li><b>Nothing calls it.</b> A moment that happens once per player per season, on a path nobody
 *       runs twice, is the definition of a mechanism that can be complete, tested and never invoked -
 *       exactly {@code AdminOperators#refresh} on 2026-09-04, and exactly the head start, which was
 *       configured, migrated and documented on 2026-09-01 and had no reader at all until it was
 *       built. Nobody reports a welcome they were never told to expect.</li>
 *   <li><b>It runs too early.</b> {@code PlayerLocales#of} answers English until the row lands, so a
 *       moment hung off {@code PlayerJoinEvent} is a moment in English for every German player, for
 *       the whole season, with nothing anywhere saying so. That is finding 96, which this module
 *       already made once - and the callback in {@code PresenceListener} is the only place in it
 *       where the language is known.</li>
 * </ol>
 *
 * <h2>Why a text search</h2>
 * All three sites are a constructor call and a registration. Reaching them needs a server, a
 * database and a player; what they protect is one line each, which is what a merge drops. The same
 * reason {@code HeadStartIsWiredTest} and {@code AdminWatchWiringTest} are text searches.
 */
class SeasonWelcomeIsWiredTest {

    private static final String PLUGIN = "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java";
    private static final String PRESENCE =
            "smp/src/main/java/eu/nordtal/s2/smp/player/PresenceListener.java";
    private static final String WELCOME =
            "smp/src/main/java/eu/nordtal/s2/smp/welcome/SeasonWelcome.java";

    @Test
    @DisplayName("the staging device is built, registered and stopped again")
    void theDeviceIsWired() {
        final String plugin = read(PLUGIN);

        assertTrue(plugin.contains("new BukkitCinematics(")
                        || plugin.contains("new eu.nordtal.s2.papercommon.stage.BukkitCinematics("),
                "nothing builds the staging device, so nothing can run a staged moment");
        assertTrue(plugin.contains("registerEvents(cinematics, this)"),
                "the staging device is not registered as a listener, so a player who leaves or dies"
                        + " mid-staging keeps the blindness - and on a quit that means it is saved"
                        + " to disk with them");
        assertTrue(plugin.contains("cinematics::stop"),
                "nothing stops the stagings at disable. Paper disables plugins BEFORE it saves"
                        + " players, so a staging still running at that point writes its potion"
                        + " effect to disk on somebody who comes back unable to see");
    }

    @Test
    @DisplayName("the moment itself is built and handed to the listener that can call it")
    void theMomentIsWired() {
        final String plugin = read(PLUGIN);

        assertTrue(plugin.contains("new SeasonWelcome(")
                        || plugin.contains("new eu.nordtal.s2.smp.welcome.SeasonWelcome("),
                "nothing builds the season's opening moment");
        assertTrue(plugin.contains("systemLines, welcome), this)"),
                "the moment is built but never handed to PresenceListener, which is the only place"
                        + " that knows when a player's language has landed");
    }

    @Test
    @DisplayName("it runs after the language, in the callback and not in the join handler")
    void itRunsAfterTheLocaleCallback() {
        final String presence = read(PRESENCE);

        final int announce = presence.indexOf("lines.announceJoin(player);");
        final int welcome = presence.indexOf("welcome.onLanguageReady(player);");
        assertTrue(announce > 0, "PresenceListener no longer announces the join line, which is the"
                + " landmark this check hangs off - if that moved, the welcome has to move with it");
        assertTrue(welcome > 0,
                "nothing calls the season's opening moment. It is complete, it is tested, and it"
                        + " never happens");
        assertTrue(welcome > announce,
                "the welcome is called before the join line, which means it is no longer inside the"
                        + " callback that waits for the player's language - and the subtitle would"
                        + " be English for everybody (finding 96, in this module, again)");

        // The join handler is what runs immediately; the callback is what runs once the row is
        // back. A call from onJoin would compile, work, and be wrong in exactly one invisible way.
        final int onJoin = presence.indexOf("public void onJoin(");
        final int loadLanguage = presence.indexOf("private void loadLanguage(");
        assertTrue(welcome > loadLanguage && loadLanguage > onJoin,
                "the welcome is called from the join handler rather than from loadLanguage's"
                        + " callback, so it runs before the language is known");
    }

    @Test
    @DisplayName("the pictures are a placeholder and say so")
    void thePicturesAreVisiblyUnfinished() {
        // The sequence is art and belongs to the owner (todo.md A10). A placeholder that looks
        // finished is a placeholder that ships, so this pins that whatever stands there names
        // itself - and it will fail the day the real glyphs arrive, which is the moment somebody
        // should be reading this file anyway.
        final String welcome = read(WELCOME);

        assertTrue(welcome.contains("placeholder"),
                "the opening moment's frames no longer announce themselves as a placeholder. If the"
                        + " art has arrived, replace this check with one that the frames name their"
                        + " font - a nordtal: code point without one draws another font's glyph");
        assertTrue(welcome.contains("minecraft:blindness"),
                "the moment no longer applies blindness, which is what makes the pictures the only"
                        + " thing on the screen");
    }

    /** Anchored on the directory holding {@code settings.gradle.kts}, the way every reader here is. */
    private static String read(final String relative) {
        try {
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null
                    && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                candidate = candidate.getParent();
            }
            if (candidate == null) {
                throw new IllegalStateException("no settings.gradle.kts above the working directory");
            }
            final Path source = candidate.resolve(relative);
            assertTrue(Files.isRegularFile(source), relative + " no longer exists");
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + relative, e);
        }
    }
}
