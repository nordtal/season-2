package eu.nordtal.s2.updater.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That one outage produces one explanation.
 *
 * <h2>What went wrong</h2>
 * A GitHub failure makes every season row {@code UNRESOLVED}, and each carried the whole reason -
 * the URL, the advice about the rate limit, and 300 characters of trimmed JSON body. Eight rows of
 * that is roughly 4 600 characters. {@code UpdateCommand.DESCRIPTION_BUDGET} cuts a Discord embed
 * description at 4 000, so what fell off the end was everything after the rows: the summary line
 * that says the list is not the whole picture, and the "jars nothing accounts for" list. The two
 * parts that tell an admin what to do were the two parts that did not survive the outage they were
 * written for.
 */
class ReportTest {

    /** {@code UpdateCommand.DESCRIPTION_BUDGET}. Not imported: :updater must not depend on the bot. */
    private static final int DESCRIPTION_BUDGET = 4000;

    /** What HttpException actually builds for a rate-limited GitHub, body trimmed at 300. */
    private static final String GITHUB_403 =
            "HTTP 403 from https://api.github.com/repos/nordtal/season-2/releases/latest"
                    + " - rate limited. GitHub allows 60 unauthenticated requests per hour per IP;"
                    + " set github-token in updater.yml if this host shares its address. Body: "
                    + "x".repeat(300);

    @Test
    @DisplayName("eight artefacts behind one outage still leave the summary inside the embed budget")
    void oneOutageIsExplainedOnce() {
        final String rendered = Report.render(githubIsDown());

        assertTrue(rendered.length() < DESCRIPTION_BUDGET,
                "the report is " + rendered.length() + " characters, and a Discord embed keeps only"
                        + " the first " + DESCRIPTION_BUDGET + ". Everything after the rows - the"
                        + " summary and the unclaimed jars - is what gets cut.");

        assertEquals(1, occurrences(rendered, "rate limited"),
                "the same reason is printed more than once. Eight copies of it is the whole bug.");
        assertTrue(rendered.contains("[1]"), "the rows no longer reference the reason: " + rendered);
        assertTrue(rendered.contains("why:"), "the reason is not printed anywhere at all");
    }

    @Test
    @DisplayName("the parts that say what to do survive the outage they are written for")
    void theSummarySurvives() {
        final String rendered = Report.render(githubIsDown());

        assertTrue(rendered.contains("could not be checked at all"),
                "the summary that says the list is not the whole picture is missing");
        assertTrue(rendered.contains("jars nothing accounts for"),
                "the unclaimed list is missing");
    }

    @Test
    @DisplayName("a reason that appears once is printed where it happened, not as a footnote")
    void aLoneReasonStaysInline() {
        // A footnote for a single occurrence is worse than the sentence itself: the reader has to
        // go and find it, and there is nothing to deduplicate.
        final UpdatePlan plan = new UpdatePlan(Instant.EPOCH, "v0.2.1", false,
                List.of(Change.unresolved("smp", "chunky", "Modrinth answered 503")),
                List.of());

        final String rendered = Report.render(plan);
        assertTrue(rendered.contains("Modrinth answered 503"), rendered);
        assertTrue(!rendered.contains("[1]"), "a lone reason was turned into a footnote: " + rendered);
    }

    /** The first deployment: the GitHub API refused, so every season artefact went unresolved. */
    private static UpdatePlan githubIsDown() {
        final List<Change> changes = new ArrayList<>();
        for (final String service : List.of("network-control", "limbo", "hunger-games", "smp")) {
            changes.add(Change.unresolved(service, service, GITHUB_403));
        }
        changes.add(Change.unresolved("network-control", Topology.RESOURCE_PACK, GITHUB_403));
        changes.add(Change.unresolved("smp", "display-tags", GITHUB_403));
        changes.add(Change.unresolved("discord-bot", "discord-bot", GITHUB_403));
        changes.add(Change.unresolved("updater", "updater", GITHUB_403));
        return new UpdatePlan(Instant.EPOCH, null, false, changes,
                List.of(new UpdatePlan.Unclaimed("smp", "SomebodysPlugin-1.0.0.jar")));
    }

    private static int occurrences(final String text, final String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }
}
