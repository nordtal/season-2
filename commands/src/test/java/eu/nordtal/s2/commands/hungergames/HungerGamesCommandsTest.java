package eu.nordtal.s2.commands.hungergames;

import eu.nordtal.s2.commands.Confirmations;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Values;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything {@code /hg} decides, without a server, a lobby or twenty people on towers.
 *
 * <h2>The command this is really about</h2>
 * {@code /hg start} decides the whole event and could previously be exercised only by registering
 * enough real accounts for a real game. Every branch below - the wrong state, the arithmetic floor,
 * the warning, the expired confirmation, the stale one - was therefore reasoned about and never run.
 */
class HungerGamesCommandsTest {

    private final FakeHungerGames hg = new FakeHungerGames();

    /** One command with an optional trailing word, which is what the second step now is. */
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

    // ------------------------------------------------------------------ the declarations

    @Test
    @DisplayName("none of them is marked irreversible, and that is the decision not an oversight")
    void startKeepsItsOwnTwoStep() {
        // Declaring /hg start irreversible would give it the catalogue's generic gate, which arms on
        // a miss and keys on the typed line - so a /hg start with forty participants would demand a
        // retype, and the sentence it demanded it with would say "this cannot be undone" instead of
        // naming the number that matters. Decided with the owner, 2026-09-05.
        assertEquals(List.of(), HungerGamesCommands.declarations().stream()
                .filter(Declaration::irreversible)
                .map(Declaration::name)
                .toList());
    }

    @Test
    @DisplayName("all four are admin-only and reachable from Discord and the console")
    void allFourAreEverywhere() {
        for (final Declaration declaration : HungerGamesCommands.declarations()) {
            assertTrue(declaration.adminOnly(), declaration.name());
            assertTrue(declaration.surfaces().containsAll(
                            List.of(Surface.GAME, Surface.DISCORD, Surface.CONSOLE)),
                    declaration.name() + " is not on every surface - the gap that made the start of"
                            + " the season's flagship event depend on one client");
        }
    }

    // ------------------------------------------------------------------ /hg start

    @Test
    @DisplayName("no game registered is its own sentence")
    void noGame() {
        assertEquals(List.of("hg.start.no-game"), run(start).keys());
        assertEquals(List.of(), hg.did);
    }

    @Test
    @DisplayName("a game that is not in registration names the state it is in")
    void wrongState() {
        hg.registration = new HungerGamesEffects.Registration(FakeHungerGames.GAME, "RUNNING", 20);

        final FakeUser user = run(start);
        assertEquals("hg.start.wrong-state", user.only().key());
        assertEquals("RUNNING", user.only().of("state"));
    }

    @Test
    @DisplayName("below the arithmetic floor it refuses outright and never arms a confirmation")
    void belowTheHardMinimum() {
        // The border step divides by the participant count. Below two there is no game to shrink a
        // border around, so this is not a judgement call and cannot be confirmed past.
        hg.registration = FakeHungerGames.registered(1);

        final FakeUser first = run(start);
        assertEquals("hg.start.below-hard-minimum", first.only().key());

        final FakeUser second = confirm(FakeUser.inGame());
        assertEquals("hg.start.below-hard-minimum", second.only().key(),
                "a confirmation carried a game past the arithmetic floor");
        assertEquals(List.of(), hg.did);
    }

    @Test
    @DisplayName("above the recommended minimum it starts at once")
    void aboveTheSoftMinimum() {
        hg.registration = FakeHungerGames.registered(20);

        final FakeUser user = run(start);
        assertEquals("hg.start.started", user.only().key());
        assertEquals(20, user.only().of("count"));
        assertEquals(List.of("logged tester", "start " + FakeHungerGames.GAME), hg.did);
    }

    @Test
    @DisplayName("below it, the warning carries the numbers and the game does not start")
    void belowTheSoftMinimumWarns() {
        hg.registration = FakeHungerGames.registered(4);

        final FakeUser user = run(start);
        assertEquals("hg.start.below-soft-minimum", user.only().key());
        assertEquals(4, user.only().of("count"));
        assertEquals(8, user.only().of("minimum"));
        assertEquals(Confirmations.WINDOW.toSeconds(), user.only().of("seconds"));
        assertEquals(List.of(), hg.did, "the warning started the game anyway");
    }

    @Test
    @DisplayName("the warning is what makes the confirmation spendable")
    void theWarningArmsAndTheConfirmSpends() {
        hg.registration = FakeHungerGames.registered(4);
        final FakeUser user = FakeUser.inGame();

        run(start, user);
        confirm(user);

        assertEquals(List.of("hg.start.below-soft-minimum", "hg.start.started"), user.keys());
        assertEquals(List.of("logged tester (confirmed)", "start " + FakeHungerGames.GAME), hg.did);
    }

