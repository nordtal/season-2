package eu.nordtal.s2.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;

/**
 * The update embed stays inside Discord's limit on the whole of it.
 *
 * Why this needs a test rather than care: Discord caps an embed at 6 000 characters in total - title, description
 * and every field name and value added together - and JDA throws from {@code build()} when that is exceeded. In this
 * class the exception unwinds into {@code fail()}, so the admin is told "that did not work" instead of being shown
 * the run. That happens on exactly the runs with the most to say, which are the ones going wrong.
 *
 * The arithmetic guarding it has been wrong twice in two days. First the per-part caps were enforced and the total
 * was not; then the overflow field reserved a flat 20 characters for a value three times that; then the description
 * was written out of the remaining budget without being subtracted from it, so the overflow field measured itself
 * against space already spent. Each was introduced by the fix for the one before it, which is the argument for
 * measuring the result instead of reasoning about the guard.
 *
 * It renders against the real shared bundle, because the embed's headings and state labels are not hardcoded
 * English. A German label is not the same length as its English original, so the budget arithmetic is measured in
 * the language it will actually be drawn in - both of them.
 */
class EmbedBudgetTest {

    /** Discord's own limit, and the thing every case here measures against. */
    private static final int LIMIT = 6000;

    private final Messages messages =
            Messages.load(EmbedBudgetTest.class.getClassLoader(), "messages/commands", Locale.ENGLISH, Locale.GERMAN);

    @Test
    void longNotesAndMoreServicesThanFitStillBuildInsideTheLimit() {
        for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final MessageEmbed embed = UpdateCommand.fields(report(40, 600, 30, 400), request(), messages, locale);

            assertTrue(
                    embed.getLength() <= LIMIT,
                    locale + ": the embed is " + embed.getLength() + " characters; JDA refuses it"
                            + " above " + LIMIT + ", and the admin then sees 'that did not work'"
                            + " instead of the run");
        }
    }

    @Test
    void aDescriptionThatEatsTheWholeBudgetLeavesNoRoomClaimedByTheOverflowField() {
        // Notes long enough to consume everything the services left, plus more services than were drawn.
        for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final MessageEmbed embed = UpdateCommand.fields(report(30, 900, 1, 5000), request(), messages, locale);

            assertTrue(embed.getLength() <= LIMIT, locale + ": the embed is " + embed.getLength() + " characters");
        }
    }

    @Test
    void anOrdinaryRunIsDrawnInFullOneLinePerServiceUnderOneHeading() {
        final MessageEmbed embed = UpdateCommand.fields(report(4, 60, 2, 80), request(), messages, Locale.GERMAN);

        assertTrue(embed.getLength() <= LIMIT);
        final MessageEmbed.Field services = embed.getFields().stream()
                .filter(field -> "Dienste".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        for (int i = 0; i < 4; i++) {
            assertTrue(
                    services.getValue().contains("**service-" + i + "**"),
                    "four services and a short note is the everyday run; guarding the limit must"
                            + " not cost it a single line");
        }
        assertTrue(services.getValue().contains("0.6.0 → **0.7.0**"), "a change is a transition");
        assertFalse(services.getValue().contains("weitere"), "nothing was summarised away");
    }

    @Test
    void aNoteThatIsAPageRenderedForATerminalIsLeftToStewardsLog() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.DONE)
                .withNote("proxy: release v0.9.5 carries no proxy-<version>.jar")
                .withNote("what was done\n\nproxy\n  proxy   unchanged   proxy-0.9.5.jar\n");
        final MessageEmbed embed = UpdateCommand.fields(report, request(), messages, Locale.ENGLISH);

        final String notes = embed.getFields().stream()
                .filter(field -> "Notes".equals(field.getName()))
                .map(MessageEmbed.Field::getValue)
                .findFirst()
                .orElseThrow();
        assertTrue(notes.contains("carries no proxy"), "a one-line note is drawn");
        assertFalse(
                notes.contains("what was done"),
                "the table repeats the service lines in a" + " shape only a monospaced font reads");
    }

    @Test
    void aRunTooBigForOneEmbedCountsWhatItLeavesOutAndNeverDrawsACodeBlock() {
        final MessageEmbed embed =
                UpdateCommand.fields(report(40, 600, 30, 400), request(), messages, Locale.ENGLISH, true);

        assertTrue(embed.getFields().size() <= 25);
        final String all =
                embed.getFields().stream().map(MessageEmbed.Field::getValue).reduce("", String::concat)
                        + embed.getDescription();
        assertTrue(all.matches("(?s).*\\+\\d+ more.*"), "what did not fit is counted: " + all.length());
        assertFalse(all.contains("```"), "a code block is for something to copy");
    }

    @Test
    void theAdminChannelsContextFieldsAreInsideTheLimitTooInBothLanguages() {
        // Only UpdateFeed draws with context, so without this case the fields it adds are measured by nothing.
        for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final MessageEmbed embed =
                    UpdateCommand.fields(report(40, 600, 30, 400), longAsker(), messages, locale, true);

            assertTrue(
                    embed.getLength() <= LIMIT,
                    locale + ": the embed is " + embed.getLength() + " characters with the context"
                            + " fields, above " + LIMIT + " - Discord refuses the whole message, so"
                            + " the admin channel would show nothing at all about a run in flight");
        }
    }

    private static UpdateReport report(final int services, final int each, final int notes, final int noteLength) {
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.VERIFYING);
        for (int i = 0; i < services; i++) {
            final List<UpdateReport.Change> changes = new ArrayList<>();
            for (int c = 0; c * 40 < each; c++) {
                changes.add(new UpdateReport.Change("artefact-" + i + "-" + c, "0.6.0", "0.7.0"));
            }
            report = report.with(
                    new UpdateReport.ServiceLine("service-" + i, UpdateReport.State.FAILED, changes, "x".repeat(each)));
        }
        for (int i = 0; i < notes; i++) {
            report = report.withNote("n".repeat(noteLength));
        }
        return report;
    }

    /** {@code requested_by} is varchar(32); 64 is twice the worst a row can hold. */
    private static UpdateRequest longAsker() {
        return new UpdateRequest(
                1L,
                UpdateKind.UPDATE,
                UpdateStatus.RUNNING,
                UpdateSource.GAME,
                "T".repeat(64),
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null);
    }

    private static UpdateRequest request() {
        return new UpdateRequest(
                1L,
                UpdateKind.UPDATE,
                UpdateStatus.RUNNING,
                UpdateSource.DISCORD,
                "1",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null);
    }
}
