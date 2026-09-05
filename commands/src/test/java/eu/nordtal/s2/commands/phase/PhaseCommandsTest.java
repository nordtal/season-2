package eu.nordtal.s2.commands.phase;

import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.SeasonDateRefused;
import eu.nordtal.s2.common.phase.SeasonDates;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code /phase} decides, asserted without a proxy and without a guild.
 *
 * <h2>Read this first: it is the module's whole justification</h2>
 * Every case below was previously answerable only by running the command. The proxy's half needed a
 * Velocity proxy and a real client; the bot's half needed a Discord guild and an admin role. So the
 * two implementations were never compared, and they had drifted: the bot confirmed a switch and the
 * proxy did not, the bot answered in hardcoded English and the proxy in the asker's language, and
 * only one of them reported that moving {@code smp_start} had shifted other people's access.
 *
 * <p>These tests do <b>not</b> prove that either adapter registers the command, parses its
 * arguments, or renders the keys - three things that still need a running proxy and a real guild,
 * and are in the owner's checklist. What they prove is that there is now only one answer to compare
 * against.</p>
 */
class PhaseCommandsTest {

    // ---------------------------------------------------------------- the declarations

    @Test
    @DisplayName("all four subcommands are admin-only, run on the proxy, and are not on the console")
    void theDeclarationsAgree() {
        for (final var declaration : List.of(PhaseCommands.SHOW, PhaseCommands.SET,
                PhaseCommands.LAUNCH, PhaseCommands.SMP_START)) {
            assertEquals(Target.PROXY, declaration.target(), declaration.name());
            assertTrue(declaration.adminOnly(), declaration.name());
            // The console is absent on purpose, and it is a decision this module inherited rather
            // than took: docs/season-phases.md rejected it on 2026-08-31 because it would be a
            // second notion of who may switch the phase on a proxy that already knows who is an
            // admin. /hg went the other way on 2026-09-04, which is why the set is per declaration.
            assertEquals(java.util.Set.of(Surface.GAME, Surface.DISCORD),
                    declaration.surfaces(), declaration.name());
        }
    }

    @Test
    @DisplayName("the flag marks what cannot be undone, and not simply everything that writes")
    void onlyTheTrulyIrreversibleIsFlagged() {
        assertFalse(PhaseCommands.SHOW.irreversible(), "reading changes nothing");
        assertTrue(PhaseCommands.SET.irreversible(),
                "a switch to SMP disconnects every player without active access");
        assertTrue(PhaseCommands.SMP_START.irreversible(),
                "moving smp-start shifts access periods belonging to people who are not in the room,"
                        + " and moving it back shifts them again rather than undoing it");

        // The one that is deliberately NOT flagged, and the reason it matters: a flag set on
        // everything that writes trains an admin to type every command twice, which is how a
        // confirmation stops being read. Setting the opening again is an exact undo.
        assertFalse(PhaseCommands.LAUNCH.irreversible());
    }

