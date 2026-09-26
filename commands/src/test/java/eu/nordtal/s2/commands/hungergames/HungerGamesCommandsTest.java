package eu.nordtal.s2.commands.hungergames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.Confirmations;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Values;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Everything {@code /hg} decides, without a server, a lobby or twenty people on towers.
 *
 * The command this is really about is {@code /hg start}: it decides the whole event and would
 * otherwise need enough real accounts for a real game to exercise. Every branch below - the wrong
 * state, the arithmetic floor, the warning, the expired confirmation, the stale one - is reasoned
 * about here instead of run against one.
 */
class HungerGamesCommandsTest {

    private final FakeHungerGames hg = new FakeHungerGames();

    /** One command with an optional trailing word, which is the second step. */
    private final StartGame start = new StartGame();

    private FakeUser run(final NordtalCommand<HungerGamesEffects> command, final FakeUser user) {
        command.run(user, Values.none(command.declaration()), hg);
        return user;
    }

    /** {@code /hg start confirm} - the same command, with its optional word supplied. */
    private FakeUser confirm(final FakeUser user) {
        start.run(user, new Values(start.declaration(), Map.of("confirm", "confirm")), hg);
        return user;
    }

    private FakeUser run(final NordtalCommand<HungerGamesEffects> command) {
        return run(command, FakeUser.inGame());
    }

    @Test
    void noneOfThemIsMarkedIrreversibleAndThatIsTheDecisionNotAnOversight() {
        // Declaring /hg start irreversible would give it the catalogue's generic gate.
        assertEquals(
                List.of(),
                HungerGamesCommands.declarations().stream()
                        .filter(Declaration::irreversible)
                        .map(Declaration::name)
                        .toList());
    }

    @Test
    void allFourAreAdminOnlyConsoleReachableAndOffGameAndDiscordOps18() {
        // Every admin command lost Surface.GAME and Surface.DISCORD. /hg start keeps Surface.WEB too.
        for (final Declaration declaration : HungerGamesCommands.declarations()) {
            assertTrue(declaration.adminOnly(), declaration.name());
            assertTrue(
                    declaration.surfaces().contains(Surface.CONSOLE),
                    declaration.name() + " lost the console, which must never happen");
            assertFalse(
                    declaration.surfaces().contains(Surface.GAME), declaration.name() + " is still reachable in game");
            assertFalse(
                    declaration.surfaces().contains(Surface.DISCORD),
                    declaration.name() + " is still reachable from Discord");
        }
    }

    @Test
    void noGameRegisteredIsItsOwnSentence() {
        assertEquals(List.of("hg.start.no-game"), run(start).keys());
        assertEquals(List.of(), hg.did);
    }

    @Test
    void aGameThatIsNotInRegistrationNamesTheStateItIsIn() {
        hg.registration = new HungerGamesEffects.Registration(FakeHungerGames.GAME, "RUNNING", 20);

        final FakeUser user = run(start);
        assertEquals("hg.start.wrong-state", user.only().key());
        assertEquals("RUNNING", user.only().of("state"));
    }

    @Test
    void belowTheArithmeticFloorItRefusesOutrightAndNeverArmsAConfirmation() {
        // The border step divides by the participant count. Below two there is no game to shrink a border around.
        hg.registration = FakeHungerGames.registered(1);

        final FakeUser first = run(start);
        assertEquals("hg.start.below-hard-minimum", first.only().key());

        final FakeUser second = confirm(FakeUser.inGame());
        assertEquals(
                "hg.start.below-hard-minimum",
                second.only().key(),
                "a confirmation carried a game past the arithmetic floor");
        assertEquals(List.of(), hg.did);
    }

    @Test
    void aboveTheRecommendedMinimumItStartsAtOnce() {
        hg.registration = FakeHungerGames.registered(20);

        final FakeUser user = run(start);
        assertEquals("hg.start.started", user.only().key());
        assertEquals(20, user.only().of("count"));
        assertEquals(List.of("logged tester", "start " + FakeHungerGames.GAME), hg.did);
    }

    @Test
    void belowItTheWarningCarriesTheNumbersAndTheGameDoesNotStart() {
        hg.registration = FakeHungerGames.registered(4);

        final FakeUser user = run(start);
        assertEquals("hg.start.below-soft-minimum", user.only().key());
        assertEquals(4, user.only().of("count"));
        assertEquals(8, user.only().of("minimum"));
        assertEquals(Confirmations.WINDOW.toSeconds(), user.only().of("seconds"));
        assertEquals(List.of(), hg.did, "the warning started the game anyway");
    }

    @Test
    void theWarningIsWhatMakesTheConfirmationSpendable() {
        hg.registration = FakeHungerGames.registered(4);
        final FakeUser user = FakeUser.inGame();

        run(start, user);
        confirm(user);

        assertEquals(List.of("hg.start.below-soft-minimum", "hg.start.started"), user.keys());
        assertEquals(List.of("logged tester (confirmed)", "start " + FakeHungerGames.GAME), hg.did);
    }

