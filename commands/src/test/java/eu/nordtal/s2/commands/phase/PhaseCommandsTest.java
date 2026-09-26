package eu.nordtal.s2.commands.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.SeasonDateRefused;
import eu.nordtal.s2.common.phase.SeasonDates;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What {@code /phase} decides, asserted without a proxy and without a guild.
 *
 * These tests do <b>not</b> prove that either adapter registers the command, parses its
 * arguments, or renders the keys; those still need a running proxy and a real guild.
 */
class PhaseCommandsTest {

    @Test
    void allFourSubcommandsAreAdminOnlyRunOnTheProxyAndAreOffChatEntirely() {
        for (final var declaration :
                List.of(PhaseCommands.SHOW, PhaseCommands.SET, PhaseCommands.LAUNCH, PhaseCommands.SMP_START)) {
            assertEquals(Target.PROXY, declaration.target(), declaration.name());
            assertTrue(declaration.adminOnly(), declaration.name());
            // Every admin command is console and web, and WEB as well as CONSOLE because a WEB row carries the asker's.
            assertEquals(java.util.Set.of(Surface.CONSOLE, Surface.WEB), declaration.surfaces(), declaration.name());
        }
    }

    @Test
    void theFlagMarksWhatCannotBeUndoneAndNotSimplyEverythingThatWrites() {
        assertFalse(PhaseCommands.SHOW.irreversible(), "reading changes nothing");
        assertTrue(PhaseCommands.SET.irreversible(), "a switch to SMP disconnects every player without active access");
        assertTrue(
                PhaseCommands.SMP_START.irreversible(),
                "moving smp-start shifts access periods belonging to people who are not in the room,"
                        + " and moving it back shifts them again rather than undoing it");

        // Deliberately not flagged: setting the opening again is an exact undo.
        assertFalse(PhaseCommands.LAUNCH.irreversible());
    }

