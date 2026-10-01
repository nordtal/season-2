package eu.nordtal.s2.steward.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.Outcome;
import eu.nordtal.s2.database.inbox.Reload;
import eu.nordtal.s2.database.inbox.SmpRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A reload travels as a typed request into the server's inbox, and the server's answer is what the save reports. */
class InboxReloaderIntegrationTest {

    private static DataSource dataSource;

    private @Nullable ScheduledExecutorService smp;

    @BeforeAll
    static void database() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @AfterEach
    void stop() {
        if (smp != null) {
            smp.shutdownNow();
        }
    }

    /** Runs an SMP that drains its inbox every few milliseconds and answers a reload as told. */
    private void smpAnswering(final Function<SmpRequest, Outcome> answer) {
        final Inbox<SmpRequest> inbox = Inbox.over(dataSource, SmpRequest.TABLE);
        smp = Executors.newSingleThreadScheduledExecutor();
        final var _ = smp.scheduleWithFixedDelay(
                () -> inbox.drain(request -> answer.apply(request.payload())), 0, 20, TimeUnit.MILLISECONDS);
    }

    private static InboxReloader reloader() {
        return new InboxReloader(dataSource, Waiting.on(Clock.systemUTC()), Duration.ofSeconds(1));
    }

    @Test
    void aServerThatReReadEverythingIsAppliedInItsOwnWords() {
        smpAnswering(request -> request instanceof Reload
                ? Outcome.done("The settings and the message bundles were reloaded.")
                : Outcome.failed("not a reload"));

        final Optional<ConfigApi.Reloaded> answer = reloader().reload("smp");

        assertTrue(answer.isPresent(), "the SMP answered within its patience");
        assertTrue(answer.get().applied());
        assertEquals(
                "The settings and the message bundles were reloaded.",
                answer.get().text());
    }

    @Test
    void aServerThatKeptPartOfItsOldSettingsIsNotApplied() {
        smpAnswering(request -> Outcome.failed("Not everything was reloaded: prestige.yml"));

        final Optional<ConfigApi.Reloaded> answer = reloader().reload("smp");

        assertTrue(answer.isPresent());
        assertFalse(answer.get().applied());
        assertEquals("Not everything was reloaded: prestige.yml", answer.get().text());
    }

    @Test
    void aServerThatIsNotListeningIsNoAnswerOnceItsPatienceIsSpent() {
        assertEquals(Optional.empty(), reloader().reload("smp"));
    }

    @Test
    void aServiceWithoutAnInboxIsRefusedBeforeAnythingIsWritten() {
        assertThrows(IllegalArgumentException.class, () -> reloader().reload("discord-bot"));
    }
}