    @Test
    @DisplayName("every phase has a consequence sentence of its own")
    void nothingFallsThroughToSomebodyElsesConsequence() {
        // One key per constant rather than one shared by PRE_EVENT and START_EVENT, so that adding
        // a sixth phase produces a missing key - which Messages logs by name - rather than silently
        // telling an admin what a different phase would have done.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertEquals("phase.consequence." + phase.name(), SetPhase.consequenceKey(phase));
        }
    }

    // ---------------------------------------------------------------- /phase show

    @Test
    @DisplayName("a process that caches the phase says it before it asks the database")
    void theProxyAnswersFromMemoryFirst() {
        // The point of the ordering: this is the command somebody runs while the network is
        // misbehaving, and it has to say something useful even when the row cannot be read.
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
    @DisplayName("a phase that was never read is said differently from one that was")
    void theFallbackIsNotPresentedAsAnObservation() {
        final FakeEffects effects = new FakeEffects();
        effects.observation = new PhaseEffects.Observation(SeasonPhase.MAINTENANCE, false, null);
        final FakeUser user = FakeUser.inGame();

        new ShowPhase().run(user, Values.none(PhaseCommands.SHOW), effects);

        assertEquals(List.of("phase.current.unread", "phase.dates"), user.keys());
    }

    @Test
    @DisplayName("a process that caches nothing reads the phase and still says it first")
    void theBotReadsAndKeepsTheSameOrder() {
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
    @DisplayName("a process with no cache and no database answers with its own key, not the proxy's")
    void theBotSaysSomethingItCanStandBehind() {
        // Two keys and not one, because phase.read.failed says "the phase above" and on this path
        // there is nothing above. It said nothing at all until 2026-09-05, which is worse than
        // either: a Discord interaction with no response reads as a broken command rather than as
        // a database that did not answer, and a request row claimed off the table settled empty.
        final FakeEffects effects = new FakeEffects();
        effects.readFailure = new IllegalStateException("the database is not there");
        final FakeUser user = FakeUser.inDiscord();

        new ShowPhase().run(user, Values.none(PhaseCommands.SHOW), effects);

        assertEquals(List.of("phase.read.failed.only"), user.keys());
        assertEquals(1, effects.warnings.size(), "it still has to be reported to the operator");
    }

    @Test
    @DisplayName("a process that already printed the phase still owes a word about the dates")
    void theProxyStillReportsTheDateFailure() {
        final FakeEffects effects = new FakeEffects();
        effects.observation = new PhaseEffects.Observation(SeasonPhase.SMP, true, null);
        effects.readFailure = new IllegalStateException("the database is not there");
        final FakeUser user = FakeUser.inGame();

        new ShowPhase().run(user, Values.none(PhaseCommands.SHOW), effects);

        assertEquals(List.of("phase.current", "phase.read.failed"), user.keys());
    }

    // ---------------------------------------------------------------- /phase set

    @Test
    @DisplayName("a switch is written, recorded, propagated and reported, in that order")
    void aSwitchDoesAllFourThings() {
        final FakeEffects effects = new FakeEffects();
        effects.current = SeasonPhase.PRE_LAUNCH;
        final FakeUser user = FakeUser.inDiscord();

        new SetPhase().run(user, new Values(PhaseCommands.SET, Map.of("phase", "SMP")), effects);

        assertEquals(SeasonPhase.SMP, effects.current);
        assertEquals(1, effects.recordedSwitches.size(), "the admin channel or the log has to hear");
        assertEquals(1, effects.afterWrites,
                "a process that caches the phase must not wait for its own notification to come"
                        + " back around, or the reply and the log disagree");
        assertEquals("phase.changed", user.only().key());
        assertEquals("PRE_LAUNCH", user.only().of("previous"));
        assertEquals("SMP", user.only().of("current"));
    }

    @Test
    @DisplayName("switching to the phase it already is is reported as such, and still audited")
    void switchingToTheSamePhaseIsNotSilent() {
        final FakeEffects effects = new FakeEffects();
        effects.current = SeasonPhase.SMP;
        final FakeUser user = FakeUser.inGame();

        new SetPhase().run(user, new Values(PhaseCommands.SET, Map.of("phase", "SMP")), effects);

        assertEquals("phase.unchanged", user.only().key());
        assertEquals("SMP", user.only().of("phase"));
        assertEquals(1, effects.recordedSwitches.size());
    }

    @Test
    @DisplayName("an unknown phase name is refused without touching the database")
    void anUnknownPhaseIsRefusedBeforeTheWrite() {
        // SeasonPhase.fromDatabase answers MAINTENANCE to anything it does not recognise, which is
        // right for a row and catastrophic for a command line: a name this build does not know
        // would lock the whole network out without anybody typing MAINTENANCE.
        final FakeEffects effects = new FakeEffects();
        effects.current = SeasonPhase.SMP;
        final FakeUser user = FakeUser.inGame();

        new SetPhase().run(user, new Values(PhaseCommands.SET, Map.of("phase", "SEASON_OVER")),
                effects);

        assertEquals("phase.unknown", user.only().key());
        assertEquals("SEASON_OVER", user.only().of("value"));
        assertEquals(SeasonPhase.SMP, effects.current, "nothing may have been written");
        assertEquals(0, effects.afterWrites);
    }

    @Test
    @DisplayName("a phase name is case-insensitive, because one surface types it by hand")
    void typingItInLowerCaseWorks() {
        final FakeEffects effects = new FakeEffects();
        new SetPhase().run(FakeUser.inGame(),
                new Values(PhaseCommands.SET, Map.of("phase", "maintenance")), effects);

        assertEquals(SeasonPhase.MAINTENANCE, effects.current);
    }

    @Test
    @DisplayName("a failed write reports the failure and does not claim a switch happened")
    void aFailedSwitchIsNotReportedAsOne() {
        final FakeEffects effects = new FakeEffects();
        effects.writeFailure = new IllegalStateException("the database did not accept it");
        final FakeUser user = FakeUser.inGame();

        new SetPhase().run(user, new Values(PhaseCommands.SET, Map.of("phase", "SMP")), effects);

        assertEquals("phase.failed", user.only().key());
        assertEquals(0, effects.recordedSwitches.size());
        assertEquals(0, effects.afterWrites);
    }

    @Test
    @DisplayName("the audit trail records the Discord id, and says which surface it came from")
    void theActorAndTheReasonAreRecorded() {
        final FakeEffects effects = new FakeEffects();
        new SetPhase().run(FakeUser.inDiscord(),
                new Values(PhaseCommands.SET, Map.of("phase", "SMP")), effects);
        assertEquals("100000000000000002", effects.lastActor);
        assertTrue(effects.lastReason.contains("DISCORD"), effects.lastReason);
        assertTrue(effects.lastReason.contains("tester"), effects.lastReason);

        // An asker with no Discord id writes a null actor rather than a placeholder string. The
        // audit column is nullable for exactly that, and /phase itself is not on the console - but
        // the logic must not assume a Discord id exists, because the surfaces are a per-declaration
        // decision and this class is not the place that takes it.
        final FakeEffects fromConsole = new FakeEffects();
        new SetPhase().run(FakeUser.console(),
                new Values(PhaseCommands.SET, Map.of("phase", "SMP")), fromConsole);
        assertNull(fromConsole.lastActor);
        assertTrue(fromConsole.lastReason.contains("CONSOLE"), fromConsole.lastReason);
    }

    // ---------------------------------------------------------------- the two dates

    @Test
    @DisplayName("a date that is not a date is refused before anything is deferred")
    void aTypoComesBackImmediately() {
        final FakeEffects effects = new FakeEffects();
        final FakeUser user = FakeUser.inDiscord();

        SetSeasonDate.launch().run(user,
                new Values(PhaseCommands.LAUNCH, Map.of("when", "next tuesday")), effects);

        assertEquals("phase.date.invalid", user.only().key());
        assertEquals(SeasonDates.PATTERN, user.only().of("pattern"));
        assertNull(effects.launch, "nothing may have been written");
    }

    @Test
    @DisplayName("setting the opening reports the old value with the new one")
    void settingTheOpeningNamesBoth() {
        final FakeEffects effects = new FakeEffects();
        effects.launch = SeasonDates.parse("2026-09-01 12:00").orElseThrow();
        final FakeUser user = FakeUser.inGame();

        SetSeasonDate.launch().run(user,
                new Values(PhaseCommands.LAUNCH, Map.of("when", "2026-10-01 18:00")), effects);

        assertEquals(List.of("phase.date.set"), user.keys());
        assertEquals("<phase.date.what.launch>", user.only().of("what"),
                "the noun is itself translated and has to go through the asker's own bundle");
        assertEquals(1, effects.afterWrites);
        assertEquals(1, effects.recordedDates.size());
    }

    @Test
    @DisplayName("moving smp-start reports how much of other people's access moved with it")
    void movedAccessIsAlwaysReported() {
        // The only place an admin finds out that a date change rewrote rows belonging to people who
        // are not in the room. The proxy said it and the bot said it; nothing made them agree.
        final FakeEffects effects = new FakeEffects();
        effects.grants = 7;
        effects.accounts = 4;
        final FakeUser user = FakeUser.inGame();

        SetSeasonDate.smpStart().run(user,
                new Values(PhaseCommands.SMP_START, Map.of("when", "2026-11-01 18:00")), effects);

        assertEquals(List.of("phase.date.set", "phase.date.moved"), user.keys());
        assertEquals("7", user.replies.get(1).of("grants"));
        assertEquals("4", user.replies.get(1).of("accounts"));
    }

    @Test
    @DisplayName("moving smp-start when nothing moved says so, rather than saying nothing")
    void nothingMovedIsAlsoAnAnswer() {
        final FakeEffects effects = new FakeEffects();
        final FakeUser user = FakeUser.inGame();

        SetSeasonDate.smpStart().run(user,
                new Values(PhaseCommands.SMP_START, Map.of("when", "2026-11-01 18:00")), effects);

        assertEquals(List.of("phase.date.set", "phase.date.none-moved"), user.keys());
    }

    @Test
    @DisplayName("the opening never reports moved access, because it moves none")
    void theOpeningDoesNotClaimToMoveAccess() {
        final FakeEffects effects = new FakeEffects();
        effects.grants = 7;
        effects.accounts = 4;
        final FakeUser user = FakeUser.inGame();

        SetSeasonDate.launch().run(user,
                new Values(PhaseCommands.LAUNCH, Map.of("when", "2026-10-01 18:00")), effects);

        assertEquals(List.of("phase.date.set"), user.keys());
    }

    @Test
    @DisplayName("clearing smp-start says what happens to the access that was already sold")
    void clearingSaysWhatHappensToWhatWasSold() {
        // "Nothing moved" and "there is nothing left to anchor it to" are different facts, and the
        // second one is the one somebody has to hear before they sell another period.
        final FakeEffects effects = new FakeEffects();
        effects.smpStart = SeasonDates.parse("2026-11-01 18:00").orElseThrow();
        final FakeUser user = FakeUser.inDiscord();

        SetSeasonDate.smpStart().run(user,
                new Values(PhaseCommands.SMP_START, Map.of("when", SeasonDates.CLEAR)), effects);

        assertEquals(List.of("phase.date.cleared", "phase.date.kept"), user.keys());
        assertNull(effects.smpStart);
    }

    @Test
    @DisplayName("clearing the opening does not talk about access, because none was anchored to it")
    void clearingTheOpeningIsOneSentence() {
        final FakeEffects effects = new FakeEffects();
        effects.launch = Instant.EPOCH;

        final FakeUser user = FakeUser.inGame();
        SetSeasonDate.launch().run(user,
                new Values(PhaseCommands.LAUNCH, Map.of("when", SeasonDates.CLEAR)), effects);

        assertEquals(List.of("phase.date.cleared"), user.keys());
    }

    @Test
    @DisplayName("a date the model refuses comes back as its own sentence, not as a failure")
    void aRefusalIsNotAnError() {
        final FakeEffects effects = new FakeEffects();
        effects.dateRefusal = new SeasonDateRefused("the opening cannot be after the SMP start");
        final FakeUser user = FakeUser.inGame();

        SetSeasonDate.launch().run(user,
                new Values(PhaseCommands.LAUNCH, Map.of("when", "2026-10-01 18:00")), effects);

        assertEquals("phase.date.refused", user.only().key());
        assertEquals("the opening cannot be after the SMP start", user.only().of("reason"));
        assertEquals(List.of(), effects.warnings,
                "the model saying no is not something an operator has to be paged about");
        assertEquals(0, effects.afterWrites);
    }

    @Test
    @DisplayName("a date that could not be written is reported as a failure and audited nowhere")
    void aFailedDateWriteIsLoud() {
        final FakeEffects effects = new FakeEffects();
        effects.writeFailure = new IllegalStateException("the database did not accept it");
        final FakeUser user = FakeUser.inGame();

        SetSeasonDate.smpStart().run(user,
                new Values(PhaseCommands.SMP_START, Map.of("when", "2026-11-01 18:00")), effects);

        assertEquals("phase.date.failed", user.only().key());
        assertEquals(1, effects.warnings.size());
        assertEquals(0, effects.recordedDates.size());
    }
}
