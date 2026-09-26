package eu.nordtal.s2.smp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * That {@code /smp reload} actually reaches everything that reads the milestone track.
 *
 * <b>What the reload is for</b>
 *
 * {@code milestones.yml} is a separate, reloadable file for one stated reason: a milestone is appended and a target
 * lowered <em>mid-season</em>, and re-reading it must not cost a restart of the season. A consumer holding the
 * instance it was given at enable defeats exactly that - it goes on paying against the old targets, and the console
 * says "the milestone track was reloaded" regardless.
 *
 * <b>Why a text search, again</b>
 *
 * The same reason every wiring test here is one: what it protects is which reference a constructor is
 * <em>handed</em> during {@code onEnable}, and reaching {@code onEnable} needs a Paper server. The type of the
 * parameter already stops the plain instance being passed; what this catches is the next step, somebody capturing a
 * local and handing on {@code () -> captured}.
 *
 * Four consumers used to hold the startup instance - {@code ObjectiveEngine}, which decides what an
 * objective pays; {@code StatisticPoller}, which decides when one is reached; {@code NpcListener}; and
 * {@code BalloonListener}. It was found by review rather than by a test, and the symptom would have been a reload
 * that reports success and changes nothing that matters.
 */
class ReloadReachesTheTrackTest {

    private static final String PLUGIN = "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java";
    // SmpStart holds the start sequence SmpPlugin delegates to, so the wiring is read from both.
    private static final String START = "smp/src/main/java/eu/nordtal/s2/smp/SmpStart.java";

    @Test
    void everyConsumerReadsThroughTheField() throws IOException {
        final String source = (read(PLUGIN) + "\n" + read(START));

        for (final String consumer : List.of(
                "new ObjectiveEngine(plugin, plugin.dao, () -> plugin.track,",
                "new StatisticPoller(plugin, () -> plugin.track,",
                "new NpcListener(plugin, plugin.dao, npc, () -> plugin.track,",
                "new BalloonListener(balloons, plugin.worlds, plugin.season, () -> plugin.track,")) {
            assertTrue(
                    source.contains(consumer),
                    consumer.substring(0, consumer.indexOf('(')) + " does not read the current milestone track, so"
                            + " /smp reload would report success and leave it on the definitions the"
                            + " server started with");
        }
    }

    @Test
    void theReloadReachesTheMessages() throws IOException {
        // steward-worker sends `smp reload` and reports it applied; without these two lines that report is empty.
        final String source = (read(PLUGIN) + "\n" + read(START));
        final int start = source.indexOf("List<String> reloadTrack(");
        final int end = source.indexOf("return trackProblems;", start);
        assertTrue(start > 0 && end > start, "the reload method moved; point this test at it");
        assertTrue(
                source.substring(start, end).contains("reloadMessages();"),
                "/smp reload no longer calls reloadMessages()");
        final int from = source.indexOf("private void reloadMessages(");
        assertTrue(from > 0, "reloadMessages moved; point this test at it");
        final String reload = source.substring(from, source.indexOf("\n    }\n", from));
        assertTrue(
                reload.contains("messages.reload();"),
                "/smp reload no longer re-reads this plugin's messages, so a saved text stays unused");
        assertTrue(
                reload.contains("sharedMessages.reload();"),
                "/smp reload no longer re-reads the shared messages the command inbox answers with");
    }

    @Test
    void aLoweredTargetTakesEffectAtOnce() throws IOException {
        // An objective nobody can add to still completes on the next hand-in - that is the case the promise is for.
        final String source = (read(PLUGIN) + "\n" + read(START));

        final int applied = source.indexOf("track = candidate;");
        final int swept = source.indexOf("completeWhateverTheNewTargetsAlreadyReach();");
        assertTrue(applied > 0, "the reload no longer applies the candidate track");
        assertTrue(
                swept > 0,
                "a reload does not finish objectives its new targets have already been reached by,"
                        + " so lowering a target below the collected progress reports success and"
                        + " leaves the row open and unpaid");
        assertTrue(
                swept > applied,
                "the sweep runs before the new track is applied, so it would decide against the"
                        + " targets the reload was replacing");

        final int rows = source.indexOf("ensureRows(candidate);");
        assertTrue(
                rows > 0 && rows < swept,
                "the sweep runs before ensureRows, so it would read the old targets out of rows"
                        + " the reload has not written yet");
    }

    @Test
    void theFieldIsPublishedSafely() throws IOException {
        // reloadTrack runs off Bukkit's async executor; every reader is on the server thread and needs volatile.
        assertTrue(
                (read(PLUGIN) + "\n" + read(START)).contains("    volatile MilestoneTrack track;"),
                "SmpPlugin.track is not volatile");
    }

    @Test
    void anUnlockUsesOneSnapshot() throws IOException {
        // Three reads of the supplier could answer three different tracks, naming a milestone the state doesn't hold.
        final String source = read("smp/src/main/java/eu/nordtal/s2/smp/progress/ObjectiveEngine.java");
        final int inUnlock = source.indexOf("public void unlockMilestone(");
        assertTrue(inUnlock > 0, "unlockMilestone is gone");
        final String body = source.substring(inUnlock, source.indexOf("announceMilestone(", inUnlock));

        assertTrue(
                body.contains("final MilestoneTrack now = track.get();"),
                "unlockMilestone does not take one snapshot of the track");
        assertEquals(
                1,
                count(body, "track.get()"),
                "unlockMilestone reads the track more than once, so one unlock can be built from"
                        + " two different files");
    }

    @Test
    void baselinesBelongToTheDefinitionsTheyWereReadUnder() throws IOException {
        // A baseline pairs a raw statistic with an objective key; a reload changing what the key counts breaks that.
        final String source = read("smp/src/main/java/eu/nordtal/s2/smp/progress/StatisticPoller.java");
        assertTrue(
                source.contains("if (now != sampledUnder) {") && source.contains("baselines.clear();"),
                "the poller keeps baselines across a track change, so a widened objective credits"
                        + " everything that was already there");
    }

    @Test
    void theReloadValidatesAgainstTheRows() throws IOException {
        // TrackValidation answers "may this file replace the running one"; without a caller reload could stop progress.
        final String source = (read(PLUGIN) + "\n" + read(START));

        assertTrue(
                source.contains("TrackValidation.validate(candidate,"),
                "the reload does not validate the file against recorded progress");
        assertTrue(
                source.contains("new StoredProgress(dao.storedMilestones(), dao.storedObjectives())"),
                "the validation is asked without the rows, which is the only place the answer lives");

        // The refusal keeps the running track: `track = candidate` must sit on the branch the problems did not take.
        final int validated = source.indexOf("TrackValidation.validate(candidate,");
        final int assigned = source.indexOf("track = candidate;", validated);
        final int refused = source.indexOf("if (!problems.isEmpty()", validated);
        assertTrue(
                refused > 0 && assigned > refused,
                "the track is assigned before the refusal is decided, so a refused file would be" + " applied anyway");
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
        return joined(Files.readString(candidate.resolve(relative), StandardCharsets.UTF_8));
    }

    // palantir-java-format wraps a long call anywhere; the checks read each call as one line.
    private static String joined(final String source) {
        return source.replaceAll("\\(\\s*\\n\\s*", "(")
                .replaceAll("\\s*\\n\\s*\\.", ".")
                .replaceAll("(=|,|->)\\s*\\n\\s*", "$1 ");
    }
}
