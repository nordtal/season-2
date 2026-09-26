package eu.nordtal.s2.commands.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.CommandMessages;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.command.CommandOutcome;
import eu.nordtal.s2.common.command.NewCommandRequest;
import eu.nordtal.s2.common.message.Messages;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * The far end of a travelling command.
 *
 * Every case here is one nobody can rehearse: producing them against real processes means running
 * two containers on different versions, or
 * revoking somebody's admin role in the half-second a request is in flight. They are also the cases
 * that matter most: a command that travelled and silently did nothing is indistinguishable, from
 * where it was typed, from one that worked.
 */
class CommandInboxTest {

    private static final Messages MESSAGES =
            Messages.load(CommandInboxTest.class.getClassLoader(), "messages/commands", Locale.ENGLISH, Locale.GERMAN);

    /** What a command here does: append to a list, so a test can see whether it ran. */
    private record Effects(List<String> ran) implements CommandEffects {

        @Override
        public void async(final Runnable work) {
            work.run();
        }

        @Override
        public void warn(final String what, final Throwable failure) {}
    }

    private static final Declaration RELOAD = new Declaration(
            List.of("smp", "reload"), Target.SMP, Set.of(Surface.GAME, Surface.DISCORD), true, false, List.of());

    private static final Declaration AURA = new Declaration(
            List.of("smp", "aura"),
            Target.SMP,
            Set.of(Surface.GAME, Surface.DISCORD),
            true,
            false,
            List.of(Argument.player("player"), Argument.integer("delta", -10000, 10000)));

    private final FakeRequests requests = new FakeRequests();
    private final List<String> warnings = new ArrayList<>();
    private final BiConsumer<String, Throwable> warn = this::recordWarning;

    private void recordWarning(final String message, final Throwable cause) {
        warnings.add(message);
    }

    private CommandInbox inbox(final boolean admin) {
        return new CommandInbox(Target.SMP, requests, MESSAGES, request -> admin, warn);
    }

    private static NordtalCommand<Effects> command(
            final Declaration declaration, final BiConsumer<NordtalUser, Values> body) {
        return new NordtalCommand<>() {
            @Override
            public Declaration declaration() {
                return declaration;
            }

            @Override
            public void run(final NordtalUser user, final Values values, final Effects effects) {
                body.accept(user, values);
            }
        };
    }

    private long submit(final String command, final String arguments) {
        return requests.submit(new NewCommandRequest(
                Target.SMP.name(),
                command,
                arguments,
                "DISCORD",
                "till",
                Optional.of("100000000000000001"),
                Optional.of(UUID.fromString("11111111-2222-3333-4444-555555555555")),
                "en",
                Instant.now().plusSeconds(30)));
    }

    @Test
    void aCommandRunsAndItsAnswerIsWrittenBackIntoTheRow() {
        final Effects effects = new Effects(new ArrayList<>());
        final CommandInbox inbox = inbox(true)
                .register(
                        command(RELOAD, (user, values) -> {
                            effects.ran().add("reload");
                            user.reply(
                                    CommandMessages.MESSAGES.command().remote().silent());
                        }),
                        effects);

        final long id = submit("smp reload", "");
        assertEquals(1, inbox.drain());

        assertEquals(List.of("reload"), effects.ran());
        assertEquals(CommandOutcome.Status.DONE, requests.statusOf(id));
        assertEquals(MESSAGES.get(Locale.ENGLISH, "command.remote.silent"), requests.resultOf(id));
    }

    @Test
    void theAnswerIsRenderedInTheLanguageOnTheRowNotThisServers() {
        final Effects effects = new Effects(new ArrayList<>());
        final long id = requests.submit(new NewCommandRequest(
                Target.SMP.name(),
                "smp reload",
                "",
                "DISCORD",
                "till",
                Optional.of("100000000000000001"),
                Optional.empty(),
                "de",
                Instant.now().plusSeconds(30)));

        inbox(true)
                .register(
                        command(
                                RELOAD,
                                (user, values) -> user.reply(
                                        CommandMessages.MESSAGES.command().cancelled())),
                        effects)
                .drain();

        assertEquals(
                MESSAGES.get(Locale.GERMAN, "command.cancelled"),
                requests.resultOf(id),
                "the answer was rendered in this process's language rather than the asker's -"
                        + " which is why the locale rides on the row instead of being looked up here");
    }

