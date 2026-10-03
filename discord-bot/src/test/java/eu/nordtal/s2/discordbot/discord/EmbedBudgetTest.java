package eu.nordtal.s2.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.discordbot.DiscordRenderer;
import eu.nordtal.s2.messages.Messages;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;

/**
 * The update embed stays inside Discord's 6000-character total, in every language it is drawn in.
 *
 * JDA throws from {@code build()} past the limit, which would hide exactly the runs with the most to say.
 */
class EmbedBudgetTest {

    /** Discord's limit on a whole embed. */
    private static final int LIMIT = 6000;

    private final DiscordRenderer messages = DiscordRenderer.of(Messages.load(
            EmbedBudgetTest.class.getClassLoader(), List.of("messages/access", "messages/admin"), Locale.ENGLISH));

    @Test
    void longNotesAndMoreServicesThanFitStillBuildInsideTheLimit() {
        final MessageEmbed embed = UpdateFeed.fields(report(40, 600, 30, 400), request(), messages, Instant.now());

        assertTrue(
                embed.getLength() <= LIMIT,
                "the embed is " + embed.getLength() + " characters; JDA refuses it above " + LIMIT
                        + ", and the admin then sees 'that did not work' instead of the run");
    }

    @Test
    void aDescriptionThatEatsTheWholeBudgetLeavesNoRoomClaimedByTheOverflowField() {
        // Notes long enough to consume the rest, plus more services than are drawn.
        final MessageEmbed embed = UpdateFeed.fields(report(30, 900, 1, 5000), request(), messages, Instant.now());

        assertTrue(embed.getLength() <= LIMIT, "the embed is " + embed.getLength() + " characters");
    }

    @Test
    void anOrdinaryRunIsDrawnInFullOneLinePerServiceUnderOneHeading() {
        final MessageEmbed embed = UpdateFeed.fields(report(4, 60, 2, 80), request(), messages, Instant.now());

        assertTrue(embed.getLength() <= LIMIT);
        final MessageEmbed.Field services = embed.getFields().stream()
                .filter(field -> "Services".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        for (int i = 0; i < 4; i++) {
            assertTrue(
                    services.getValue().contains("**service-" + i + "**"),
                    "four services and a short note is the everyday run; guarding the limit must"
                            + " not cost it a single line");
        }
        assertTrue(services.getValue().contains("0.6.0 → **0.7.0**"), "a change is a transition");
        assertFalse(services.getValue().contains("more"), "nothing was summarised away");
    }

    @Test
    void aNoteThatIsAPageRenderedForATerminalIsLeftToStewardsLog() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.DONE)
                .withNote("proxy: release v0.9.5 carries no proxy-<version>.jar")
                .withNote("what was done\n\nproxy\n  proxy   unchanged   proxy-0.9.5.jar\n");
        final MessageEmbed embed = UpdateFeed.fields(report, request(), messages, Instant.now());

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
                UpdateFeed.fields(report(40, 600, 30, 400), request(), messages, true, Instant.now());

        assertTrue(embed.getFields().size() <= 25);
        final String all =
                embed.getFields().stream().map(MessageEmbed.Field::getValue).reduce("", String::concat)
                        + embed.getDescription();
        assertTrue(all.matches("(?s).*\\+\\d+ more.*"), "what did not fit is counted: " + all.length());
        assertFalse(all.contains("```"), "a code block is for something to copy");
    }

    @Test
    void theAdminChannelsContextFieldsAreInsideTheLimitToo() {
        // Only UpdateFeed draws with context, so this is the only case measuring those fields.
        final MessageEmbed embed =
                UpdateFeed.fields(report(40, 600, 30, 400), longAsker(), messages, true, Instant.now());

        assertTrue(
                embed.getLength() <= LIMIT,
                "the embed is " + embed.getLength() + " characters with the context fields, above " + LIMIT
                        + " - Discord refuses the whole message, so the admin channel would show nothing at all"
                        + " about a run in flight");
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

    /** The longest asker a row can carry: a person, whose Discord id has at most twenty digits. */
    private static UpdateRequest longAsker() {
        return new UpdateRequest(
                1L,
                UpdateKind.UPDATE,
                UpdateStatus.RUNNING,
                Actor.person(eu.nordtal.s2.common.id.DiscordId.of("9".repeat(20))),
                Instant.now(),
                Instant.now(),
                null,
                List.of(),
                Instant.now(),
                null,
                null);
    }

    private static UpdateRequest request() {
        return new UpdateRequest(
                1L,
                UpdateKind.UPDATE,
                UpdateStatus.RUNNING,
                Actor.HOST,
                Instant.now(),
                Instant.now(),
                null,
                List.of(),
                Instant.now(),
                null,
                null);
    }
}
