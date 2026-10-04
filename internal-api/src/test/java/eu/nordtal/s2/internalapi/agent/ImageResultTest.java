package eu.nordtal.s2.internalapi.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What the image comparison found, kept apart: compared and current, nobody looked, nobody could answer, built here.
 *
 * The report words each of these through its own message; this is the rule that decides which applies.
 */
class ImageResultTest {

    @Test
    void oneCheckedServiceMeansSomethingWasComparedWhichIsWhyAnUnverifiableOneIsSeparate() {
        final ImageResult result = ImageResult.of(
                Map.of("smp", ImageResult.State.UP_TO_DATE, "steward-agent", ImageResult.State.UNKNOWN),
                Set.of("steward-agent"));

        assertFalse(result.nothingCompared(), "something was checked, so 'nobody looked at anything' stays away");
        assertEquals(
                Set.of("steward-agent"),
                result.unverifiable(),
                "and the service nobody could look at still has to be named - folding these two"
                        + " together is what let four releases run behind unnoticed");
    }

    @Test
    void neverBothOneThingThatWentWrongIsOneNoteAboutIt() {
        final ImageResult result =
                ImageResult.of(Map.of("steward-agent", ImageResult.State.UNKNOWN), Set.of("steward-agent"));

        assertFalse(result.nothingCompared(), "two notes about the same thing is how a report stops being read");
    }

    @Test
    void aRunThatCouldReadNoImageAtAllIsNotTheSameAsNobodyCompared() {
        final ImageResult result = ImageResult.unreachable("no docker socket at /var/run/docker.sock");

        assertFalse(result.reached());
        assertFalse(result.nothingCompared(), "the images were never read, which has a note of its own");
        assertEquals(Set.of(), result.unverifiable(), "naming individual services would be inventing them");
        assertEquals(List.of(), result.local());
    }

    @Test
    void anEmptyProjectIsNobodyLookedNeverEverythingIsCurrent() {
        assertTrue(ImageResult.of(Map.of()).nothingCompared());
        assertTrue(ImageResult.of(Map.of("smp", ImageResult.State.UNKNOWN)).nothingCompared());
    }

    @Test
    void aCheckedCurrentProjectHasNothingToSay() {
        final ImageResult result = ImageResult.of(Map.of("smp", ImageResult.State.UP_TO_DATE));

        assertFalse(result.nothingCompared());
        assertEquals(Set.of(), result.unverifiable());
        assertEquals(List.of(), result.local());
        assertFalse(result.isOutdated("smp"));
        assertEquals(
                ImageResult.State.UNKNOWN,
                result.state("a-service-nobody-listed"),
                "an absent service is 'nobody looked', which is never work and never current");
    }

    @Test
    void aLocalBuildIsNamedSortedButIsNotAnUnansweredQuestion() {
        // A local build (rebuilt with docker build and never pushed) is not a question nobody could answer.
        final ImageResult result = ImageResult.of(Map.of(
                "steward-agent",
                ImageResult.State.LOCAL,
                "steward",
                ImageResult.State.LOCAL,
                "smp",
                ImageResult.State.UP_TO_DATE));

        assertTrue(result.isLocal("steward-agent"));
        assertFalse(
                result.isOutdated("steward-agent"),
                "LOCAL is the opposite direction from OUTDATED, not a milder version of it");
        assertFalse(result.nothingCompared());
        assertEquals(Set.of(), result.unverifiable(), "a local build is a known answer");
        assertEquals(List.of("steward", "steward-agent"), result.local());
    }
}
