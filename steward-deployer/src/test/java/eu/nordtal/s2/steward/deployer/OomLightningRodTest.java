package eu.nordtal.s2.steward.deployer;

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
 * Which container the kernel is told to take first when this host runs out of memory.
 *
 * Docker's own documentation says <em>"The OOM priority on containers isn't adjusted"</em>: the
 * global OOM killer chooses by size across the whole host, and no {@code mem_limit} on one
 * container can influence that choice. The only lever left does not decide <em>whether</em>
 * something dies, it decides <em>what first</em> - which makes it a human call and not an
 * inference from resource usage: {@code limbo} and {@code proxy}, because they hold almost nothing
 * that cannot be made again in seconds.
 *
 * The value is four characters in a YAML file with no runtime behaviour attached, in a repository
 * where nothing else reads it. Deleting it is invisible: nothing fails, nothing is logged, and the
 * only way to notice is the next OOM taking the SMP server instead.
 *
 * <b>The negative half matters as much as the positive one.</b> Asserting only that the two are
 * set would stay green if somebody helpfully gave every service the same number, which is the same
 * as giving none of them one: the kernel would be back to choosing by size.
 */
class OomLightningRodTest {

    /**
     * The order is the content; the exact number is not.
     *
     * The two standbys are the same answer again, following from the one already taken for the
     * primaries rather than a separate decision: {@code proxy-standby} and {@code limbo-standby} run
     * the same two images with the same two jobs, and they hold exactly as little - a waiting room
     * with no world worth the name and a proxy that persists nothing. What a kill costs either of
     * them is a disconnect, never data.
     *
     * And the moment they exist at all is the moment this matters most: a standby is only up
     * while its model is being replaced, so during a swap this host carries two proxies and two
     * limbos at once. If the kernel has to take something in that window, these four are still the
     * four to take - and leaving the two new ones at 0 would have made the standby pair the
     * <em>safest</em> processes on the box, quietly ranking them above the SMP world.
     */
    private static final Map<String, Boolean> EXPECTED = new LinkedHashMap<>(Map.of(
            "limbo", true,
            "proxy", true,
            "limbo-standby", true,
            "proxy-standby", true,
            "smp", false,
            "hunger-games", false,
            "postgres", false,
            "steward-worker", false));

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
        // A difference here is a second decision nobody took, and a copied number is one typo from a ranking.
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
     * The {@code oom_score_adj} of one compose service, or {@code null} when it has none.
     *
     * Read out of the text rather than by parsing YAML, for the reason every other repository
     * check here gives: the assertion is about what the file says, and a parser would also have to
     * resolve the {@code <<: *minecraft} merge keys - which would make a value inherited from the
     * anchor indistinguishable from one written on the service, and inheriting it is exactly the
     * mistake the negative half above exists to catch.
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
