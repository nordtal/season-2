package eu.nordtal.s2.discordbot.access;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessGrant;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.discordbot.AdminLog;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.utils.TimeFormat;

/**
 * Warns when a grant starts now because {@code season_phase.smp_start} is not set yet.
 *
 * A note, not an alert: it does not ping the admin role, since every test purchase fires it.
 */
@Slf4j
public final class SeasonStart {

    private final PhaseDirectory phases;
    private final AdminLog admin;

    public SeasonStart(final PhaseDirectory phases, final AdminLog admin) {
        this.phases = phases;
        this.admin = admin;
    }

    /** Returns whether access is not yet consumed in this phase, so a missing anchor loses days. */
    private static boolean beforeTheSmp(final SeasonPhase phase) {
        return phase == SeasonPhase.PRE_LAUNCH || phase == SeasonPhase.PRE_EVENT || phase == SeasonPhase.START_EVENT;
    }

    /** Reports one freshly written grant if it was anchored to nothing. */
    public void warnIfUnanchored(final String discordId, final AccessGrant grant) {
        try {
            if (phases.smpStart().isPresent() || !beforeTheSmp(phases.currentPhase())) {
                return;
            }
        } catch (final RuntimeException unreachable) {
            // The grant is written; an unreachable database is no reason for noise.
            log.warn("Could not check whether the season has a start date", unreachable);
            return;
        }

        log.warn(
                "Granted access to {} while season_phase.smp_start is NULL: the period runs from"
                        + " {} instead of from the SMP opening",
                discordId,
                grant.validFrom());
        // Expected while testing; before the season opens, somebody sets the date in Steward.
        admin.note(
                "⚠️ No season start",
                "<@" + discordId + ">'s access runs from " + TimeFormat.DATE_TIME_SHORT.format(grant.validFrom())
                        + " instead of the SMP opening. Set the date in Steward under Season.");
    }
}