    @Test
    void aCommandThatSaysNothingIsReportedAsHavingDoneSomethingAnyway() {
        // "It worked and said nothing" and "it never ran" look identical to somebody watching a spinner.
        final Effects effects = new Effects(new ArrayList<>());
        final long id = submit("smp reload", "");
        inbox(true)
                .register(command(RELOAD, (user, values) -> effects.ran().add("quiet")), effects)
                .drain();

        assertEquals(CommandOutcome.Status.DONE, requests.statusOf(id));
        assertEquals(MESSAGES.get(Locale.ENGLISH, "command.remote.silent"), requests.resultOf(id));
    }

    @Test
    void anAdminRevokedWhileTheRequestWaitedIsRefusedHere() {
        // The asking surface checked and let it through. This is the second check.
        final Effects effects = new Effects(new ArrayList<>());
        final long id = submit("smp reload", "");
        inbox(false)
                .register(command(RELOAD, (user, values) -> effects.ran().add("reload")), effects)
                .drain();

        assertEquals(List.of(), effects.ran(), "a revoked admin's command still ran");
        assertEquals(
                CommandOutcome.Status.DONE,
                requests.statusOf(id),
                "the command was answered - the answer is no - so it is DONE and not FAILED");
        assertEquals(MESSAGES.get(Locale.ENGLISH, "command.not-admin"), requests.resultOf(id));
    }

    @Test
    void aCommandThisBuildDoesNotHaveIsNamedAsAVersionSkew() {
        final long id = submit("smp teleport", "");
        assertEquals(1, inbox(true).drain());

        assertEquals(CommandOutcome.Status.FAILED, requests.statusOf(id));
        assertTrue(requests.resultOf(id).contains("/smp teleport"), requests.resultOf(id));
    }

    @Test
    void argumentsThisBuildCannotReadSettleTheRowRatherThanLosingIt() {
        final Effects effects = new Effects(new ArrayList<>());
        final long id = submit("smp aura", "not-a-uuid 5");
        inbox(true)
                .register(command(AURA, (user, values) -> effects.ran().add("aura")), effects)
                .drain();

        assertEquals(List.of(), effects.ran());
        assertEquals(CommandOutcome.Status.FAILED, requests.statusOf(id));
        assertFalse(warnings.isEmpty(), "a version skew is worth a log line, not just a reply");
    }

    @Test
    void aCommandThatThrowsSettlesItsRow() {
        // Otherwise the row stays RUNNING for ever and the asker waits out its whole timeout for an answer that was.
        final Effects effects = new Effects(new ArrayList<>());
        final long id = submit("smp reload", "");
        inbox(true)
                .register(
                        command(RELOAD, (user, values) -> {
                            throw new IllegalStateException("the world is not loaded");
                        }),
                        effects)
                .drain();

        assertEquals(CommandOutcome.Status.FAILED, requests.statusOf(id));
        assertEquals(MESSAGES.get(Locale.ENGLISH, "command.remote.failed"), requests.resultOf(id));
        assertFalse(warnings.isEmpty());
    }

    @Test
    void oneWakeUpDrainsEverythingBecauseOneNotificationIsNotOneRow() {
        final Effects effects = new Effects(new ArrayList<>());
        submit("smp reload", "");
        submit("smp reload", "");
        submit("smp reload", "");

        assertEquals(
                3,
                inbox(true)
                        .register(
                                command(RELOAD, (user, values) -> effects.ran().add("reload")), effects)
                        .drain());
        assertEquals(3, effects.ran().size());
    }

    @Test
    void aRequestForAnotherTargetIsLeftAlone() {
        requests.submit(new NewCommandRequest(
                Target.HUNGER_GAMES.name(),
                "hg start",
                "",
                "DISCORD",
                "till",
                Optional.of("100000000000000001"),
                Optional.empty(),
                "en",
                Instant.now().plusSeconds(30)));

        assertEquals(0, inbox(true).drain());
    }

