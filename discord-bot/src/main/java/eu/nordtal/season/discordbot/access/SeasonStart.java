package eu.nordtal.season.discordbot.access;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.phase.PhaseDirectory;
import eu.nordtal.season.discordbot.AdminLog;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;

/**
 * Warns when a grant starts now because {@code season_phase.smp_start} is not set.
 * The warning is a journal line by the bot, not an alert.
 * It mentions no admin, and every test purchase fires it.
 */
@Slf4j
public final class SeasonStart {

    private final PhaseDirectory phases;
    private final AdminLog admin;

    public SeasonStart(final PhaseDirectory phases, final AdminLog admin) {
        this.phases = phases;
        this.admin = admin;
    }

    private static boolean beforeTheSmp(final SeasonPhase phase) {
        return phase == SeasonPhase.PRE_LAUNCH || phase == SeasonPhase.PRE_EVENT || phase == SeasonPhase.START_EVENT;
    }

    public void warnIfUnanchored(final DiscordId discordId, final Instant validFrom) {
        try {
            if (phases.smpStart().isPresent() || !beforeTheSmp(phases.currentPhase())) {
                return;
            }
        } catch (final RuntimeException unreachable) {
            log.warn("Could not check whether the season has a start date", unreachable);
            return;
        }

        log.warn(
                "Granted access to {} while season_phase.smp_start is NULL: the period runs from"
                        + " {} instead of from the SMP opening",
                discordId,
                validFrom);
        // Expected while testing. Before the season opens, somebody sets the date in Steward.
        admin.record(unanchored(discordId, validFrom));
    }

    public static AuditLine unanchored(final DiscordId discordId, final Instant validFrom) {
        return AuditLine.about(
                JournalAction.UNANCHORED_GRANT, Actor.BOT, discordId, new JournalDetail.GrantUnanchored(validFrom));
    }
}
