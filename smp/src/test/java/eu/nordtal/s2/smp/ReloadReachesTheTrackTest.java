package eu.nordtal.s2.smp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    @DisplayName("one unlock reads the track once, so its two halves cannot come from two files")
    void anUnlockUsesOneSnapshot() throws IOException {
        // Three separate reads of the supplier can answer with three different tracks, because
        // /smp reload runs on another thread. The successor written into the database would then
        // come from one file and the SeasonState built beside it from another - the row names a
        // milestone the running state does not hold as active, and progression stops.
        final String source =
                read("smp/src/main/java/eu/nordtal/s2/smp/progress/ObjectiveEngine.java");
        final int inUnlock = source.indexOf("public void unlockMilestone(");
        assertTrue(inUnlock > 0, "unlockMilestone is gone");
        final String body = source.substring(inUnlock, source.indexOf("announceMilestone(", inUnlock));

        assertTrue(body.contains("final MilestoneTrack now = track.get();"),
                "unlockMilestone does not take one snapshot of the track");
        assertEquals(1, count(body, "track.get()"),
                "unlockMilestone reads the track more than once, so one unlock can be built from"
                        + " two different files");
    }

    @Test
    @DisplayName("the statistic baselines are dropped when the track changes under them")
    void baselinesBelongToTheDefinitionsTheyWereReadUnder() throws IOException {
        // A baseline is a raw statistic value paired with an objective KEY, and a reload can change
        // what that key counts. An objective counting coal that starts counting coal and iron reads
        // far higher on the next poll, and the whole difference would be credited as progress
        // somebody just made. Nothing in the stored number says which definition produced it.
        final String source =
                read("smp/src/main/java/eu/nordtal/s2/smp/progress/StatisticPoller.java");
        assertTrue(source.contains("if (now != sampledUnder) {") && source.contains("baselines.clear();"),
                "the poller keeps baselines across a track change, so a widened objective credits"
                        + " everything that was already there");
    }

    @Test
    @DisplayName("the reload asks TrackValidation, which nothing in production used to ask")
    void theReloadValidatesAgainstTheRows() throws IOException {
        // TrackValidation answers "may this file replace the running one" - a renamed milestone key,
        // an objective that changed type with progress against it, a completed objective whose
        // target moved. It had twelve tests, two javadoc references and NO CALLER, so a reload that
        // removed the active milestone was applied, reported success and stopped progression with
        // nothing anywhere saying why.
        final String source = read(PLUGIN);

        assertTrue(source.contains("TrackValidation.validate(candidate,"),
                "the reload does not validate the file against recorded progress");
        assertTrue(source.contains("new StoredProgress(dao.storedMilestones(), dao.storedObjectives())"),
                "the validation is asked without the rows, which is the only place the answer lives");

        // And the refusal keeps the running track. `track = candidate` must sit on the branch the
        // problems did not take.
        final int validated = source.indexOf("TrackValidation.validate(candidate,");
        final int assigned = source.indexOf("track = candidate;", validated);
        final int refused = source.indexOf("if (!problems.isEmpty())", validated);
        assertTrue(refused > 0 && assigned > refused,
                "the track is assigned before the refusal is decided, so a refused file would be"
                        + " applied anyway");
    }

    private static int count(final String text, final String needle) {
        int at = 0;
        int found = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            found++;
            at += needle.length();
        }
        return found;
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