    @Test
    void aRequestThatExpiredWhileItQueuedIsNeverClaimed() {
        // The asker has stopped listening. Running it anyway is how somebody's aura gets corrected twice.
        final Effects effects = new Effects(new ArrayList<>());
        requests.submit(new NewCommandRequest(
                Target.SMP.name(),
                "smp reload",
                "",
                "DISCORD",
                "till",
                Optional.of("100000000000000001"),
                Optional.empty(),
                "en",
                Instant.now().minusSeconds(1)));

        assertEquals(
                0,
                inbox(true)
                        .register(
                                command(RELOAD, (user, values) -> effects.ran().add("reload")), effects)
                        .drain());
        assertEquals(List.of(), effects.ran());
    }

    @Test
    void aDatabaseThatStopsAnsweringEndsTheDrainInsteadOfSpinning() {
        requests.failure = new IllegalStateException("connection refused");
        assertEquals(0, inbox(true).drain());
        assertEquals(List.of("could not claim a command request"), warnings);
    }

    @Test
    void aCommandBelongingToAnotherProcessCannotBeRegisteredHere() {
        final Declaration hungerGames = new Declaration(
                List.of("hg", "start"), Target.HUNGER_GAMES, Set.of(Surface.GAME), true, true, List.of());

        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> inbox(true).register(command(hungerGames, (user, values) -> {}), new Effects(new ArrayList<>())));
        assertTrue(refused.getMessage().contains("HUNGER_GAMES"), refused.getMessage());
    }

    @Test
    void twoCommandsCannotClaimOnePath() {
        final Effects effects = new Effects(new ArrayList<>());
        final CommandInbox inbox = inbox(true).register(command(RELOAD, (user, values) -> {}), effects);

        assertThrows(
                IllegalArgumentException.class, () -> inbox.register(command(RELOAD, (user, values) -> {}), effects));
    }

    @Test
    void effectsThatHandTheirWorkToAnotherThreadAreRefusedAtRegistration() {
        // The failure this prevents is silent and only visible on the surface furthest from the logs: a stuck row.
        record Scheduled(java.util.concurrent.ExecutorService pool) implements CommandEffects {
            @Override
            public void async(final Runnable work) {
                var _ = pool.submit(work);
            }

            @Override
            public void warn(final String what, final Throwable failure) {}
        }

        final var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final var effects = new Scheduled(pool);
            final IllegalArgumentException refused = assertThrows(
                    IllegalArgumentException.class,
                    () -> new CommandInbox(Target.SMP, requests, MESSAGES, request -> true, warn)
                            .register(
                                    new NordtalCommand<Scheduled>() {
                                        @Override
                                        public Declaration declaration() {
                                            return RELOAD;
                                        }

                                        @Override
                                        public void run(
                                                final NordtalUser user, final Values values, final Scheduled given) {}
                                    },
                                    effects));
            assertTrue(refused.getMessage().contains("Runnable::run"), refused.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSchedulerThatFinishesBeforeItReturnsIsStillAScheduler() {
        // The race this pins: a pool thread that picks the task up between `async` returning and the flag being read.
        record Eager() implements CommandEffects {
            @Override
            public void async(final Runnable work) {
                final Thread worker = new Thread(work, "eager-effects");
                worker.start();
                try {
                    worker.join();
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }

            @Override
            public void warn(final String what, final Throwable failure) {}
        }

        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> new CommandInbox(Target.SMP, requests, MESSAGES, request -> true, warn)
                        .register(
                                new NordtalCommand<Eager>() {
                                    @Override
                                    public Declaration declaration() {
                                        return RELOAD;
                                    }

                                    @Override
                                    public void run(final NordtalUser user, final Values values, final Eager given) {}
                                },
                                new Eager()));
        assertTrue(refused.getMessage().contains("Runnable::run"), refused.getMessage());
    }

    @Test
    void effectsThatRunTheWorkInlineAreAcceptedWhichIsTheWholePoint() {
        record Inline() implements CommandEffects {
            @Override
            public void async(final Runnable work) {
                work.run();
            }

            @Override
            public void warn(final String what, final Throwable failure) {}
        }

        final CommandInbox inbox = new CommandInbox(Target.SMP, requests, MESSAGES, request -> true, warn)
                .register(
                        new NordtalCommand<Inline>() {
                            @Override
                            public Declaration declaration() {
                                return RELOAD;
                            }

                            @Override
                            public void run(final NordtalUser user, final Values values, final Inline given) {}
                        },
                        new Inline());

        assertEquals(1, inbox.size());
    }
}
