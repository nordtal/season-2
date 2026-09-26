package eu.nordtal.s2.steward.worker.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two sentences a person actually reads, and the rule that decides which one they get.
 *
 * <h2>Why a string is worth a test</h2>
 * Neither of these is a log line: they are notes in the update report that goes into a Discord
 * embed and into {@code /smp update}'s output, and they are the <em>only</em> thing standing
 * between "nobody could look at this image" and "this image is current". Both sentences described
 * the management panel this project removed on 2026-09-13 - one of them told the reader to go and
 * turn on a setting in it, which is advice about a program that is no longer installed. A wrong
 * sentence here is not cosmetic; it is finding A24 wearing different words.
 */
class ImageResultTest {

    @Test
    @DisplayName("an unverifiable image is named, and the note says why it could not be compared")
    void anUnverifiableImageIsNamed() {
        final ImageResult result = ImageResult.of(
                Map.of("smp", ImageResult.State.UP_TO_DATE, "steward-ui", ImageResult.State.UNKNOWN),
                Set.of("steward-ui"));

        final String note = result.notCheckable()
                .orElseThrow(() -> new AssertionError("an unverifiable image produced no note at all, which"
                        + " reads exactly like one that was checked and found current"));

        assertEquals(
                "The registry could not be asked about steward-ui, so nothing here can tell a"
                        + " current image from a stale one for it. A local build is told apart from this"
                        + " already, so what is left is a registry that did not answer, or an image whose"
                        + " exact identity the daemon no longer has on file. Neither of those is"
                        + " `up to date`, and a run that stayed silent about it would read exactly as if it"
                        + " had checked and found nothing to do.",
                note);

        // The sentence must not send anybody to the panel that used to hold this answer, nor blame
        // a `build:` directive, nor ask for credentials. Every image this stack runs is a public
        // ghcr.io/nordtal package that answers /distribution unauthenticated (measured 2026-09-13).
        for (final String dead : new String[] {"Arcane", "build:", "credential", "Redeploy"}) {
            assertFalse(
                    note.contains(dead),
                    "the note still mentions '" + dead + "', which is not why an image is" + " unverifiable any more: "
                            + note);
        }
    }

    @Test
    @DisplayName("one checked service silences nothingChecked, which is why notCheckable is separate")
    void oneCheckedServiceDoesNotSilenceTheOther() {
        final ImageResult result = ImageResult.of(
                Map.of("smp", ImageResult.State.UP_TO_DATE, "steward-ui", ImageResult.State.UNKNOWN),
                Set.of("steward-ui"));

        assertEquals(
                Optional.empty(),
                result.nothingChecked(),
                "something was checked, so the 'nobody looked at anything' note must stay away");
        assertTrue(
                result.notCheckable().isPresent(),
                "and the service nobody could look at still has to be named - folding these two"
                        + " together is what let four releases run behind unnoticed");
    }

    @Test
    @DisplayName("never both notes: one thing that went wrong is one sentence about it")
    void theTwoNotesAreNeverBothPresent() {
        final ImageResult result =
                ImageResult.of(Map.of("steward-ui", ImageResult.State.UNKNOWN), Set.of("steward-ui"));

        assertTrue(result.notCheckable().isPresent());
        assertEquals(
                Optional.empty(),
                result.nothingChecked(),
                "two notes about the same thing is how a report stops being read");
    }

    @Test
    @DisplayName("a run that could read no image at all says so rather than saying nothing")
    void anUnreadableRuntimeSaysSo() {
        final ImageResult result = ImageResult.unreachable("no docker socket at /var/run/docker.sock");

        assertEquals(
                "The images could not be read, so this run knows nothing about image updates:"
                        + " no docker socket at /var/run/docker.sock",
                result.nothingChecked().orElseThrow());
        assertEquals(
                Optional.empty(),
                result.notCheckable(),
                "nothing could be read, so naming individual services would be inventing them");
    }

    @Test
    @DisplayName("an empty project is 'nobody looked', never 'everything is current'")
    void anEmptyResultIsNotSilence() {
        assertEquals(
                "No service's image was compared against a registry, so this run cannot tell a"
                        + " current image from a stale one. Either the daemon listed no running container"
                        + " for this project, or no reference could be resolved.",
                ImageResult.of(Map.of()).nothingChecked().orElseThrow());
    }

    @Test
    @DisplayName("a checked, current project produces no note at all")
    void aCleanResultIsSilent() {
        final ImageResult result = ImageResult.of(Map.of("smp", ImageResult.State.UP_TO_DATE));

        assertEquals(Optional.empty(), result.nothingChecked());
        assertEquals(Optional.empty(), result.notCheckable());
        assertFalse(result.isOutdated("smp"));
        assertEquals(
                ImageResult.State.UNKNOWN,
                result.state("a-service-nobody-listed"),
                "an absent service is 'nobody looked', which is never work and never current");
    }

    @Test
    @DisplayName("a local build is named, but is not the unverifiable note's business")
    void aLocalBuildIsItsOwnNoteNotAnUnverifiableOne() {
        // steward/75: steward-ui and steward-worker on this host on 2026-09-16, both rebuilt with a
        // local `docker build` and never pushed. Neither is a question nobody could answer.
        final ImageResult result = ImageResult.of(Map.of(
                "steward-ui",
                ImageResult.State.LOCAL,
                "steward-worker",
                ImageResult.State.LOCAL,
                "smp",
                ImageResult.State.UP_TO_DATE));

        assertTrue(result.isLocal("steward-ui"));
        assertFalse(
                result.isOutdated("steward-ui"),
                "LOCAL is the opposite direction from OUTDATED, not a milder version of it");
        assertEquals(Optional.empty(), result.nothingChecked());
        assertEquals(
                Optional.empty(), result.notCheckable(), "a local build is a known answer, not an unanswered question");

        final String note =
                result.localImages().orElseThrow(() -> new AssertionError("two local builds produced no note at all"));
        assertEquals(
                "Built on this host and never published: steward-ui, steward-worker. The next"
                        + " real update run replaces them with whatever the last release actually contains,"
                        + " without asking.",
                note);
    }

    @Test
    @DisplayName("no local build means no note about one")
    void noLocalBuildMeansNoNote() {
        final ImageResult result = ImageResult.of(Map.of("smp", ImageResult.State.UP_TO_DATE));

        assertEquals(Optional.empty(), result.localImages());
    }
}
