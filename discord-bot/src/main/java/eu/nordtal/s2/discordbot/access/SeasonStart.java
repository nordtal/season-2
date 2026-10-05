package eu.nordtal.s2.discordbot.access;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.phase.PhaseDirectory;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.messages.value.Mention;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;

/**
 * Warns when a grant starts now because {@code season_phase.smp_start} is not set yet.
 *
 * A note, not an alert: it mentions no admin, since every test purchase fires it.
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

    /** Reports one freshly written grant, starting at {@code validFrom}, if it was anchored to nothing. */
    public void warnIfUnanchored(final DiscordId discordId, final Instant validFrom) {
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
                validFrom);
        // Expected while testing; before the season opens, somebody sets the date in Steward.
        admin.note("⚠️", TEXTS.note().noSeasonStart(), TEXTS.note().runsFromTheGrant(Mention.of(discordId), validFrom));
    }
}
