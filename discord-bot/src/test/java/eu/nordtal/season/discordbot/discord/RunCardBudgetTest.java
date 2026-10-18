package eu.nordtal.season.discordbot.discord;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.LayoutRenderer;
import eu.nordtal.season.discordbot.StoredLayoutsStandIn;
import eu.nordtal.season.messages.Messages;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Message;
import org.junit.jupiter.api.Test;

/** The run card stays within the size limit of one Components V2 message, however much a run reports. */
class RunCardBudgetTest {

    private static final LayoutRenderer LAYOUTS = new LayoutRenderer(
            DiscordRenderer.of(Messages.load(
                    RunCardBudgetTest.class.getClassLoader(),
                    List.of("messages/access", "messages/admin"),
                    Locale.ENGLISH)),
            StoredLayoutsStandIn.none());

    @Test
    void longNotesAndMoreServicesThanFitStillStayInsideTheLimits() {
        assertInside(card(report(40, 600, 30, 400), request()));
    }

    @Test
    void notesThatEatTheWholeBudgetStillLeaveTheCardInsideTheLimits() {
        assertInside(card(report(30, 900, 1, 5000), request()));
    }

    @Test
    void anOrdinaryRunIsDrawnInFullOneLinePerServiceUnderOneHeading() {
        final String text = card(report(4, 60, 2, 80), request()).text();

        assertTrue(text.contains("### Services"), text);
        for (int i = 0; i < 4; i++) {
            assertTrue(
                    text.contains("**service-" + i + "**"),
                    "four services and a short note is the everyday run; guarding the limit must not cost it a line");
        }
        assertTrue(text.contains("0.6.0 → 0.7.0"), "a change is a transition");
        assertFalse(text.matches("(?s).*\\n\\+\\d+(\\n.*)?"), "nothing was summarised away");
    }

    @Test
    void aNoteIsToldInTheChannelsLanguageWithItsValuesEscaped() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.FAILED)
                .withNote(UpdateReport.Note.failed(
                                UpdateReport.Step.SCOPE, TEXTS.report().downRefused())
                        .on("mc_smp"))
                .with(new UpdateReport.ServiceLine(
                        "smp",
                        UpdateReport.State.FAILED,
                        List.of(),
                        UpdateReport.Facts.refused(UpdateReport.Act.STOP, "exit *1*")));
        final String why = why(card(report, request()));

        assertTrue(why.contains("**mc\\_smp** cannot be put down from here"), why);
        assertTrue(why.contains("**smp** could not be stopped: exit \\*1\\*"), why);
    }

    @Test
    void aNoteThatIsAPageRenderedForATerminalIsLeftToStewardsLog() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.FAILED)
                .withNote(UpdateReport.Note.failed(
                        UpdateReport.Step.RUN,
                        TEXTS.report().words("proxy: release v0.9.5 carries no proxy-<version>.jar")))
                .withNote(UpdateReport.Note.failed(
                        UpdateReport.Step.RUN,
                        TEXTS.report().words("what was done\n\nproxy\n  proxy   unchanged   proxy-0.9.5.jar\n")));
        final String why = why(card(report, request()));

        assertTrue(why.contains("carries no proxy"), "a one-line note is drawn");
        assertFalse(
                why.contains("what was done"),
                "the table repeats the service lines in a shape only a monospaced font reads");
    }

    @Test
    void aRunTooBigForOneMessageCountsWhatItLeavesOutAndNeverDrawsACodeBlock() {
        final String text = card(report(40, 600, 30, 400), request()).text();

        assertTrue(text.matches("(?s).*\\n\\+\\d+(\\n.*)?"), "what did not fit is counted: " + text.length());
        assertFalse(text.contains("```"), "a code block is for something to copy");
    }

    @Test
    void theAdminChannelsContextIsInsideTheLimitsToo() {
        // Only the feed draws with context, so only this case measures who asked.
        assertInside(UpdateFeed.card(report(40, 600, 30, 400), longAsker(), LAYOUTS, true, null));
    }

    private static void assertInside(final LayoutRenderer.Drawn card) {
        final int components = MessageComponentTree.of(card.components())
                .findAll(Component.class)
                .size();
        assertTrue(
                card.text().length() <= Message.MAX_CONTENT_LENGTH_COMPONENT_V2,
                "the card's text is " + card.text().length() + " characters");
        assertTrue(components <= Message.MAX_COMPONENT_COUNT_IN_COMPONENT_TREE, "the card has " + components);
    }

    private static LayoutRenderer.Drawn card(final UpdateReport report, final UpdateRequest request) {
        return UpdateFeed.card(report, request, LAYOUTS, false, null);
    }

    private static String why(final LayoutRenderer.Drawn card) {
        final String text = card.text();
        return text.substring(text.indexOf("### Why"));
    }

    private static UpdateReport report(final int services, final int each, final int notes, final int noteLength) {
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.FAILED);
        for (int i = 0; i < services; i++) {
            final List<UpdateReport.Change> changes = new ArrayList<>();
            for (int c = 0; c * 40 < each; c++) {
                changes.add(new UpdateReport.Change("artefact-" + i + "-" + c, "0.6.0", "0.7.0"));
            }
            report = report.with(new UpdateReport.ServiceLine(
                    "service-" + i,
                    UpdateReport.State.FAILED,
                    changes,
                    UpdateReport.Facts.refused(UpdateReport.Act.BACKUP, "x".repeat(each))));
        }
        for (int i = 0; i < notes; i++) {
            report = report.withNote(UpdateReport.Note.failed(
                    UpdateReport.Step.RUN, TEXTS.report().words("n".repeat(noteLength))));
        }
        return report;
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
                UpdateStatus.RUNNING,
                actor,
                Instant.now(),
                Instant.now(),
                null,
                List.of(),
                Instant.now(),
                null,
                null);
    }
}
