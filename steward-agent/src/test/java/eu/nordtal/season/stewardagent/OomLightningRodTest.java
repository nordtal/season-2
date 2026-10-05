package eu.nordtal.season.stewardagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.ComposeFile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The kernel's OOM killer takes limbo and proxy first, as {@code oom_score_adj} in compose.yml says.
 *
 * Nothing else reads the value, so deleting it or equalising it would only show at the next OOM. A value a service
 * inherits counts as its own.
 */
class OomLightningRodTest {

    /** Which services are OOM lightning rods; the order matters, not the exact number. */
    private static final Map<String, Boolean> EXPECTED = new LinkedHashMap<>(Map.of(
            "limbo", true,
            "proxy", true,
            "limbo-standby", true,
            "proxy-standby", true,
            "smp", false,
            "hunger-games", false,
            "postgres", false,
            "steward", false));

    @Test
    void onlyTheTwoThatHoldNothing() {
        for (final Map.Entry<String, Boolean> service : EXPECTED.entrySet()) {
            final Integer adjustment = oomScoreAdj(service.getKey());
            if (service.getValue()) {
                assertNotNull(
                        adjustment,
                        service.getKey() + " has no oom_score_adj, and it is"
                                + " one of the services the kernel is supposed to take first. Without the"
                                + " line the kernel is back to choosing by size.");
                assertTrue(
                        adjustment > 0,
                        service.getKey() + " has oom_score_adj " + adjustment
                                + ", which does not make it a lightning rod. Only a POSITIVE value moves a"
                                + " process up the kernel's list.");
            } else {
                assertNull(
                        adjustment,
                        service.getKey() + " has an oom_score_adj of its own."
                                + " Giving every service one is the same as giving none of them one - the"
                                + " kernel goes back to choosing by size, and this service is one of the"
                                + " two that hold something which cannot simply be made again.");
            }
        }
    }

    @Test
    void theyAllCarryTheSameNumber() {
        // A difference here is a second decision nobody took.
        final Integer first = oomScoreAdj("limbo");
        EXPECTED.forEach((service, isLightningRod) -> {
            if (isLightningRod) {
                assertEquals(
                        first,
                        oomScoreAdj(service),
                        service + " carries a different"
                                + " oom_score_adj from limbo. That is a ranking among the services the"
                                + " kernel should take first, and nobody decided one.");
            }
        });
    }

    /** Returns the {@code oom_score_adj} one service ends up with, inherited or its own, or {@code null}. */
    private static Integer oomScoreAdj(final String service) {
        return ComposeFile.get()
                .service(service)
                .text("oom_score_adj")
                .map(Integer::valueOf)
                .orElse(null);
    }
}
