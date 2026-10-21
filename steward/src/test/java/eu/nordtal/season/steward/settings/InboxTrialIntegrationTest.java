package eu.nordtal.season.steward.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.DatabaseMessages;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.inbox.HungerGamesRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.InboxStatus;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.inbox.SettingsChange;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.spec.MessageSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Tests that a save waits for the server's answer to its settings change and withdraws an unanswered one. */
class InboxTrialIntegrationTest {

    private static final SettingsChange CHANGE =
            new SettingsChange("milestones", Map.of("milestones", "[]"), List.of("broadcast"));

    private final DataSource database = TestDatabase.fresh().dataSource();
    private final Inbox<SmpRequest> smp = Inbox.over(database, SmpRequest.TABLE);
    private final Inbox<SmpRequest> server = Inbox.takeOver(database, SmpRequest.TABLE, "smp");
    private final Messages refusals = Messages.load(
            InboxTrialIntegrationTest.class.getClassLoader(),
            "messages/" + MessageSchema.bundle(DatabaseMessages.class),
            Locales.DEFAULT);

    @Test
    void aRefusalComesBackInWords() {
        final ServerTrial.Verdict verdict = trial(asked -> server.settle(
                        asked.id(),
                        Outcome.refused(ServerRefusal.SETTINGS_REFUSED.with("smp", "the milestones name stone_axes"))))
                .ask("smp", CHANGE, Actor.STEWARD);

        assertEquals(ServerTrial.Verdict.Kind.REFUSED, verdict.kind());
        assertEquals("smp would not start with this change: the milestones name stone_axes", verdict.refusal());
    }

    @Test
    void anAnswerWithoutARefusalTakesTheChange() {
        final ServerTrial.Verdict verdict =
                trial(asked -> server.settle(asked.id(), Outcome.done(null))).ask("smp", CHANGE, Actor.STEWARD);

        assertEquals(ServerTrial.Verdict.Kind.TAKEN, verdict.kind());
    }

    @Test
    void aServerThatDoesNotAnswerLeavesTheChangeUncheckedAndTheQuestionWithdrawn() {
        final ServerTrial.Verdict verdict = trial(null).ask("smp", CHANGE, Actor.STEWARD);

        assertEquals(ServerTrial.Verdict.Kind.UNANSWERED, verdict.kind());
        assertEquals(
                List.of(InboxStatus.CANCELLED),
                smp.recent(SmpRequest.CheckSettings.class, 5).stream()
                        .map(Request::status)
                        .toList(),
                "a server that starts later finds no question to answer");
    }

    @Test
    void aServiceWithoutAnInboxIsNotAsked() {
        assertEquals(
                ServerTrial.Verdict.Kind.UNASKED,
                trial(null).ask("proxy", CHANGE, Actor.STEWARD).kind());
    }

    /** Builds the trial whose first pause lets the server claim the question and answer it with {@code answer}. */
    private InboxTrial trial(final @Nullable Consumer<Request<SmpRequest>> answer) {
        final Waiting clock = new Waiting() {
            private Instant now = Instant.parse("2026-10-18T17:00:00Z");

            @Override
            public Instant now() {
                return now;
            }

            @Override
            public boolean sleep(final Duration duration) {
                now = now.plus(duration);
                if (answer != null) {
                    server.claim().ifPresent(answer);
                }
                return true;
            }
        };
        return new InboxTrial(smp, Inbox.over(database, HungerGamesRequest.TABLE), refusals, clock);
    }
}
