package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReleaseTest {

    @Test
    void theAgentsOwnReleaseRunsHereWithOrWithoutItsV() {
        assertEquals(Release.Standing.OWN, Release.of("v0.10.3", "0.10.3"));
        assertEquals(Release.Standing.OWN, Release.of("0.10.3", "0.10.3"));
    }

    /** Compares numbers and not text: 0.10.0 is after 0.9.9. */
    @Test
    void aLaterReleaseIsNewerByNumberNotByText() {
        assertEquals(Release.Standing.NEWER, Release.of("v0.10.0", "0.9.9"));
        assertEquals(Release.Standing.NEWER, Release.of("v1.0.1", "1.0.0"));
        assertEquals(Release.Standing.OLDER, Release.of("v0.9.9", "0.10.0"));
    }

    @Test
    void anUnresolvedReleaseOrAnUnknownAgentRunsHere() {
        assertEquals(Release.Standing.OWN, Release.of(null, "0.10.3"));
        assertEquals(Release.Standing.OWN, Release.of("v0.10.3", null));
    }

    /** The newest published release runs, and a name nothing can order is still handed over. */
    @Test
    void aReleaseNothingCanOrderIsHandedOver() {
        assertEquals(Release.Standing.NEWER, Release.of("v1.0.0-rc1", "0.10.3"));
    }

    /** A resolve step names the release only when it differs from the agent's, and both without a v. */
    @Test
    void theReleaseIsAChangeOnlyWhenItIsNotTheAgentsOwn() {
        assertEquals(Optional.empty(), Release.changed("v0.55.0", "0.55.0"));
        assertEquals(Optional.empty(), Release.changed(null, "0.55.0"));
        assertEquals(Optional.of("0.56.0"), Release.changed("v0.56.0", "0.55.0"));
    }
}
