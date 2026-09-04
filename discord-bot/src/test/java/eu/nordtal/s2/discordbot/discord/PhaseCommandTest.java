package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.SeasonDates;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What is left of {@code /phase} that belongs to <em>this</em> surface: the confirmation buttons.
 *
 * <h2>Why this file is a third of its former size</h2>
 * It used to assert the authorisation rule, the phase-name parsing, the confirmation wording, the
 * overview and the date refusal - all of which the proxy's copy of {@code /phase} decided
 * separately, and some of it differently. Since 2026-09-04 those live once, in {@code :commands},
 * and are asserted by {@code PhaseCommandsTest} against a fake user rather than against a string
 * this bot happened to build. What could not move is the button, because a component id is a
 * Discord concept.
 *
 * <p>The two functions below are static and package-visible for exactly that reason. <b>Every flow's
 * buttons arrive at every listener in this bot</b>, and these are the ones that switch the season
 * phase and move other people's paid access; "is this button mine, and does it still mean something"
 * is the whole of what this class can be got wrong about.</p>
 *
 * <p>What it cannot prove: that the confirmation is ever shown, that a click reaches this code, or
 * that the flag is re-read before it acts. Those need a real guild and are in the owner's
 * checklist.</p>
 */
class PhaseCommandTest {

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
        assertAll(
                () -> assertEquals(Optional.empty(), PhaseCommand.confirmedPhase(Ids.BUY)),
                () -> assertEquals(Optional.empty(), PhaseCommand.confirmedPhase(Ids.CONFIRM)),
                () -> assertEquals(Optional.empty(), PhaseCommand.confirmedPhase(Ids.LINK)),
                () -> assertEquals(Optional.empty(), PhaseCommand.confirmedPhase(Ids.PHASE_CANCEL)),
                () -> assertEquals(Optional.empty(), PhaseCommand.confirmedPhase(null)),
                // The date button is ours and is not this one. Reading it as a phase would switch
                // the network into whatever a date happened to parse as.
                () -> assertEquals(Optional.empty(),
                        PhaseCommand.confirmedPhase(Ids.PHASE_DATE_CONFIRM + "2026-10-01 18:00")));
    }

    @Test
    @DisplayName("a confirm button naming a phase this build does not know does nothing")
    void confirmButtonWithAnUnknownPhaseDoesNothing() {
        // An id minted by an older build, still sitting in a channel. Doing nothing is the only
        // safe reading of it.
        assertEquals(Optional.empty(),
                PhaseCommand.confirmedPhase(Ids.PHASE_CONFIRM + "RESOURCE_PACK_INSTALL"));
    }

    @Test
    @DisplayName("the phase in a button id is matched exactly, unlike one somebody typed")
    void theButtonIsNotCaseInsensitive() {
        // SetPhase.parse is deliberately case-insensitive, because a person types that argument on
        // the proxy. This string was minted by this bot: a name that does not match exactly did not
        // come from here.
        assertEquals(Optional.empty(), PhaseCommand.confirmedPhase(Ids.PHASE_CONFIRM + "smp"));
    }

    @Test
    @DisplayName("a date confirm button round-trips the date it was built for, and 'clear' too")
    void dateButtonCarriesItsDate() {
        assertEquals(Optional.of("2026-10-01 18:00"),
                PhaseCommand.confirmedDate(Ids.PHASE_DATE_CONFIRM + "2026-10-01 18:00"));
        assertEquals(Optional.of(SeasonDates.CLEAR),
                PhaseCommand.confirmedDate(Ids.PHASE_DATE_CONFIRM + SeasonDates.CLEAR));
    }

    @Test
    @DisplayName("a date button carrying something that is not a date does nothing")
    void dateButtonWithRubbishDoesNothing() {
        assertAll(
                () -> assertEquals(Optional.empty(),
                        PhaseCommand.confirmedDate(Ids.PHASE_DATE_CONFIRM + "next tuesday")),
                () -> assertEquals(Optional.empty(),
                        PhaseCommand.confirmedDate(Ids.PHASE_DATE_CONFIRM)),
                () -> assertEquals(Optional.empty(), PhaseCommand.confirmedDate(Ids.PHASE_CANCEL)),
                () -> assertEquals(Optional.empty(), PhaseCommand.confirmedDate(null)));
    }
}
