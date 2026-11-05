package eu.nordtal.season.smp.milestone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.game.TrackResets;
import eu.nordtal.season.database.season.SeasonDirectory;
import java.time.Clock;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** smp applies Steward's reset of the track once, whenever it next looks. */
class TrackResetIntegrationTest {

    private static final Actor ADMIN = Actor.person(DiscordId.of("300000000000000001"));

    private Jdbi owner;
    private TrackDao smp;
    private TrackResets steward;
    private SeasonDirectory season;

    @BeforeEach
    void playedTrack() {
        final TestDatabase database = TestDatabase.fresh();
        owner = Jdbis.over(database.dataSource());
        smp = Jdbis.over(database.dataSourceAs(DatabaseRole.SMP)).onDemand(TrackDao.class);
        steward = new TrackResets(database.dataSourceAs(DatabaseRole.STEWARD));
        season = SeasonDirectory.using(database.dataSource(), Clock.systemUTC());
        owner.useHandle(handle -> {
            handle.execute("UPDATE season_state SET mode = 'HUNGER_GAMES', maintenance = false WHERE id");
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
    void aResetStartsTheTrackOverOnceAndKeepsTheAura() {
        assertEquals(0, smp.startOverIfDue(), "no reset was asked for yet");

        steward.request(ADMIN, "the test weekend is over");

        assertEquals(1, smp.startOverIfDue());
        assertEquals(List.of("departure|LOCKED|-", "waiting|LOCKED|-"), track());
        assertEquals(List.of("logs|0|-"), objectives());
        assertEquals(0, count("SELECT count(*) FROM smp_contribution"));
        assertEquals(1, count("SELECT count(*) FROM smp_player WHERE aura = 12"), "aura stays");
        assertFalse(steward.pending());

        owner.useHandle(handle -> handle.execute("UPDATE smp_milestone SET state = 'ACTIVE' WHERE key = 'waiting'"));
        assertEquals(0, smp.startOverIfDue(), "one request, one reset: a later pass starts nothing over");
        assertEquals(List.of("departure|LOCKED|-", "waiting|ACTIVE|-"), track());
    }

    @Test
    void aServerThatWasDownAtTheRequestStartsOverWhenItNextLooks() {
        steward.request(ADMIN, null);
        steward.request(ADMIN, null);
        assertTrue(steward.pending());

        assertEquals(1, smp.startOverIfDue(), "the stamp waits for smp, and two requests unseen are one reset");
        assertEquals(List.of("logs|0|-"), objectives());
        assertEquals(0, smp.startOverIfDue());
    }

    @Test
    void launchingSwitchingToTheSmpAndMaintenanceStartNothingOver() {
        season.launchNow(Actor.STEWARD, null);
        season.setMode(SeasonMode.SMP, Actor.STEWARD, null);
        season.setMaintenance(true, Actor.STEWARD, null);
        season.setMaintenance(false, Actor.STEWARD, null);

        assertEquals(0, smp.startOverIfDue());
        assertEquals(1, count("SELECT count(*) FROM smp_objective WHERE amount = 64 AND completed IS NOT NULL"));
        assertEquals(1, count("SELECT count(*) FROM smp_contribution"));
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
