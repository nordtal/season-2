package eu.nordtal.s2.discordbot.access;

import eu.nordtal.s2.discordbot.discord.AdminLog;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessGrant;
import eu.nordtal.s2.common.phase.PhaseDirectory;

import lombok.extern.slf4j.Slf4j;

/**
 * Says out loud when a period of access was sold before anybody said when it should start.
 *
 * <h2>The rule this watches</h2>
 * {@code AccessDao}'s append rule starts a period at
 * {@code max(now(), season_phase.smp_start, current valid_until)} - so a purchase made weeks before
 * the SMP opens does not begin burning the moment it is paid ({@code V9__smp_start.sql}). When
 * {@code smp_start} is {@code NULL} that anchor is missing and the period starts <b>now</b>, which
 * is exactly the behaviour the rule exists to remove.
 *
 * <h2>Why that is allowed at all</h2>
 * Decided 2026-09-03: the shop has to work before the season has a date, so that the whole flow -
 * tab, payment, grant, role, DM - can be exercised internally. The alternative was refusing every
 * purchase until somebody ran an {@code UPDATE}, which would have made the first test of the
 * payment path wait on a decision about a calendar.
 *
 * <p>The cost is that "we are testing" and "somebody forgot to set the date and a real customer
 * just lost four weeks" produce exactly the same database rows. This class is the difference: every
 * grant written without an anchor, during the phase where an anchor is expected, is a WARNING in
 * the log and a line in the admin channel naming the {@code UPDATE} that fixes it.
 *
 * <h2>A note, not an alert</h2>
 * It does not mention the admin role. During the internal run this fires on every single test
 * purchase, and a channel that pings for an expected state is a channel whose notifications get
 * turned off before the one message that mattered arrives. The line is in the channel either way,
 * and the real safety net is the checklist item that says to set the date - see the workspace
 * {@code todo.md}.
 */
@Slf4j
public final class SeasonStart {

    private final PhaseDirectory phases;
    private final AdminLog admin;

    public SeasonStart(final PhaseDirectory phases, final AdminLog admin) {
        this.phases = phases;
        this.admin = admin;
    }

    /**
     * The phases in which a missing anchor actually costs somebody days.
     * <p>
     * All three of them, not just {@code PRE_LAUNCH} - which is what this checked until it was
     * pointed out on review. {@code AccessDao} starts a grant at {@code now()} whenever
     * {@code smp_start} is {@code NULL}, and access is not consumed in {@code PRE_EVENT} or
     * {@code START_EVENT} either, so a purchase made during the event would lose exactly as many
     * days and say nothing about it.
     * </p><p>
     * {@code SMP} is excluded because {@code now()} is then the right answer and no date is needed;
     * {@code MAINTENANCE} because it interrupts a season that is already running
     * ({@code docs/season-phases.md}), so it is not a pre-season state.
     * </p>
     */
    private static boolean beforeTheSmp(final SeasonPhase phase) {
        return phase == SeasonPhase.PRE_LAUNCH
                || phase == SeasonPhase.PRE_EVENT
                || phase == SeasonPhase.START_EVENT;
    }

    /**
     * Checks one freshly written grant and reports it if it was anchored to nothing.
     *
     * @param discordId who it was written for
     * @param grant     the grant that was just created
     */
    public void warnIfUnanchored(final String discordId, final AccessGrant grant) {
        try {
            if (phases.smpStart().isPresent() || !beforeTheSmp(phases.currentPhase())) {
                return;
            }
        } catch (final RuntimeException unreachable) {
            // The grant is already written; a database that cannot answer this question is not a
            // reason to make noise about it, and the next grant will ask again.
            log.warn("Could not check whether the season has a start date", unreachable);
            return;
        }

        log.warn("Granted access to {} while season_phase.smp_start is NULL: the period runs from"
                + " {} instead of from the SMP opening", discordId, grant.validFrom());
        admin.note("Access was granted to <@" + discordId + "> while the season has no start date."
                + " The period runs from **" + grant.validFrom() + "**, not from the SMP opening."
                + " Expected while testing; before the season opens, set the date with"
                + " `UPDATE season_phase SET smp_start = timestamptz '<when>' WHERE id;`");
    }
}
