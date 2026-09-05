package eu.nordtal.s2.smp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That {@code /smp reload} actually reaches everything that reads the milestone track.
 *
 * <h2>What the reload is for</h2>
 * {@code milestones.yml} is a separate, reloadable file for one stated reason: a milestone is
 * appended and a target lowered <em>mid-season</em>, and re-reading it must not cost a restart of
 * the season. A consumer holding the instance it was given at enable defeats exactly that - it goes
 * on paying against the old targets, and the console says "the milestone track was reloaded"
 * regardless.
 *
 * <h2>Why a text search, again</h2>
 * The same reason every wiring test here is one: what it protects is which reference a constructor
 * is <em>handed</em> during {@code onEnable}, and reaching {@code onEnable} needs a Paper server.
 * The type of the parameter already stops the plain instance being passed; what this catches is the
 * next step, somebody capturing a local and handing on {@code () -> captured}.
 *
 * <p>Four consumers held the startup instance until 2026-09-05 - {@code ObjectiveEngine}, which
 * decides what an objective pays; {@code StatisticPoller}, which decides when one is reached;
 * {@code NpcListener}; and {@code BalloonListener}. It was found by review rather than by a test,
 * and the symptom would have been a reload that reports success and changes nothing that matters.
 */
class ReloadReachesTheTrackTest {

    private static final String PLUGIN = "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java";

    @Test
    @DisplayName("every track consumer is handed the live supplier, not a captured instance")
    void everyConsumerReadsThroughTheField() throws IOException {
        final String source = read(PLUGIN);

        for (final String consumer : List.of(
                "new ObjectiveEngine(this, dao, () -> track,",
                "new StatisticPoller(this, () -> track,",
                "new NpcListener(this, dao, npc, () -> track,",
                "new BalloonListener(balloons, worlds, season, () -> track,")) {
            assertTrue(source.contains(consumer),
                    consumer.split("\\(")[0] + " does not read the current milestone track, so"
                            + " /smp reload would report success and leave it on the definitions the"
                            + " server started with");
        }
    }

    @Test
    @DisplayName("the field the supplier reads is volatile, because the writer is another thread")
    void theFieldIsPublishedSafely() throws IOException {
        // reloadTrack runs on Bukkit's async executor behind /smp reload; every consumer above
        // reads on the server thread. Without volatile the supplier may go on seeing the old
        // instance for no bounded length of time, which looks identical to the bug it replaced.
        assertTrue(read(PLUGIN).contains("private volatile MilestoneTrack track;"),
                "SmpPlugin.track is not volatile");
    }

    private static String read(final String relative) throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        return Files.readString(candidate.resolve(relative), StandardCharsets.UTF_8);
    }
}