    @Test
    void everyPhaseHasAConsequenceSentenceOfItsOwn() {
        // One key per constant, so that a new phase produces a missing key rather than silently telling an admin what.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertEquals(
                    "phase.consequence." + phase.name(),
                    SetPhase.consequence(phase).key());
        }
    }

    @Test
    void aProcessThatCachesThePhaseSaysItBeforeItAsksTheDatabase() {
        // This is the command somebody runs while the network is misbehaving.
        final FakeEffects effects = new FakeEffects();
        effects.observation = new PhaseEffects.Observation(SeasonPhase.SMP, true, null);
        effects.readFailure = new IllegalStateException("the database is not there");
        final FakeUser user = FakeUser.inGame();

        new ShowPhase().run(user, Values.none(PhaseCommands.SHOW), effects);

        assertEquals(List.of("phase.current", "phase.read.failed"), user.keys());
        assertEquals("SMP", user.replies.getFirst().of("phase"));
        assertEquals(List.of("reading the season dates for /phase show"), effects.warnings);
    }

    @Test
    void aPhaseThatWasNeverReadIsSaidDifferentlyFromOneThatWas() {
        final FakeEffects effects = new FakeEffects();
        effects.observation = new PhaseEffects.Observation(SeasonPhase.MAINTENANCE, false, null);
        final FakeUser user = FakeUser.inGame();

        new ShowPhase().run(user, Values.none(PhaseCommands.SHOW), effects);

        assertEquals(List.of("phase.current.unread", "phase.dates"), user.keys());
    }

    @Test
    void aProcessThatCachesNothingReadsThePhaseAndStillSaysItFirst() {
        final FakeEffects effects = new FakeEffects();
        effects.current = SeasonPhase.START_EVENT;
        effects.launch = SeasonDates.parse("2026-10-01 18:00").orElseThrow();
        final FakeUser user = FakeUser.inDiscord();

        new ShowPhase().run(user, Values.none(PhaseCommands.SHOW), effects);

        assertEquals(List.of("phase.current", "phase.dates"), user.keys());
        assertEquals("START_EVENT", user.replies.getFirst().of("phase"));
        assertEquals(SeasonDates.format(effects.launch), user.replies.get(1).of("launch"));
        assertEquals(SeasonDates.ZONE.getId(), user.replies.get(1).of("zone"));
    }

    @Test
    void aProcessWithNoCacheAndNoDatabaseAnswersWithItsOwnKeyNotTheProxys() {
        // Two keys and not one, because phase.read.failed says "the phase above" and on this path there is nothing.
        final FakeEffects effects = new FakeEffects();
        effects.readFailure = new IllegalStateException("the database is not there");
        final FakeUser user = FakeUser.inDiscord();

        new ShowPhase().run(user, Values.none(PhaseCommands.SHOW), effects);

        assertEquals(List.of("phase.read.failed.only"), user.keys());
        assertEquals(1, effects.warnings.size(), "it still has to be reported to the operator");
    }

    @Test
    void aPhaseLinePrintedFromInsideTheReadStillCountsAsOneAbove() {
        // No cache, so the phase line and the dates share one try: currentPhase() can still fail after it printed.
        final FakeEffects effects = new FakeEffects();
        effects.datesFailure = new IllegalStateException("the dates are not there");
        final FakeUser user = FakeUser.inDiscord();

        new ShowPhase().run(user, Values.none(PhaseCommands.SHOW), effects);

        assertEquals(List.of("phase.current", "phase.read.failed"), user.keys());
    }

    @Test
    void aProcessThatAlreadyPrintedThePhaseStillOwesAWordAboutTheDates() {
        final FakeEffects effects = new FakeEffects();
        effects.observation = new PhaseEffects.Observation(SeasonPhase.SMP, true, null);
        effects.readFailure = new IllegalStateException("the database is not there");
        final FakeUser user = FakeUser.inGame();

        new ShowPhase().run(user, Values.none(PhaseCommands.SHOW), effects);

        assertEquals(List.of("phase.current", "phase.read.failed"), user.keys());
    }

    @Test
    void aSwitchIsWrittenRecordedPropagatedAndReportedInThatOrder() {
        final FakeEffects effects = new FakeEffects();
        effects.current = SeasonPhase.PRE_LAUNCH;
        final FakeUser user = FakeUser.inDiscord();

        new SetPhase().run(user, new Values(PhaseCommands.SET, Map.of("phase", "SMP")), effects);

        assertEquals(SeasonPhase.SMP, effects.current);
        assertEquals(1, effects.recordedSwitches.size(), "the admin channel or the log has to hear");
        assertEquals(
                1,
                effects.afterWrites,
                "a process that caches the phase must not wait for its own notification to come"
                        + " back around, or the reply and the log disagree");
        assertEquals("phase.changed", user.only().key());
        assertEquals("PRE_LAUNCH", user.only().of("previous"));
        assertEquals("SMP", user.only().of("current"));
    }

    @Test
    void switchingToThePhaseItAlreadyIsIsReportedAsSuchAndStillAudited() {
        final FakeEffects effects = new FakeEffects();
        effects.current = SeasonPhase.SMP;
        final FakeUser user = FakeUser.inGame();

        new SetPhase().run(user, new Values(PhaseCommands.SET, Map.of("phase", "SMP")), effects);

        assertEquals("phase.unchanged", user.only().key());
        assertEquals("SMP", user.only().of("phase"));
        assertEquals(1, effects.recordedSwitches.size());
    }

    @Test
    void anUnknownPhaseNameIsRefusedWithoutTouchingTheDatabase() {
        // SeasonPhase.fromDatabase answers MAINTENANCE to anything it does not recognise.
        final FakeEffects effects = new FakeEffects();
        effects.current = SeasonPhase.SMP;
        final FakeUser user = FakeUser.inGame();

        new SetPhase().run(user, new Values(PhaseCommands.SET, Map.of("phase", "SEASON_OVER")), effects);

        assertEquals("phase.unknown", user.only().key());
        assertEquals("SEASON_OVER", user.only().of("value"));
        assertEquals(SeasonPhase.SMP, effects.current, "nothing may have been written");
        assertEquals(0, effects.afterWrites);
    }

    @Test
    void aPhaseNameIsCaseInsensitiveBecauseOneSurfaceTypesItByHand() {
        final FakeEffects effects = new FakeEffects();
        new SetPhase().run(FakeUser.inGame(), new Values(PhaseCommands.SET, Map.of("phase", "maintenance")), effects);

        assertEquals(SeasonPhase.MAINTENANCE, effects.current);
    }

    @Test
    void aFailedWriteReportsTheFailureAndDoesNotClaimASwitchHappened() {
        final FakeEffects effects = new FakeEffects();
        effects.writeFailure = new IllegalStateException("the database did not accept it");
        final FakeUser user = FakeUser.inGame();

        new SetPhase().run(user, new Values(PhaseCommands.SET, Map.of("phase", "SMP")), effects);

        assertEquals("phase.failed", user.only().key());
        assertEquals(0, effects.recordedSwitches.size());
        assertEquals(0, effects.afterWrites);
    }

    @Test
    void theAuditTrailRecordsTheDiscordIdAndSaysWhichSurfaceItCameFrom() {
        final FakeEffects effects = new FakeEffects();
        new SetPhase().run(FakeUser.inDiscord(), new Values(PhaseCommands.SET, Map.of("phase", "SMP")), effects);
        assertEquals("100000000000000002", effects.lastActor);
        assertTrue(effects.lastReason.contains("DISCORD"), effects.lastReason);
        assertTrue(effects.lastReason.contains("tester"), effects.lastReason);

        // An asker with no Discord id writes a null actor rather than a placeholder string.
        final FakeEffects fromConsole = new FakeEffects();
        new SetPhase().run(FakeUser.console(), new Values(PhaseCommands.SET, Map.of("phase", "SMP")), fromConsole);
        assertNull(fromConsole.lastActor);
        assertTrue(fromConsole.lastReason.contains("CONSOLE"), fromConsole.lastReason);
    }

    @Test
    void aDateThatIsNotADateIsRefusedBeforeAnythingIsDeferred() {
        final FakeEffects effects = new FakeEffects();
        final FakeUser user = FakeUser.inDiscord();

        SetSeasonDate.launch().run(user, new Values(PhaseCommands.LAUNCH, Map.of("when", "next tuesday")), effects);

        assertEquals("phase.date.invalid", user.only().key());
        assertEquals(SeasonDates.PATTERN, user.only().of("pattern"));
        assertNull(effects.launch, "nothing may have been written");
    }

    @Test
    void settingTheOpeningReportsTheOldValueWithTheNewOne() {
        final FakeEffects effects = new FakeEffects();
        effects.launch = SeasonDates.parse("2026-09-01 12:00").orElseThrow();
        final FakeUser user = FakeUser.inGame();

        SetSeasonDate.launch().run(user, new Values(PhaseCommands.LAUNCH, Map.of("when", "2026-10-01 18:00")), effects);

        assertEquals(List.of("phase.date.set"), user.keys());
        assertEquals(
                "<phase.date.what.launch>",
                user.only().of("what"),
                "the noun is itself translated and has to go through the asker's own bundle");
        assertEquals(1, effects.afterWrites);
        assertEquals(1, effects.recordedDates.size());
    }

    @Test
    void movingSmpStartReportsHowMuchOfOtherPeoplesAccessMovedWithIt() {
        // The only place an admin finds out that a date change rewrote rows belonging to people who are offline.
        final FakeEffects effects = new FakeEffects();
        effects.grants = 7;
        effects.accounts = 4;
        final FakeUser user = FakeUser.inGame();

        SetSeasonDate.smpStart()
                .run(user, new Values(PhaseCommands.SMP_START, Map.of("when", "2026-11-01 18:00")), effects);

        assertEquals(List.of("phase.date.set", "phase.date.moved"), user.keys());
        assertEquals(7, user.replies.get(1).of("grants"));
        assertEquals(4, user.replies.get(1).of("accounts"));
    }

    @Test
    void movingSmpStartWhenNothingMovedSaysSoRatherThanSayingNothing() {
        final FakeEffects effects = new FakeEffects();
        final FakeUser user = FakeUser.inGame();

        SetSeasonDate.smpStart()
                .run(user, new Values(PhaseCommands.SMP_START, Map.of("when", "2026-11-01 18:00")), effects);

        assertEquals(List.of("phase.date.set", "phase.date.none-moved"), user.keys());
    }

    @Test
    void theOpeningNeverReportsMovedAccessBecauseItMovesNone() {
        final FakeEffects effects = new FakeEffects();
        effects.grants = 7;
        effects.accounts = 4;
        final FakeUser user = FakeUser.inGame();

        SetSeasonDate.launch().run(user, new Values(PhaseCommands.LAUNCH, Map.of("when", "2026-10-01 18:00")), effects);

        assertEquals(List.of("phase.date.set"), user.keys());
    }

    @Test
    void clearingSmpStartSaysWhatHappensToTheAccessThatWasAlreadySold() {
        // "Nothing moved" and "there is nothing left to anchor it to" are different facts.
        final FakeEffects effects = new FakeEffects();
        effects.smpStart = SeasonDates.parse("2026-11-01 18:00").orElseThrow();
        final FakeUser user = FakeUser.inDiscord();

        SetSeasonDate.smpStart()
                .run(user, new Values(PhaseCommands.SMP_START, Map.of("when", SeasonDates.CLEAR)), effects);

        assertEquals(List.of("phase.date.cleared", "phase.date.kept"), user.keys());
        assertNull(effects.smpStart);
    }

    @Test
    void clearingTheOpeningDoesNotTalkAboutAccessBecauseNoneWasAnchoredToIt() {
        final FakeEffects effects = new FakeEffects();
        effects.launch = Instant.EPOCH;

        final FakeUser user = FakeUser.inGame();
        SetSeasonDate.launch().run(user, new Values(PhaseCommands.LAUNCH, Map.of("when", SeasonDates.CLEAR)), effects);

        assertEquals(List.of("phase.date.cleared"), user.keys());
    }

    @Test
    void aDateTheModelRefusesComesBackAsItsOwnSentenceNotAsAFailure() {
        final FakeEffects effects = new FakeEffects();
        effects.dateRefusal = new SeasonDateRefused("the opening cannot be after the SMP start");
        final FakeUser user = FakeUser.inGame();

        SetSeasonDate.launch().run(user, new Values(PhaseCommands.LAUNCH, Map.of("when", "2026-10-01 18:00")), effects);

        assertEquals("phase.date.refused", user.only().key());
        assertEquals("the opening cannot be after the SMP start", user.only().of("reason"));
        assertEquals(
                List.of(), effects.warnings, "the model saying no is not something an operator has to be paged about");
        assertEquals(0, effects.afterWrites);
    }

    @Test
    void aDateThatCouldNotBeWrittenIsReportedAsAFailureAndAuditedNowhere() {
        final FakeEffects effects = new FakeEffects();
        effects.writeFailure = new IllegalStateException("the database did not accept it");
        final FakeUser user = FakeUser.inGame();

        SetSeasonDate.smpStart()
                .run(user, new Values(PhaseCommands.SMP_START, Map.of("when", "2026-11-01 18:00")), effects);

        assertEquals("phase.date.failed", user.only().key());
        assertEquals(1, effects.warnings.size());
        assertEquals(0, effects.recordedDates.size());
    }
}
