package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;

import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The update embed stays inside Discord's limit on the whole of it.
 *
 * <h2>Why this needs a test rather than care</h2>
 * Discord caps an embed at 6 000 characters <em>in total</em> - title, description and every field
 * name and value added together - and JDA throws from {@code build()} when that is exceeded. In
 * this class the exception unwinds into {@code fail()}, so the admin is told "that did not work"
 * instead of being shown the run. That happens on exactly the runs with the most to say, which are
 * the ones going wrong.
 *
 * <p>The arithmetic guarding it has been wrong twice in two days. First the per-part caps were
 * enforced and the total was not; then the overflow field reserved a flat 20 characters for a value
 * three times that; then the description was written out of the remaining budget without being
 * subtracted from it, so the overflow field measured itself against space already spent. Each was
 * introduced by the fix for the one before it, which is the argument for measuring the result
 * instead of reasoning about the guard.</p>
 *
 * <p>It renders against the real shared bundle since 2026-09-08, because the embed's headings and
 * state labels stopped being hardcoded English that day. A German label is not the same length as
 * its English original, so the budget arithmetic is measured in the language it will actually be
 * drawn in - both of them.</p>
 */
class EmbedBudgetTest {

    /** Discord's own limit, and the thing every case here measures against. */
    private static final int LIMIT = 6000;

    private final Messages messages = Messages.load(EmbedBudgetTest.class.getClassLoader(),
            "messages/commands", Locale.ENGLISH, Locale.GERMAN);

    @Test
    @DisplayName("long notes and more services than fit still build inside the limit")
    void theWorstCaseFits() {
        for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final MessageEmbed embed = UpdateCommand.fields(report(40, 600, 30, 400), request(),
                    messages, locale);

            assertTrue(embed.getLength() <= LIMIT,
                    locale + ": the embed is " + embed.getLength() + " characters; JDA refuses it"
                            + " above " + LIMIT + ", and the admin then sees 'that did not work'"
                            + " instead of the run");
        }
    }

    @Test
    @DisplayName("a description that eats the whole budget leaves no room claimed by the overflow field")
    void theDescriptionIsSubtractedFromTheBudget() {
        // The exact shape of the third bug: notes long enough to consume everything the services
        // left, and more services than were drawn, so the overflow field is offered.
        for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final MessageEmbed embed = UpdateCommand.fields(report(30, 900, 1, 5000), request(),
                    messages, locale);

            assertTrue(embed.getLength() <= LIMIT,
                    locale + ": the embed is " + embed.getLength() + " characters");
        }
    }

    @Test
    @DisplayName("an ordinary run is drawn in full, not truncated into uselessness")
    void theNormalCaseKeepsEveryService() {
        final MessageEmbed embed = UpdateCommand.fields(report(4, 60, 2, 80), request(),
                messages, Locale.GERMAN);

        assertTrue(embed.getLength() <= LIMIT);
        assertTrue(embed.getFields().size() == 4,
                "four services and a short note is the everyday run; guarding the limit must not"
                        + " cost it a single field");
    }

    /** A report with {@code services} lines of roughly {@code each} characters, plus notes. */
    private static UpdateReport report(final int services, final int each,
                                       final int notes, final int noteLength) {
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.VERIFYING);
        for (int i = 0; i < services; i++) {
            final List<UpdateReport.Change> changes = new ArrayList<>();
            for (int c = 0; c * 40 < each; c++) {
                changes.add(new UpdateReport.Change("artefact-" + i + "-" + c, "0.6.0", "0.7.0"));
            }
            report = report.with(new UpdateReport.ServiceLine("service-" + i,
                    UpdateReport.State.FAILED, changes, "x".repeat(each)));
        }
        for (int i = 0; i < notes; i++) {
            report = report.withNote("n".repeat(noteLength));
        }
        return report;
    }

    private static UpdateRequest request() {
        return new UpdateRequest(1L, UpdateKind.UPDATE, UpdateStatus.RUNNING, UpdateSource.DISCORD,
                "1", Instant.now(), Instant.now(), Instant.now(), null, null);
    }
}
