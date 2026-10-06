package eu.nordtal.season.hungergames.combat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import org.junit.jupiter.api.Test;

/**
 * Checks that both sentences of a body's death are in both languages of the bundle.
 *
 * Vanilla writes no death message for an {@code EntityDeathEvent}; that {@code onMarkerDeath} announces it is
 * {@code :architecture}'s rule.
 */
class BodyDeathIsAnnouncedTest {

    @Test
    void theTwoSentencesAreTwoBecauseByNobodyIsNotASentence() {
        for (final String language : new String[] {"en", "de"}) {
            final String bundle = RepositoryRoot.read(
                    "hunger-games/src/main/resources/messages/hunger-games/" + language + ".properties");
            // Two keys, not one with an empty slot: a border death and a kill are different sentences.
            assertTrue(bundle.contains("hg.death.body="), language + " has no hg.death.body");
            assertTrue(bundle.contains("hg.death.body.by="), language + " has no hg.death.body.by");
        }
    }
}
