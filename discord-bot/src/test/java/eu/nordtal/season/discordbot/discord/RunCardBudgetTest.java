package eu.nordtal.season.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.update.RunRecord;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Message;
import org.junit.jupiter.api.Test;

/** The run card stays within the size limit of one Components V2 message, however many steps a run writes. */
class RunCardBudgetTest {

    private static final DocumentRenderer DOCUMENTS = StoredDocumentsStandIn.renderer();

    @Test
    void longChangesAndMoreServicesThanFitStillStayInsideTheLimits() {
        assertInside(card(record(40, 600), request()));
    }

    @Test
    void anOrdinaryRunIsDrawnInFullOneLinePerServiceUnderOneHeading() {
        final String text = card(record(4, 60), request()).text();

        assertTrue(text.contains("### Services"), text);
        for (int i = 0; i < 4; i++) {
            assertTrue(
                    text.contains("**service-" + i + "**"),
                    "four services is the everyday run; guarding the limit must not cost it a line");
        }
        assertTrue(text.contains("0.6.0 → 0.7.0"), "a change is a transition");
        assertFalse(text.matches("(?s).*\\n\\+\\d+(\\n.*)?"), "nothing was summarised away");
    }

    @Test
    void aServiceIsNamedWithItsValuesEscaped() {
        final RunRecord record = new RunRecord(
                RunStage.FAILED,
                List.of(
                        step(RunStep.Kind.SCOPE, "mc_smp", RunStep.Outcome.FAILED, RunStep.Reason.UNKNOWN, List.of()),
                        step(RunStep.Kind.STOP, "smp", RunStep.Outcome.FAILED, RunStep.Reason.REFUSED, List.of())));
        final String why = why(card(record, request()));

        assertTrue(why.contains("**mc\\_smp** not in this stack"), why);
        assertTrue(why.contains("**smp** refused, the run log has the answer"), why);
    }

    @Test
    void aRunTooBigForOneMessageCountsWhatItLeavesOutAndNeverDrawsACodeBlock() {
        final String text = card(record(40, 600), request()).text();

        assertTrue(text.matches("(?s).*\\n\\+\\d+(\\n.*)?"), "what did not fit is counted: " + text.length());
        assertFalse(text.contains("```"), "a code block is for something to copy");
    }

    @Test
    void theAdminChannelsContextIsInsideTheLimitsToo() {
        // Only the feed draws with context, so only this case measures who asked.
        assertInside(UpdateFeed.card(record(40, 600), longAsker(), DOCUMENTS, true, null));
    }

    private static void assertInside(final DocumentRenderer.Drawn card) {
        final int components = MessageComponentTree.of(card.components())
                .findAll(Component.class)
                .size();
        assertTrue(
                card.text().length() <= Message.MAX_CONTENT_LENGTH_COMPONENT_V2,
                "the card's text is " + card.text().length() + " characters");
        assertTrue(components <= Message.MAX_COMPONENT_COUNT_IN_COMPONENT_TREE, "the card has " + components);
    }

    private static DocumentRenderer.Drawn card(final RunRecord record, final UpdateRequest request) {
        return UpdateFeed.card(record, request, DOCUMENTS, false, null);
    }

    private static String why(final DocumentRenderer.Drawn card) {
        final String text = card.text();
        return text.substring(text.indexOf("### Why"));
    }

    /** A failed run whose every service installs about {@code each} characters of changes and then fails. */
    private static RunRecord record(final int services, final int each) {
        final List<RunStep.Row> steps = new ArrayList<>();
        for (int i = 0; i < services; i++) {
            final List<RunStep.Change> changes = new ArrayList<>();
            for (int c = 0; c * 40 < each; c++) {
                changes.add(new RunStep.Change("artefact-" + i + "-" + c, "0.6.0", "0.7.0", null));
            }
            steps.add(step(RunStep.Kind.INSTALL, "service-" + i, RunStep.Outcome.DONE, null, changes));
            steps.add(step(
                    RunStep.Kind.VERIFY,
                    "service-" + i,
                    RunStep.Outcome.FAILED,
                    RunStep.Reason.NOT_HEALTHY,
                    List.of()));
        }
        return new RunRecord(RunStage.FAILED, steps);
    }

    private static RunStep.Row step(
            final RunStep.Kind kind,
            final String service,
            final RunStep.Outcome outcome,
            final RunStep.@org.jspecify.annotations.Nullable Reason reason,
            final List<RunStep.Change> changes) {
        return new RunStep.Row(
                0, kind, service, null, null, outcome, reason, null, changes, List.of(), List.of(), null, null, null,
                List.of(), List.of());
    }

    /** The longest asker of a row is a person, whose Discord id has at most twenty digits. */
    private static UpdateRequest longAsker() {
        return request(Actor.person(eu.nordtal.season.common.id.DiscordId.of("9".repeat(20))));
    }

    private static UpdateRequest request() {
        return request(Actor.HOST);
    }

    private static UpdateRequest request(final Actor actor) {
        return new UpdateRequest(
                1L,
                UpdateKind.UPDATE,
                UpdateStatus.FAILED,
                actor,
                Instant.now(),
                Instant.now(),
                null,
                List.of(),
                Instant.now(),
                null,
                RunStage.FAILED);
    }
}
