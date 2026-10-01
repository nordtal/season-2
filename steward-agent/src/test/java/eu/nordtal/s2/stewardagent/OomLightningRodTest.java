package eu.nordtal.s2.stewardagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The kernel's OOM killer takes limbo and proxy first, as {@code oom_score_adj} in compose.yml says.
 *
 * Nothing else reads the value, so deleting it or equalising it would only show at the next OOM.
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

    private final String compose = read("compose.yml");

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

    /**
     * Returns the {@code oom_score_adj} of one service as written in the text, or {@code null}.
     *
     * A YAML parser would resolve merge keys and hide an inherited value.
     */
    private Integer oomScoreAdj(final String service) {
        final Matcher start = Pattern.compile("^  " + Pattern.quote(service) + ":\\s*$", Pattern.MULTILINE)
                .matcher(compose);
        assertTrue(
                start.find(),
                "compose.yml declares no service `" + service + "`. If it was"
                        + " renamed, rename it here too: a check that cannot find its subject silently"
                        + " stops running.");

        final Matcher next = Pattern.compile("^  [A-Za-z0-9][A-Za-z0-9._-]*:\\s*$", Pattern.MULTILINE)
                .matcher(compose);
        final int end = next.find(start.end()) ? next.start() : compose.length();

        final Matcher value = Pattern.compile("^    oom_score_adj:\\s*(-?\\d+)\\s*$", Pattern.MULTILINE)
                .matcher(compose.substring(start.end(), end));
        return value.find() ? Integer.valueOf(value.group(1)) : null;
    }

    private static String read(final String relative) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        try {
            return Files.readString(candidate.resolve(relative), StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
