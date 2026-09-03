package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.SeasonPhase;
import java.time.Instant;
import eu.nordtal.s2.common.phase.SeasonDates;
import eu.nordtal.s2.common.phase.DateChange;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two decisions {@code /phase set} makes before it changes anything: <b>may this person switch
 * the phase</b>, and <b>has the switch been confirmed for this phase</b>.
 * <p>
 * Both are pure functions on purpose. Everything around them is JDA - an interaction, a hook, a
 * button - and none of that can be exercised without a real Discord session, so the parts that can
 * be got wrong silently are kept out of it: an unknown phase name resolving to something, an
 * unknown user counting as an admin, a button from another flow being treated as a confirmation.
 * </p>
 * <p>
 * What this <b>cannot</b> prove: that the confirmation is ever shown, that the button reaches this
 * code, or that {@code PhaseDirectory#switchPhase} writes what it says. The first two need a real
 * guild; the third is covered by {@code :common} against a real PostgreSQL.
 * </p>
 */
class PhaseCommandTest {

    // ---------------------------------------------------------------- authorisation

    @Test
    @DisplayName("an account with the admin flag may switch the phase")
    void adminMaySwitch() {
        assertTrue(PhaseCommand.maySwitch(Optional.of(true)));
    }

    @Test
    @DisplayName("an account whose admin flag is false may not")
    void nonAdminMayNotSwitch() {
        assertFalse(PhaseCommand.maySwitch(Optional.of(false)));
    }

    @Test
    @DisplayName("an account the bot has never written a row for may not")
    void unknownAccountMayNotSwitch() {
        // "Unknown" and "false" are the same answer here and different answers in the DAO, which is
        // the point: folding them in the DAO would make the absence of a row indistinguishable from
        // a flag that was actually read.
        assertFalse(PhaseCommand.maySwitch(Optional.empty()));
    }

    // ---------------------------------------------------------------- the phase option

    @Test
    @DisplayName("each of the four phase names parses to itself")
    void everyPhaseNameParses() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertEquals(Optional.of(phase), PhaseCommand.phaseOf(phase.name()), phase.name());
        }
    }

    @Test
    @DisplayName("a name this build does not know is refused, not read as MAINTENANCE")
    void unknownPhaseNameIsRefused() {
        // SeasonPhase.fromDatabase answers MAINTENANCE to anything it does not recognise, which is
        // correct for a value read out of the database and catastrophic for one typed by a person:
        // a typo would lock the whole network out instead of failing.
        assertAll(
                () -> assertEquals(SeasonPhase.MAINTENANCE, SeasonPhase.fromDatabase("SMPP"),
                        "the trap this is guarding against still exists"),
                () -> assertEquals(Optional.empty(), PhaseCommand.phaseOf("SMPP")),
                () -> assertEquals(Optional.empty(), PhaseCommand.phaseOf("")),
                () -> assertEquals(Optional.empty(), PhaseCommand.phaseOf(null))
        );
    }

    @Test
    @DisplayName("the phase name is matched exactly, so a lower-case one is refused")
    void lowerCasePhaseNameIsRefused() {
        assertEquals(Optional.empty(), PhaseCommand.phaseOf("smp"));
    }

    // ---------------------------------------------------------------- the confirmation button

    @Test
    @DisplayName("a confirm button round-trips the phase it was built for")
    void confirmButtonCarriesItsPhase() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertEquals(Optional.of(phase),
                    PhaseCommand.confirmedPhase(Ids.PHASE_CONFIRM + phase.name()), phase.name());
        }
    }

    @Test
    @DisplayName("no other button in the guild is a phase confirmation")
    void otherButtonsAreNotConfirmations() {
        // Every flow's buttons arrive at every listener. Only ours may switch the season phase.
        assertAll(
                () -> assertEquals(Optional.empty(), PhaseCommand.confirmedPhase(Ids.BUY)),
                () -> assertEquals(Optional.empty(), PhaseCommand.confirmedPhase(Ids.CONFIRM)),
                () -> assertEquals(Optional.empty(), PhaseCommand.confirmedPhase(Ids.LINK)),
                () -> assertEquals(Optional.empty(), PhaseCommand.confirmedPhase(Ids.PHASE_CANCEL)),
                () -> assertEquals(Optional.empty(), PhaseCommand.confirmedPhase(null))
        );
    }

    @Test
    @DisplayName("a confirm button naming a phase this build does not know does nothing")
    void confirmButtonWithAnUnknownPhaseDoesNothing() {
        // An id minted by an older build, still sitting in a channel. Doing nothing is the only
        // safe reading of it.
        assertEquals(Optional.empty(), PhaseCommand.confirmedPhase(Ids.PHASE_CONFIRM + "RESOURCE_PACK_INSTALL"));
    }

    // ---------------------------------------------------------------- what is confirmed

    @Test
    @DisplayName("the confirmation names the phase being left and the phase being entered")
    void confirmationNamesBothPhases() {
        final String text = PhaseCommand.confirmation(SeasonPhase.PRE_EVENT, SeasonPhase.START_EVENT);
        assertAll(
                () -> assertTrue(text.contains("PRE_EVENT"), text),
                () -> assertTrue(text.contains("START_EVENT"), text)
        );
    }

    @Test
    @DisplayName("confirming a switch to SMP says players without access are disconnected")
    void confirmationWarnsAboutTheSmpDisconnect() {
        // This is the reason there is a confirmation step at all: docs/season-phases.md#routing
        // settles that a switch to SMP disconnects a player with no active access rather than
        // moving them to limbo.
        final String text = PhaseCommand.confirmation(SeasonPhase.START_EVENT, SeasonPhase.SMP);
        assertTrue(text.contains("disconnected"), text);
    }

    @Test
    @DisplayName("confirming a switch to MAINTENANCE says only admins get in")
    void confirmationWarnsAboutMaintenance() {
        final String text = PhaseCommand.confirmation(SeasonPhase.SMP, SeasonPhase.MAINTENANCE);
        assertTrue(text.contains("Only admins"), text);
    }

    @Test
    @DisplayName("confirming the phase that is already current says so rather than refusing")
    void confirmationSaysWhenNothingChanges() {
        // Not an error - PhaseDirectory writes the row and the audit entry either way, and the
        // admin should know before clicking that this is what they are about to do.
        final String text = PhaseCommand.confirmation(SeasonPhase.SMP, SeasonPhase.SMP);
        assertTrue(text.contains("already in that phase"), text);
    }

    @Test
    @DisplayName("a switch into a free phase does not claim anybody is disconnected")
    void confirmationDoesNotWarnWhenAccessIsNotRequired() {
        // Access is only required from SMP onwards; PRE_EVENT and START_EVENT are free for any
        // linked member, and saying otherwise would be the confirmation lying about the cost.
        for (final SeasonPhase target : new SeasonPhase[]{SeasonPhase.PRE_EVENT, SeasonPhase.START_EVENT}) {
            final String text = PhaseCommand.confirmation(SeasonPhase.MAINTENANCE, target);
            assertAll(
                    () -> assertFalse(text.contains("disconnected"), text),
                    () -> assertTrue(text.contains("hunger-games"), text)
            );
        }
    }

    // ---------------------------------------------------------------- the two dates

    @Test
    void theOverviewNamesThePhaseAndBothDates() {
        final String text = PhaseCommand.overview(SeasonPhase.PRE_LAUNCH,
                SeasonDates.parse("2026-10-01 18:00").orElseThrow(),
                SeasonDates.parse("2026-10-08 18:00").orElseThrow());

        assertTrue(text.contains("PRE_LAUNCH"));
        assertTrue(text.contains("2026-10-01 18:00"), text);
        assertTrue(text.contains("2026-10-08 18:00"), text);
        assertTrue(text.contains("Europe/Berlin"), "the zone has to be stated: " + text);
    }

    @Test
    void theOverviewSpellsOutADateThatIsNotSet() {
        final String text = PhaseCommand.overview(SeasonPhase.PRE_LAUNCH, null, null);

        // "not set" rather than an empty space: an admin has to be able to tell a missing date
        // from a rendering bug.
        assertTrue(text.contains("not set"), text);
    }

    @Test
    void theRefusalOfANonDateNamesThePatternAndTheEscape() {
        final String text = PhaseCommand.notADate();

        assertTrue(text.contains(SeasonDates.PATTERN), text);
        assertTrue(text.contains("Europe/Berlin"), text);
        assertTrue(text.contains(SeasonDates.CLEAR), "the way out has to be named too: " + text);
    }

    @Test
    void movingTheSmpStartReportsHowMuchAccessMovedWithIt() {
        final Instant was = SeasonDates.parse("2026-10-08 18:00").orElseThrow();
        final Instant now = SeasonDates.parse("2026-10-15 18:00").orElseThrow();

        final String text = PhaseCommand.summary(PhaseCommand.Column.SMP_START,
                new DateChange(was, now, 7, 4));

        assertTrue(text.contains("2026-10-15 18:00"), text);
        assertTrue(text.contains("2026-10-08 18:00"), "the previous date belongs in it too: " + text);
        assertTrue(text.contains("7"), text);
        assertTrue(text.contains("4"), "the number of people is the one a human reacts to: " + text);
    }

    @Test
    void movingTheOpeningNeverTalksAboutAccess() {
        final String text = PhaseCommand.summary(PhaseCommand.Column.LAUNCH,
                new DateChange(null, SeasonDates.parse("2026-10-01 18:00").orElseThrow(), 0, 0));

        assertFalse(text.contains("access period"), "launch owns no grants: " + text);
    }

    @Test
    void clearingTheSmpStartSaysThatAccessStayedWhereItWas() {
        final String text = PhaseCommand.summary(PhaseCommand.Column.SMP_START,
                new DateChange(SeasonDates.parse("2026-10-08 18:00").orElseThrow(), null, 0, 0));

        assertTrue(text.contains("did not move"), text);
    }

    @Test
    void writingTheDateThatWasAlreadyThereSaysSoInsteadOfClaimingAChange() {
        final Instant same = SeasonDates.parse("2026-10-08 18:00").orElseThrow();

        final String text = PhaseCommand.summary(PhaseCommand.Column.SMP_START,
                new DateChange(same, same, 0, 0));

        assertTrue(text.contains("already"), text);
    }
}
