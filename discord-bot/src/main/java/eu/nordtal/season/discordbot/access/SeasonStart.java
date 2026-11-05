package eu.nordtal.season.discordbot.access;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.season.SeasonDirectory;
import eu.nordtal.season.discordbot.AdminLog;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;

/**
 * Warns when a grant starts now because the season has no launch date it could start at.
 * The warning is a journal line by the bot, not an alert.
 * It mentions no admin, and every test grant fires it.
 */
@Slf4j
public final class SeasonStart {

    private final SeasonDirectory season;
    private final AdminLog admin;

    public SeasonStart(final SeasonDirectory season, final AdminLog admin) {
        this.season = season;
        this.admin = admin;
    }

    public void warnIfUnanchored(final DiscordId discordId, final Instant validFrom) {
        try {
            if (season.state().launch() != null) {
                return;
            }
        } catch (final RuntimeException unreachable) {
            log.warn("Could not check whether the season has a launch date", unreachable);
            return;
        }

        log.warn(
                "Granted access to {} while the season has no launch date: the period runs from {}"
                        + " instead of from the launch",
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
