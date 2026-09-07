package eu.nordtal.s2.hungergames.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A frozen participant must not be kicked for standing still.
 *
 * <h2>The two halves that make a loop</h2>
 * {@code FreezeListener} cancels every position change for the whole countdown, so a participant
 * placed above air does not fall - they hover. Vanilla kicks a hovering player after about five
 * seconds ("<i>was kicked for floating too long</i>" / "<i>Flying is not enabled on this server</i>"),
 * and the kick is not the end of it: the proxy puts them back on the backend, the start places them
 * on the same tower, and the five seconds run again. Measured on the local stack 2026-09-07
 * (finding 138) - both participants cycled through kick and rejoin indefinitely, and the only way
 * out was ending the phase from outside the game.
 *
 * <p>Locally the towers are missing because the arena world is in no repository. On the real arena
 * they are built - but the freeze is what turns "a tower's top block is one lower than
 * {@code spawn-tower-height}, or somebody mined it" into a failure that takes out <em>every</em>
 * participant at once, at the one moment in the season that cannot be retried.
 *
 * <h2>Why the fix is mayfly</h2>
 * {@code mayfly} is the flag vanilla's check reads, so granting it removes the failure mode rather
 * than the local instance of it. It grants no actual movement, because {@code FreezeListener}
 * cancels that for as long as the freeze lasts, and {@code release} takes it back. Building the
 * towers from the plugin was the alternative and is a much larger decision - the arena is hand-built
 * and this module places nothing.
 */
class FrozenPlayersAreNotKickedTest {

    private static final String MANAGER =
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/game/HungerGamesManager.java";
    private static final String FREEZE =
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/listener/FreezeListener.java";

    @Test
    @DisplayName("a participant put on a tower may fly, so vanilla cannot kick them for hovering")
    void theTowerGrantsFlight() {
        final String body = methodBody(read(MANAGER), "private void placeOnTower(");
        assertTrue(body.contains("setAllowFlight(true)"),
                "placeOnTower has to grant mayfly: the freeze holds a participant above whatever is"
                        + " (or is not) under their tower, and vanilla kicks a hovering player after"
                        + " about five seconds - which the proxy answers by putting them straight"
                        + " back into it");
    }

    @Test
    @DisplayName("the release takes it back, and lands them first")
    void theReleaseTakesItBack() {
        final String body = methodBody(read(MANAGER), "private void release(");
        assertTrue(body.contains("setAllowFlight(false)"),
                "release has to take mayfly back - it was granted for the countdown, not for the"
                        + " game");
        assertTrue(body.indexOf("setFlying(false)") >= 0
                        && body.indexOf("setFlying(false)") < body.indexOf("setAllowFlight(false)"),
                "setFlying(false) has to come first: revoking mayfly from somebody who is actually"
                        + " flying drops them, and by this point the freeze is no longer catching"
                        + " the fall");
    }

    @Test
    @DisplayName("the freeze is still what makes it necessary - it cancels the fall")
    void theFreezeStillHolds() {
        final String source = read(FREEZE);
        assertTrue(source.contains("hasChangedPosition()") && source.contains("event.setTo(event.getFrom())"),
                "this test's whole reason is that the freeze cancels position changes, so a"
                        + " participant above air hovers instead of falling. If that stops being"
                        + " true, re-read the two tests above rather than deleting this one");
    }

    private static String methodBody(final String source, final String signature) {
        final int start = source.indexOf(signature);
        if (start < 0) {
            throw new IllegalStateException(signature + " is gone - this test names it directly");
        }
        final int end = source.indexOf("\n    }", start);
        if (end < 0) {
            throw new IllegalStateException("no close found for " + signature);
        }
        return source.substring(start, end);
    }

    private static String read(final String relative) {
        try {
            return Files.readString(repositoryRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + relative, e);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        return candidate;
    }
}
