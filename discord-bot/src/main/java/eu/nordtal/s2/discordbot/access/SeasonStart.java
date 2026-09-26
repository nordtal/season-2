package eu.nordtal.s2.discordbot.access;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessGrant;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.discordbot.AdminLog;
import lombok.extern.slf4j.Slf4j;

/**
 * Says out loud when a period of access was sold before anybody said when it should start.
 *
 * A grant starts at {@code max(now(), season_phase.smp_start, current valid_until)}, so a purchase made weeks before
 * the SMP opens does not begin burning when it is paid. With {@code smp_start} still {@code NULL} the anchor is
 * missing and the period starts now - allowed, so the shop can be exercised before the season has a date, but it
 * must never be silent: a customer would lose those weeks and the rows would look identical to a test purchase.
 *
 * A note, not an alert: it does not ping the admin role, because during an internal run this fires on every test
 * purchase.
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
     *
     * Access is consumed in none of them, so a purchase there would lose the time and say nothing. {@code SMP} is
     * excluded because {@code now()} is then the right answer, and {@code MAINTENANCE} because it interrupts a
     * season already running rather than preceding one.
     */
    private static boolean beforeTheSmp(final SeasonPhase phase) {
        return phase == SeasonPhase.PRE_LAUNCH || phase == SeasonPhase.PRE_EVENT || phase == SeasonPhase.START_EVENT;
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
            // The grant is already written; an unreachable database is no reason for noise, and the next one retries.
            log.warn("Could not check whether the season has a start date", unreachable);
            return;
        }

        log.warn(
                "Granted access to {} while season_phase.smp_start is NULL: the period runs from"
                        + " {} instead of from the SMP opening",
                discordId,
                grant.validFrom());
        admin.note("Access was granted to <@" + discordId + "> while the season has no start date."
                + " The period runs from **" + grant.validFrom() + "**, not from the SMP opening."
                + " Expected while testing; before the season opens, set the date with"
                + " `/phase smp-start <yyyy-MM-dd HH:mm>`.");
    }
}
