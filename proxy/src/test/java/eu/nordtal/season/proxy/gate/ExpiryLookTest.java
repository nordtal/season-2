package eu.nordtal.season.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.database.access.AccessState;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** When a connected player's access is looked at next: at the warning, then at the end, never in between. */
class ExpiryLookTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final Duration LEAD = Duration.ofMinutes(5);

    private static AccessState endingIn(final Duration left) {
        return new AccessState(
                UUID.randomUUID(),
                null,
                null,
                true,
                left == null ? null : NOW.plus(left),
                false,
                false,
                false,
                0L,
                Locale.ENGLISH,
                SeasonPhase.SMP,
                null);
    }

    @Test
    void accessWithNoEndIsNeverLookedAtAgain() {
        assertEquals(Optional.empty(), ExpiryWatch.untilNextLook(endingIn(null), NOW, LEAD));
    }

    @Test
    void whileTheWarningIsAheadTheNextLookIsTheWarning() {
        assertEquals(
                Optional.of(Duration.ofMinutes(55).plus(ExpiryWatch.SLACK)),
                ExpiryWatch.untilNextLook(endingIn(Duration.ofHours(1)), NOW, LEAD));
    }

    @Test
    void onceTheWarningIsGivenTheNextLookIsTheEnd() {
        assertEquals(
                Optional.of(Duration.ofMinutes(2).plus(ExpiryWatch.SLACK)),
                ExpiryWatch.untilNextLook(endingIn(Duration.ofMinutes(2)), NOW, LEAD));
    }

    @Test
    void accessThatHasPassedLeavesTheRestToTheHubsWakeUp() {
        assertTrue(ExpiryWatch.untilNextLook(endingIn(Duration.ofSeconds(-3)), NOW, LEAD)
                .isEmpty());
    }
}
