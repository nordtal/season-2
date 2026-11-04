package eu.nordtal.season.database.game;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.Journal;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.notify.Channel;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/**
 * An admin's reset of the SMP's milestone track.
 * Steward stamps {@code smp_reset_request}, and smp applies each stamp once, whenever it next looks: on the signal, on
 * connecting and after every quiet minute.
 */
public final class TrackResets {

    private final Jdbi jdbi;

    public TrackResets(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /** Stamps a reset, journals it as {@code actor}'s with the reason, if any, and signals smp, in one transaction. */
    public void request(final Actor actor, final @Nullable String reason) {
        Objects.requireNonNull(actor, "actor");
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            INSERT INTO smp_reset_request (id, requested) VALUES (true, now())
                            ON CONFLICT (id) DO UPDATE
                            SET requested = GREATEST(smp_reset_request.requested, EXCLUDED.requested)""").execute();
            // smp of the release before reads the phase's stamp. It goes with season_phase.
            handle.createUpdate("UPDATE season_phase SET fresh_start = now() WHERE id")
                    .execute();
            Journal.write(handle, AuditLine.of(JournalAction.RESET_TRACK, actor, new JournalDetail.TrackReset(reason)));
            handle.createQuery("SELECT pg_notify(:channel, '') IS NULL")
                    .bind("channel", Channel.SMP.sqlName())
                    .mapTo(Boolean.class)
                    .one();
        });
    }

    /** Whether smp has yet to apply the last reset, as when it was stopped when the reset was asked for. */
    public boolean pending() {
        return jdbi.withHandle(
                handle -> handle.createQuery("""
                        SELECT EXISTS (SELECT 1
                                       FROM smp_reset_request request
                                                LEFT JOIN smp_reset reset ON reset.id
                                       WHERE request.id
                                         AND (reset.applied IS NULL OR reset.applied < request.requested))""").mapTo(Boolean.class).one());
    }
}
