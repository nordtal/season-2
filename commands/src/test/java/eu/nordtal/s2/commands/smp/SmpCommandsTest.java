package eu.nordtal.s2.commands.smp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.message.context.MilestoneContext;
import eu.nordtal.s2.common.message.context.PlayerContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every decision {@code /smp} takes, without a server.
 *
 * Before this, nothing could be asked: all six of these lived as Brigadier handlers inside one
 * Paper plugin, so "what does
 * {@code /smp objective complete} say when no milestone is active?" was answerable only by starting
 * a server, loading a world, and arranging for no milestone to be active. The cases below are the
 * ones that were therefore never checked - and one of them was wrong: {@code /smp aura} answered an
 * unlinked target with the message written for a <em>player</em> about their <em>own</em> account.
 */
class SmpCommandsTest {

    private static final UUID SOMEBODY = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final FakeSmp smp = new FakeSmp();

    private FakeUser run(final NordtalCommand<SmpEffects> command, final Map<String, Object> values) {
        final FakeUser user = FakeUser.inGame();
        command.run(user, new Values(command.declaration(), values), smp);
        return user;
    }

    @Test
    void theTwoThatCannotBeUndoneAskFirstAndTheOthersDoNot() {
        // A flag on everything that writes is a flag nobody reads - /smp aura is unguarded because it is its own undo.
        assertEquals(
                Set.of("/smp objective complete", "/smp milestone unlock"),
                SmpCommands.declarations().stream()
                        .filter(Declaration::irreversible)
                        .map(Declaration::name)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void everySmpDeclarationIsAnAdminsAndOnTheConsoleAndWebOnly() {
        // Every admin command lost Surface.GAME and Surface.DISCORD. The two a player types - /aura and /smp status.
        for (final Declaration declaration : SmpCommands.declarations()) {
            assertTrue(declaration.adminOnly(), declaration.name() + " is not admin-only");
            // Console must never be lost, and game/Discord must both be gone.
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
    void reloadSaysSoAndARefusalNamesTheConsoleRatherThanSwallowingIt() {
        assertEquals(
                List.of("smp.admin.reloaded"), run(new ReloadSmp(), Map.of()).keys());
        assertEquals(List.of("reload"), smp.did);

        smp.failure = new IllegalStateException("milestones.yml is not valid");
        assertEquals(
                List.of("smp.admin.reload-failed"),
                run(new ReloadSmp(), Map.of()).keys());
        assertEquals(List.of("/smp reload failed"), smp.warnings);
    }

    @Test
    void aRefusedTrackIsItsOwnAnswerAndItNamesWhatTheFileDisagreesWith() {
        // Not the same thing as a reload that threw. The file parsed.
        smp.trackRefused = List.of(
                "ancient-debris: has stored progress but is not declared in the file any more.",
                "logs/oak: changed type from STATISTIC to HAND_IN");

        final var user = run(new ReloadSmp(), Map.of());
        assertEquals(List.of("smp.admin.track-refused"), user.keys());
        final String printed = String.valueOf(user.only().of("problems"));
        assertTrue(
                printed.contains("ancient-debris") && printed.contains("logs/oak"),
                "the answer does not name both problems: " + printed);
        assertEquals(
                List.of("reload"),
                smp.did,
                "the reload did not run - the sounds and the wording are re-read regardless");
    }

    @Test
    void noActiveMilestoneAndNoSuchObjectiveAreDifferentSentences() {
        // Folding them into one would leave an admin re-reading the milestone file for a key that is in it.
        assertEquals(
                List.of("smp.admin.no-active-milestone"),
                run(new CompleteObjective(), Map.of("key", "netherite")).keys());

        smp.activeMilestone = "the-nether";
        assertEquals(
                List.of("smp.admin.no-such-objective"),
                run(new CompleteObjective(), Map.of("key", "netherite")).keys());
        assertEquals(List.of(), smp.did, "nothing was paid out for an objective that does not exist");
    }

    @Test
    void completingAnObjectiveNamesBothItAndItsMilestone() {
        smp.activeMilestone = "the-nether";
        smp.objectives = List.of("netherite");

        final FakeUser user = run(new CompleteObjective(), Map.of("key", "netherite"));

        assertEquals("smp.admin.objective-completed", user.only().key());
        assertEquals("netherite", user.only().of("key"));
        assertEquals(new MilestoneContext("the-nether"), user.only().of("milestone"));
        assertEquals(List.of("complete the-nether/netherite"), smp.did);
    }

    @Test
    void unlockingAMilestoneDoesNotCheckTheKeyFirstAndSaysWhichOneItWas() {
        // The engine is the only thing that knows the whole track, active milestones included.
        final FakeUser user = run(new UnlockMilestone(), Map.of("key", "the-end"));

        assertEquals("smp.admin.milestone-unlocked", user.only().key());
        assertEquals("the-end", user.only().of("key"));
        assertEquals(List.of("unlock the-end"), smp.did);
    }

    @Test
    void anUnlinkedTargetIsToldAboutNotToldOff() {
        // Not smp.error.no-account-link, which is addressed to the player, not to the admin asking.
        smp.names.put(SOMEBODY, "Steve");

        final FakeUser user = run(new ChangeAura(), Map.of("player", SOMEBODY, "delta", -25));

        assertEquals("smp.admin.target-unlinked", user.only().key());
        assertEquals(new PlayerContext("Steve"), user.only().of("player"));
        assertEquals(List.of(), smp.did);
    }

    @Test
    void aCorrectionRecordsWhoMadeIt() {
        // An unexplained balance is what the reason column exists to prevent.
        smp.names.put(SOMEBODY, "Steve");
        smp.links.put(SOMEBODY, "100000000000000009");

        final FakeUser user = run(new ChangeAura(), Map.of("player", SOMEBODY, "delta", -25));

        assertEquals(List.of("aura 100000000000000009 -25 by tester"), smp.did);
        assertEquals("smp.admin.aura-changed", user.only().key());
        assertEquals(new PlayerContext("Steve"), user.only().of("player"));
        assertEquals(-25, user.only().of("delta"));
    }

    @Test
    void aPlayerThisServerHasNeverSeenIsNamedByUuidRatherThanNotAtAll() {
        // Reachable now that the command can arrive from Discord about somebody who is not here.
        final FakeUser user = run(new ChangeAura(), Map.of("player", SOMEBODY, "delta", 1));
        assertEquals(new PlayerContext(SOMEBODY.toString()), user.only().of("player"));
    }

    @Test
    void anUnlinkedAccountIsSaidPlainlyBecauseItMeansSomethingElseIsWrong() {
        smp.names.put(SOMEBODY, "Steve");
        smp.access = new SmpEffects.Access(null, false, null);

        assertEquals(
                List.of("smp.access.unlinked"),
                run(new ShowAccess(), Map.of("player", SOMEBODY)).keys());
    }

    @Test
    void linkedActiveAndAPurchaseWithAPaymentLinkWaiting() {
        smp.names.put(SOMEBODY, "Steve");
        smp.access = new SmpEffects.Access("100000000000000009", true, Instant.parse("2026-10-01T00:00:00Z"));
        smp.payment = FakeSmp.payment(true);

        assertEquals(
                List.of("smp.access.linked", "smp.access.active", "smp.access.payment"),
                run(new ShowAccess(), Map.of("player", SOMEBODY)).keys());
    }

    @Test
    void aPurchaseWithNoPaymentLinkIsADifferentLineAndThatIsThePoint() {
        // "Chose 60 days" and "asked for a payment link" are different problems to chase.
        smp.names.put(SOMEBODY, "Steve");
        smp.access = new SmpEffects.Access("100000000000000009", false, null);
        smp.payment = FakeSmp.payment(false);

        assertEquals(
                List.of("smp.access.linked", "smp.access.never", "smp.access.payment-unstarted"),
                run(new ShowAccess(), Map.of("player", SOMEBODY)).keys());
    }

    @Test
    void expiredAccessReadsDifferentlyFromAccessThatNeverExisted() {
        smp.names.put(SOMEBODY, "Steve");
        smp.access = new SmpEffects.Access("100000000000000009", false, Instant.parse("2026-08-01T00:00:00Z"));

        assertEquals(
                List.of("smp.access.linked", "smp.access.expired", "smp.access.no-payment"),
                run(new ShowAccess(), Map.of("player", SOMEBODY)).keys());
    }

    @Test
    void aFailureReadingThePaymentKeepsTheAccessLineWhichIsWhatWasAskedFor() {
        // The two reads behind this command are separate on purpose. Losing the access line because the second query.
        smp.names.put(SOMEBODY, "Steve");
        smp.access = new SmpEffects.Access("100000000000000009", true, Instant.parse("2026-10-01T00:00:00Z"));
        smp.paymentFailure = new IllegalStateException("the database did not answer");

        assertEquals(
                List.of("smp.access.linked", "smp.access.active", "smp.access.payment-unknown"),
                run(new ShowAccess(), Map.of("player", SOMEBODY)).keys());
    }
}
