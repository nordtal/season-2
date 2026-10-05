package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.plan.Change;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One outage produces one explanation.
 *
 * Repeating it on every row pushed the summary past the Discord description budget.
 */
class ReportTest {

    /** {@code UpdateCommand.DESCRIPTION_BUDGET}. Not imported: :steward must not depend on the bot. */
    private static final int DESCRIPTION_BUDGET = 4000;

    /** What SourceHttp actually builds for a rate-limited GitHub, body trimmed at 300. */
    private static final String GITHUB_403 =
            "HTTP 403 from https://api.github.com/repos/nordtal/season-2/releases/latest"
                    + " - rate limited. GitHub allows 60 unauthenticated requests per hour per IP;"
                    + " set github-token in the steward group if this host shares its address. Body: "
                    + "x".repeat(300);

    @Test
    void eightArtefactsBehindOneOutageStillLeaveTheSummaryInsideTheEmbedBudget() {
        final String rendered = Report.render(githubIsDown());

        assertTrue(
                rendered.length() < DESCRIPTION_BUDGET,
                "the report is " + rendered.length() + " characters, and a Discord embed keeps only"
                        + " the first " + DESCRIPTION_BUDGET + ". Everything after the rows - the"
                        + " summary and the unclaimed jars - is what gets cut.");

        assertEquals(
                1,
                occurrences(rendered, "rate limited"),
                "the same reason is printed more than once. Eight copies of it is the whole bug.");
        assertTrue(rendered.contains("[1]"), "the rows no longer reference the reason: " + rendered);
        assertTrue(rendered.contains("why:"), "the reason is not printed anywhere at all");
    }

    @Test
    void thePartsThatSayWhatToDoSurviveTheOutageTheyAreWrittenFor() {
        final String rendered = Report.render(githubIsDown());

        assertTrue(
                rendered.contains("could not be checked at all"),
                "the summary that says the list is not the whole picture is missing");
        assertTrue(rendered.contains("jars nothing accounts for"), "the unclaimed list is missing");
    }

    @Test
    void aReasonThatAppearsOnceIsPrintedWhereItHappenedNotAsAFootnote() {
        // A single occurrence as a footnote is worse than the sentence: reader has to find it, nothing to dedupe.
        final UpdatePlan plan = new UpdatePlan(
                Instant.EPOCH,
                "v0.2.1",
                false,
                List.of(Change.unresolved("smp", "packetevents", TEXTS.report().words("Modrinth answered 503"))),
                List.of(),
                List.of());

        final String rendered = Report.render(plan);
        assertTrue(rendered.contains("Modrinth answered 503"), rendered);
        assertTrue(!rendered.contains("[1]"), "a lone reason was turned into a footnote: " + rendered);
    }

    @Test
    void anArtefactWithNoBuildYetStopsTheSummaryClaimingEverythingIsUpToDate() {
        // The summary is what people read; rows are what people scan. "Up to date" above "no build yet" reads as all.
        final UpdatePlan plan = new UpdatePlan(
                Instant.EPOCH,
                "v0.7.1",
                false,
                List.of(
                        new Change("smp", "smp", Change.Status.UP_TO_DATE, "smp-0.7.1.jar", null, null),
                        Change.unsupported(
                                "smp",
                                "coreprotect",
                                TEXTS.report().words("no stable release is tagged for this platform"))),
                List.of(),
                List.of());

        final String rendered = Report.render(plan);

        assertTrue(rendered.contains("no build yet"), rendered);
        assertTrue(rendered.contains("have none yet"), rendered);
        assertTrue(!rendered.contains("Everything is up to date."), "the summary overclaims: " + rendered);
        assertTrue(
                !rendered.contains("could not be checked"),
                "an artefact with no build is not an artefact that could not be checked: " + rendered);
    }

    /** The first deployment: the GitHub API refused, so every season artefact went unresolved. */
    private static UpdatePlan githubIsDown() {
        final List<Change> changes = new ArrayList<>();
        for (final String service : List.of("proxy", "limbo", "hunger-games", "smp")) {
            changes.add(Change.unresolved(service, service, TEXTS.report().words(GITHUB_403)));
        }
        changes.add(Change.unresolved(
                "proxy", Topology.RESOURCE_PACK, TEXTS.report().words(GITHUB_403)));
        changes.add(
                Change.unresolved("discord-bot", "discord-bot", TEXTS.report().words(GITHUB_403)));
        changes.add(Change.unresolved("steward", "steward", TEXTS.report().words(GITHUB_403)));
        return new UpdatePlan(
                Instant.EPOCH,
                null,
                false,
                changes,
                List.of(new UpdatePlan.Unclaimed("smp", "SomebodysPlugin-1.0.0.jar")),
                List.of());
    }

    private static int occurrences(final String text, final String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }
}
