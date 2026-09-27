package eu.nordtal.s2.hungergames.game;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A frozen participant must not be kicked for floating.
 *
 * A frozen player above air hovers; {@code mayfly} disables vanilla's floating kick.
 */
class FrozenPlayersAreNotKickedTest {

    private static final String MANAGER =
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/game/HungerGamesManager.java";
    private static final String FREEZE =
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/listener/FreezeListener.java";

    @Test
    void aParticipantPutOnATowerMayFlySoVanillaCannotKickThemForHovering() {
        final String body = methodBody(read(MANAGER), "private void placeOnTower(");
        assertTrue(
                body.contains("setAllowFlight(true)"),
                "placeOnTower has to grant mayfly: the freeze holds a participant above whatever is"
                        + " (or is not) under their tower, and vanilla kicks a hovering player after"
                        + " about five seconds - which the proxy answers by putting them straight"
                        + " back into it");
    }

    @Test
    void theReleaseTakesItBackAndLandsThemFirst() {
        final String body = methodBody(read(MANAGER), "private void release(");
        assertTrue(
                body.contains("setAllowFlight(false)"),
                "release has to take mayfly back - it was granted for the countdown, not for the" + " game");
        assertTrue(
                body.indexOf("setFlying(false)") >= 0
                        && body.indexOf("setFlying(false)") < body.indexOf("setAllowFlight(false)"),
                "setFlying(false) has to come first: revoking mayfly from somebody who is actually"
                        + " flying drops them, and by this point the freeze is no longer catching"
                        + " the fall");
    }

    @Test
    void theFreezeIsStillWhatMakesItNecessaryItCancelsTheFall() {
        final String source = read(FREEZE);
        assertTrue(
                source.contains("hasChangedPosition()") && source.contains("event.setTo(event.getFrom())"),
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
