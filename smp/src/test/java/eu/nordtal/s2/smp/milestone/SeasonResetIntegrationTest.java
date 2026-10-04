package eu.nordtal.s2.smp.milestone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.DatabaseRole;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.phase.PhaseDirectory;
import java.time.Clock;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** smp starts its track over itself, once for every fresh start the phase stamped, and as its own role. */
class SeasonResetIntegrationTest {

    private Jdbi owner;
    private TrackDao smp;
    private PhaseDirectory phases;

    @BeforeEach
    void playedTrack() {
        final TestDatabase database = TestDatabase.fresh();
        owner = Jdbis.over(database.dataSource());
        smp = Jdbis.over(database.dataSourceAs(DatabaseRole.SMP)).onDemand(TrackDao.class);
        phases = PhaseDirectory.using(database.dataSource(), Clock.systemUTC());
        owner.useHandle(handle -> {
            handle.execute("UPDATE season_phase SET phase = 'START_EVENT' WHERE id");
            handle.execute("INSERT INTO discord_user (discord_id) VALUES ('400000000000000001')");
            handle.execute("INSERT INTO smp_milestone (key, state, unlocked) VALUES ('waiting', 'UNLOCKED', now()),"
                    + " ('departure', 'ACTIVE', NULL)");
            handle.execute("INSERT INTO smp_objective (milestone_key, key, type, amount, target, completed)"
                    + " VALUES ('departure', 'logs', 'HAND_IN', 64, 64, now())");
            handle.execute("INSERT INTO smp_contribution (objective_id, discord_id, amount)"
                    + " SELECT id, '400000000000000001', 64 FROM smp_objective");
            handle.execute("INSERT INTO smp_player (discord_id, aura) VALUES ('400000000000000001', 12)");
        });
    }

    @Test
    void enteringTheSeasonStartsTheTrackOverOnceAndKeepsTheAura() {
        assertEquals(0, smp.startOverIfDue(), "no fresh start was stamped yet");

        phases.switchPhase(SeasonPhase.SMP, Actor.STEWARD, null);

        assertEquals(1, smp.startOverIfDue());
        assertEquals(List.of("departure|LOCKED|-", "waiting|LOCKED|-"), track());
        assertEquals(List.of("logs|0|-"), objectives());
        assertEquals(0, count("SELECT count(*) FROM smp_contribution"));
        assertEquals(1, count("SELECT count(*) FROM smp_player WHERE aura = 12"), "aura stays");

        owner.useHandle(handle -> handle.execute("UPDATE smp_milestone SET state = 'ACTIVE' WHERE key = 'waiting'"));
        assertEquals(0, smp.startOverIfDue(), "one fresh start, one reset: a later pass starts nothing over");
        assertEquals(List.of("departure|LOCKED|-", "waiting|ACTIVE|-"), track());
    }

    @Test
    void aServerThatWasDownAtTheSwitchStartsOverWhenItNextLooks() {
        phases.switchPhase(SeasonPhase.SMP, Actor.STEWARD, null);
        phases.switchPhase(SeasonPhase.MAINTENANCE, Actor.STEWARD, null);

        assertEquals(1, smp.startOverIfDue(), "the stamp waits for smp, whatever the phase is now");
        assertEquals(List.of("logs|0|-"), objectives());
    }

    @Test
    void aBreakFromMaintenanceKeepsTheTrack() {
        phases.switchPhase(SeasonPhase.SMP, Actor.STEWARD, null);
        assertEquals(1, smp.startOverIfDue());
        owner.useHandle(handle -> handle.execute("UPDATE smp_objective SET amount = 10"));

        phases.switchPhase(SeasonPhase.MAINTENANCE, Actor.STEWARD, null);
        phases.switchPhase(SeasonPhase.SMP, Actor.STEWARD, null);

        assertEquals(0, smp.startOverIfDue());
        assertEquals(List.of("logs|10|-"), objectives());
    }

    private List<String> track() {
        return owner.withHandle(handle -> handle.createQuery(
                        "SELECT key || '|' || state || '|' || coalesce(cast(unlocked AS text), '-')"
                                + " FROM smp_milestone ORDER BY key")
                .mapTo(String.class)
                .list());
    }

    private List<String> objectives() {
        return owner.withHandle(handle -> handle.createQuery(
                        "SELECT key || '|' || amount || '|' || coalesce(cast(completed AS text), '-')"
                                + " FROM smp_objective")
                .mapTo(String.class)
                .list());
    }

    private int count(final String sql) {
        return owner.withHandle(
                handle -> handle.createQuery(sql).mapTo(Integer.class).one());
    }
}