    @Test
    @DisplayName("a bare confirm typed twice never starts a game, which the first version did")
    void confirmNeverArmsItself() {
        // The bug this exists for: Confirmations#confirm arms on a miss, so a confirm implemented
        // with it would arm itself on the first call and go through on the second, having never
        // shown the warning it exists for.
        hg.registration = FakeHungerGames.registered(4);
        final FakeUser user = FakeUser.inGame();

        confirm(user);
        confirm(user);

        assertEquals(List.of("hg.start.confirm-expired", "hg.start.confirm-expired"), user.keys());
        assertEquals(List.of(), hg.did);
    }

    @Test
    @DisplayName("an admin who always types the second step can still start a healthy game")
    void confirmOnAGameThatNeedsNoConfirmation() {
        // Until 2026-09-05 this answered "that confirmation expired": the trailing word alone made
        // it a confirmation, nothing had armed one because the count was fine, and consume() then
        // missed. Nothing had expired, and the event did not start.
        hg.registration = FakeHungerGames.registered(20);
        final FakeUser user = FakeUser.inGame();

        confirm(user);

        assertEquals(List.of("hg.start.started"), user.keys());
        assertEquals(List.of("logged tester (confirmed)", "start " + FakeHungerGames.GAME), hg.did);
    }

    @Test
    @DisplayName("a start that throws is said so, and not after 'the event is starting'")
    void aStartThatFails() {
        // The reply used to go out before effects.start(...) and start(...) carried no catch, so a
        // failure told the admin the event had begun and left them nothing to act on.
        hg.registration = FakeHungerGames.registered(20);
        hg.startFailure = new IllegalStateException("the world is not loaded");

        final FakeUser user = run(start);
        assertEquals(List.of("hg.start.failed"), user.keys());
        assertEquals(1, hg.warnings.size(), "it still has to reach the operator");
    }

    @Test
    @DisplayName("another admin cannot spend the warning shown to the first")
    void aConfirmationBelongsToWhoeverWasWarned() {
        hg.registration = FakeHungerGames.registered(4);

        run(start, FakeUser.inGame());
        final FakeUser somebodyElse = confirm(FakeUser.inDiscord());

        assertEquals("hg.start.confirm-expired", somebodyElse.only().key());
        assertEquals(List.of(), hg.did);
    }

    @Test
    @DisplayName("a start that needed no warning clears a stale one from a previous game")
    void aStaleWarningIsNotCarriedForward() {
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
    @DisplayName("a database that does not answer is said out loud rather than read as 'no game'")
    void aFailedReadIsNotAnEmptyOne() {
        // Folding the two would tell an admin there is no event registered at the moment the event
        // is about to start, which is the worst possible wrong answer here.
        hg.failure = new IllegalStateException("connection refused");

        assertEquals(List.of("hg.start.read-failed"), run(start).keys());
        assertEquals(List.of("/hg start could not read the registration"), hg.warnings);
    }

    // ------------------------------------------------------------------ /hg ready-status

    @Test
    @DisplayName("ready-status lists every team with its status as a translated phrase")
    void readyStatus() {
        hg.registration = FakeHungerGames.registered(4);
        hg.teams = List.of(new HungerGamesEffects.TeamReady("Rot", true),
                new HungerGamesEffects.TeamReady("Blau", false));

        final FakeUser user = run(new ReadyStatus());

        assertEquals(List.of("hg.ready-status.header", "hg.ready-status.line",
                "hg.ready-status.line"), user.keys());
        assertEquals("<hg.ready-status.ready>", user.replies.get(1).of("status"));
        assertEquals("<hg.ready-status.not-ready>", user.replies.get(2).of("status"));
    }

    @Test
    @DisplayName("ready-status with no game says so rather than printing an empty list")
    void readyStatusWithoutAGame() {
        assertEquals(List.of("hg.start.no-game"), run(new ReadyStatus()).keys());
    }

    // ------------------------------------------------------------------ /hg reload

    @Test
    @DisplayName("the sounds are reloaded first, and a failure in either is one sentence")
    void reload() {
        // Sounds first because they are the cheapest thing to get wrong and the only one an operator
        // is expected to be iterating on while somebody waits to hear the result.
        assertEquals(List.of("hg.admin.reloaded"), run(new ReloadHungerGames()).keys());
        assertEquals(List.of("reload sounds", "reload messages"), hg.did);

        hg.did.clear();
        hg.messagesReload = false;
        assertEquals(List.of("hg.admin.reload-failed"), run(new ReloadHungerGames()).keys());
        assertEquals(List.of("reload sounds", "reload messages"), hg.did,
                "a broken sounds.yml must not stop a corrected message from being re-read");

        // And the other way round, which the sentence above claimed and no case checked: an early
        // return after a failed reloadSounds() would have kept this test green while skipping the
        // message reload entirely.
        hg.did.clear();
        hg.messagesReload = true;
        hg.soundsReload = false;
        assertEquals(List.of("hg.admin.reload-failed"), run(new ReloadHungerGames()).keys());
        assertEquals(List.of("reload sounds", "reload messages"), hg.did,
                "a broken sounds.yml must not stop the messages from being re-read");
    }
}
