package eu.nordtal.s2.steward.deployer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which container the kernel is told to take first when this host runs out of memory.
 *
 * <h2>Why this is a decision and not a tuning number</h2>
 * On 2026-09-18 the global OOM killer took {@code nordtal-s2-smp-1}: 3 GB of anonymous RSS, the
 * largest process on the box, during a full build that saturated the machine. The obvious
 * counter-measure does not work - Docker's own documentation says <em>"The OOM priority on
 * containers isn't adjusted"</em>, and measured here all ten containers sat at
 * {@code oom_score_adj} 0 while the kill itself was {@code constraint=CONSTRAINT_NONE}, a host-wide
 * choice by size that no cgroup limit can influence. A generous {@code mem_limit} would have
 * changed nothing and a tight one would have killed the server reliably instead of by chance.
 *
 * <p>So the only lever left does not decide <em>whether</em> something dies, it decides <em>what
 * first</em> - which makes it Till's call and not an agent's. He made it on 2026-09-19
 * (season-2-ops/115): {@code limbo} and {@code proxy}, because they hold almost nothing that cannot
 * be made again in seconds.</p>
 *
 * <h2>What this test is for</h2>
 * The value is four characters in a YAML file with no runtime behaviour attached, in a repository
 * where nothing else reads it. Deleting it is invisible: nothing fails, nothing is logged, and the
 * only way to notice is the next OOM taking the SMP server again - which is the incident this was
 * written to prevent, arriving as the way to discover the setting is gone.
 *
 * <p><b>The negative half matters as much as the positive one.</b> Asserting only that the two are
 * set would stay green if somebody helpfully gave every service the same number, which is the same
 * as giving none of them one: the kernel would be back to choosing by size.</p>
 */
class OomLightningRodTest {

    /** Till's answer, 2026-09-19. The order is the content; the exact number is not. */
    private static final Map<String, Boolean> EXPECTED = new LinkedHashMap<>(Map.of(
            "limbo", true,
            "proxy", true,
            "smp", false,
            "hunger-games", false,
            "postgres", false,
            "steward-worker", false));

    private final String compose = read("compose.yml");

    @Test
    @DisplayName("limbo and the proxy are the lightning rods, and nothing else is")
    void onlyTheTwoThatHoldNothing() {
        for (final Map.Entry<String, Boolean> service : EXPECTED.entrySet()) {
            final Integer adjustment = oomScoreAdj(service.getKey());
            if (service.getValue()) {
                assertNotNull(adjustment, service.getKey() + " has no oom_score_adj. Till chose it"
                        + " as one of the two the kernel should take first (season-2-ops/115);"
                        + " without the line the kernel is back to choosing by size, which is how"
                        + " the SMP server died on 2026-09-18.");
                assertTrue(adjustment > 0, service.getKey() + " has oom_score_adj " + adjustment
                        + ", which does not make it a lightning rod. Only a POSITIVE value moves a"
                        + " process up the kernel's list.");
            } else {
                assertNull(adjustment, service.getKey() + " has an oom_score_adj of its own."
                        + " Giving every service one is the same as giving none of them one - the"
                        + " kernel goes back to choosing by size, and this service is one of the"
                        + " two that hold something which cannot simply be made again.");
            }
        }
    }

    @Test
    @DisplayName("the two carry the same number, so neither outranks the other")
    void theTwoAreEqual() {
        // Not a detail: a difference between them would be a second decision nobody took. Till
        // named two services, not an order between them.
        assertEquals(oomScoreAdj("limbo"), oomScoreAdj("proxy"),
                "limbo and proxy carry different oom_score_adj values. That is a ranking between"
                        + " the two, and nobody decided one.");
    }

    /**
     * The {@code oom_score_adj} of one compose service, or {@code null} when it has none.
     *
     * <p>Read out of the text rather than by parsing YAML, for the reason every other repository
     * check here gives: the assertion is about what the file says, and a parser would also have to
     * resolve the {@code <<: *minecraft} merge keys - which would make a value inherited from the
     * anchor indistinguishable from one written on the service, and inheriting it is exactly the
     * mistake the negative half above exists to catch.</p>
     */
    private Integer oomScoreAdj(final String service) {
        final Matcher start = Pattern.compile("^  " + Pattern.quote(service) + ":\\s*$",
                Pattern.MULTILINE).matcher(compose);
        assertTrue(start.find(), "compose.yml declares no service `" + service + "`. If it was"
                + " renamed, rename it here too: a check that cannot find its subject silently"
                + " stops running.");

        final Matcher next = Pattern.compile("^  [A-Za-z0-9][A-Za-z0-9._-]*:\\s*$",
                Pattern.MULTILINE).matcher(compose);
        final int end = next.find(start.end()) ? next.start() : compose.length();

        final Matcher value = Pattern.compile("^    oom_score_adj:\\s*(-?\\d+)\\s*$",
                Pattern.MULTILINE).matcher(compose.substring(start.end(), end));
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
