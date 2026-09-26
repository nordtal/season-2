package eu.nordtal.s2.commands.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.command.CommandOutcome;
import eu.nordtal.s2.common.command.NewCommandRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The near end of a travelling command: the wait, and the three ways it can end.
 *
 * Real timings, shrunk: the scheduler is a real one and the timeout is milliseconds rather than
 * thirty seconds. That is
 * deliberate over a fake clock: what this class actually does is reschedule itself, and a fake
 * scheduler would prove that the arithmetic is right while saying nothing about whether the
 * rescheduling terminates. The waits here are bounded by a latch, never by a sleep.
 */
class OutboxTest {

    private static final Declaration AURA = new Declaration(
            List.of("smp", "aura"),
            Target.SMP,
            Set.of(Surface.GAME, Surface.DISCORD),
            true,
            false,
            List.of(Argument.player("player"), Argument.integer("delta", -10000, 10000)));

    private static final UUID WHO = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final FakeRequests requests = new FakeRequests();
    private final List<String> warnings = new ArrayList<>();
    private final BiConsumer<String, Throwable> warn = this::recordWarning;

    private final Outbox outbox = new Outbox(requests, scheduler, Duration.ofMillis(300), Duration.ofMillis(5), warn);

    private void recordWarning(final String message, final Throwable cause) {
        warnings.add(message);
    }

    @AfterEach
    void stop() throws InterruptedException {
        scheduler.shutdownNow();
        assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
    }

    private Values aura(final int delta) {
        return new Values(AURA, Map.of("player", WHO, "delta", delta));
    }

    /** Wait for {@code user} to have said {@code count} things, or fail. */
    private static void until(final FakeUser user, final int count) {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (user.replies.size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected " + count + " replies, got " + user.keys());
            }
            Thread.onSpinWait();
        }
    }

    @Test
    void theRequestCarriesTheAskersIdentityLanguageAndArguments() {
        final FakeUser user = FakeUser.inDiscord();
        outbox.send(AURA, user, aura(-25));
        until(user, 1);

        final NewCommandRequest sent = requests.submitted().getFirst();
        assertEquals(Target.SMP.name(), sent.target());
        assertEquals("smp aura", sent.command(), "the path is the command's identity everywhere");
        assertEquals(WHO + " -25", sent.arguments());
        assertEquals("DISCORD", sent.source());
        assertEquals("100000000000000002", sent.discordId().orElseThrow());
        assertEquals("en", sent.locale());
    }

    @Test
    void theAnswerComesBackVerbatimBecauseTheTargetAlreadyRenderedIt() {
        final FakeUser user = FakeUser.inDiscord();
        outbox.send(AURA, user, aura(10));
        until(user, 1);

        requests.answer(1, true, "Aura changed by 10.");
        until(user, 2);

        assertEquals("command.remote.sent", user.replies.get(0).key());
        assertEquals("<literal>", user.replies.get(1).key());
        assertEquals("Aura changed by 10.", user.replies.get(1).of("text"));
    }

    @Test
    void aFailureOnTheFarSideAddsASentenceOfItsOwn() {
        final FakeUser user = FakeUser.inDiscord();
        outbox.send(AURA, user, aura(10));
        until(user, 1);

        requests.answer(1, false, "The world is not loaded.");
        until(user, 3);

        assertEquals(List.of("command.remote.sent", "<literal>", "command.remote.failed"), user.keys());
    }

    @Test
    void nothingEverPickedItUpThatIsTheTargetBeingDownAndItSaysSo() {
        final FakeUser user = FakeUser.inDiscord();
        outbox.send(AURA, user, aura(10));
        until(user, 2);

        assertEquals("command.remote.no-answer", user.replies.get(1).key());
        assertEquals(
                CommandOutcome.Status.EXPIRED,
                requests.statusOf(1),
                "the asking side is what writes EXPIRED - the target never does, which is what"
                        + " makes that status mean 'nothing ever picked this up'");
    }

    @Test
    void claimedJustAsTheWaitRanOutIsADifferentSentenceAndABetterOne() {
        // The good case: it IS running. Saying "no answer" here would tell an admin nothing happened while a milestone.
        final FakeUser user = FakeUser.inDiscord();
        outbox.send(AURA, user, aura(10));
        until(user, 1);
        requests.claimSilently(1);
        until(user, 2);

        assertEquals("command.remote.still-running", user.replies.get(1).key());
        assertEquals(
                CommandOutcome.Status.RUNNING, requests.statusOf(1), "the asker must not cancel work already underway");
    }

    @Test
    void aDatabaseThatRefusesTheWriteAnswersRatherThanHanging() {
        requests.failure = new IllegalStateException("connection refused");
        final FakeUser user = FakeUser.inDiscord();
        outbox.send(AURA, user, aura(10));
        until(user, 1);

        assertEquals("command.remote.failed", user.only().key());
        assertEquals(List.of("could not send /smp aura"), warnings);
    }

    @Test
    void theConsolesRequestCarriesNoIdentityWhichTheRowsOwnCheckAlsoSays() {
        final FakeUser console = FakeUser.console();
        outbox.send(AURA, console, aura(1));
        until(console, 1);

        final NewCommandRequest sent = requests.submitted().getFirst();
        assertEquals("CONSOLE", sent.source());
        assertTrue(sent.discordId().isEmpty());
        assertTrue(sent.minecraftId().isEmpty());
    }

    @Test
    void aValueTheDeclarationCannotExpressIsRefusedBeforeAnythingIsWritten() {
        final Declaration word = new Declaration(
                List.of("smp", "objective", "complete"),
                Target.SMP,
                Set.of(Surface.GAME),
                true,
                true,
                List.of(Argument.word("key")));
        final FakeUser user = FakeUser.inDiscord();

        outbox.send(word, user, new Values(word, Map.of("key", "two words")));
        until(user, 1);

        assertEquals("command.remote.failed", user.only().key());
        assertEquals(
                List.of(),
                requests.submitted(),
                "a request that cannot be read on the far side must not be written at all");
    }

    @Test
    void aConsoleUserIsRefusedAnIdentityByTheRowItself() {
        // Belt and braces with the CHECK in V11: the record refuses it too.
        assertThrows(
                IllegalArgumentException.class,
                () -> new NewCommandRequest(
                        Target.SMP.name(),
                        "smp reload",
                        "",
                        "CONSOLE",
                        "console",
                        java.util.Optional.of("100000000000000001"),
                        java.util.Optional.empty(),
                        "en",
                        java.time.Instant.now().plusSeconds(30)));
    }
}