    @Test
    void aBareConfirmTypedTwiceNeverStartsAGameWhichTheFirstVersionDid() {
        // The bug this exists for: Confirmations#confirm arms on a miss.
        hg.registration = FakeHungerGames.registered(4);
        final FakeUser user = FakeUser.inGame();

        confirm(user);
        confirm(user);

        assertEquals(List.of("hg.start.confirm-expired", "hg.start.confirm-expired"), user.keys());
        assertEquals(List.of(), hg.did);
    }

    @Test
    void anAdminWhoAlwaysTypesTheSecondStepCanStillStartAHealthyGame() {
        // A bare trailing word alone must not read as a confirmation when nothing armed one.
        hg.registration = FakeHungerGames.registered(20);
        final FakeUser user = FakeUser.inGame();

        confirm(user);

        assertEquals(List.of("hg.start.started"), user.keys());
        assertEquals(List.of("logged tester (confirmed)", "start " + FakeHungerGames.GAME), hg.did);
    }

    @Test
    void aStartThatThrowsIsSaidSoAndNotAfterTheEventIsStarting() {
        // Guards against a reply sent before effects.start(...), which would claim a game that never started.
        hg.registration = FakeHungerGames.registered(20);
        hg.startFailure = new IllegalStateException("the world is not loaded");

        final FakeUser user = run(start);
        assertEquals(List.of("hg.start.failed"), user.keys());
        assertEquals(1, hg.warnings.size(), "it still has to reach the operator");
    }

    @Test
    void anotherAdminCannotSpendTheWarningShownToTheFirst() {
        hg.registration = FakeHungerGames.registered(4);

        run(start, FakeUser.inGame());
        final FakeUser somebodyElse = confirm(FakeUser.inDiscord());

        assertEquals("hg.start.confirm-expired", somebodyElse.only().key());
        assertEquals(List.of(), hg.did);
    }

    @Test
    void aStartThatNeededNoWarningClearsAStaleOneFromAPreviousGame() {
        hg.registration = FakeHungerGames.registered(4);
        final FakeUser user = FakeUser.inGame();
        run(start, user);

        // People arrive; the same admin starts it normally.
        hg.registration = FakeHungerGames.registered(20);
        run(start, user);
        hg.did.clear();

        // A later game, below the minimum again: the old warning must not be spendable.
        hg.registration = FakeHungerGames.registered(3);
        final FakeUser later = confirm(user);
        assertEquals("hg.start.confirm-expired", later.keys().getLast());
        assertFalse(hg.did.stream().anyMatch(what -> what.startsWith("start ")));
    }

    @Test
    void aDatabaseThatDoesNotAnswerIsSaidOutLoudRatherThanReadAsNoGame() {
        // Folding the two would tell an admin there is no event registered at the moment the event is about to start.
        hg.failure = new IllegalStateException("connection refused");

        assertEquals(List.of("hg.start.read-failed"), run(start).keys());
        assertEquals(List.of("/hg start could not read the registration"), hg.warnings);
    }

    @Test
    void readyStatusListsEveryTeamWithItsStatusAsATranslatedPhrase() {
        hg.registration = FakeHungerGames.registered(4);
        hg.teams =
                List.of(new HungerGamesEffects.TeamReady("Rot", true), new HungerGamesEffects.TeamReady("Blau", false));

        final FakeUser user = run(new ReadyStatus());

        assertEquals(List.of("hg.ready-status.header", "hg.ready-status.line", "hg.ready-status.line"), user.keys());
        assertEquals("<hg.ready-status.ready>", user.replies.get(1).of("status"));
        assertEquals("<hg.ready-status.not-ready>", user.replies.get(2).of("status"));
    }

    @Test
    void readyStatusWithNoGameSaysSoRatherThanPrintingAnEmptyList() {
        assertEquals(List.of("hg.start.no-game"), run(new ReadyStatus()).keys());
    }

    @Test
    void theSoundsAreReloadedFirstAndAFailureInEitherIsOneSentence() {
        // Sounds first because they are the cheapest thing to get wrong and the only one an operator notices at all.
        assertEquals(List.of("hg.admin.reloaded"), run(new ReloadHungerGames()).keys());
        assertEquals(List.of("reload sounds", "reload messages"), hg.did);

        hg.did.clear();
        hg.messagesReload = false;
        assertEquals(
                List.of("hg.admin.reload-failed"), run(new ReloadHungerGames()).keys());
        assertEquals(
                List.of("reload sounds", "reload messages"),
                hg.did,
                "a broken sounds.yml must not stop a corrected message from being re-read");

        // And the other way round, which the sentence above claimed and no case checked until now.
        hg.did.clear();
        hg.messagesReload = true;
        hg.soundsReload = false;
        assertEquals(
                List.of("hg.admin.reload-failed"), run(new ReloadHungerGames()).keys());
        assertEquals(
                List.of("reload sounds", "reload messages"),
                hg.did,
                "a broken sounds.yml must not stop the messages from being re-read");
    }
}
