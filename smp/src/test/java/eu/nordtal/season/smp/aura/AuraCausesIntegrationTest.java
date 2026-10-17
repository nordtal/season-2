package eu.nordtal.season.smp.aura;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Every aura change records its cause as typed values in the row of its reason, against a real PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuraCausesIntegrationTest {

    private static final DiscordId PLAYER = DiscordId.of("100000000000000042");

    private static Jdbi jdbi;

    private AuraDao aura;

    @BeforeAll
    static void startDatabase() {
        jdbi = Jdbis.over(TestDatabase.fresh().dataSource());
    }

    @BeforeEach
    void freshSeason() {
        jdbi.useHandle(handle -> {
            handle.execute("TRUNCATE TABLE smp_player, smp_aura_event, smp_milestone, discord_user CASCADE");
            handle.execute("INSERT INTO discord_user (discord_id) VALUES (?)", PLAYER.value());
        });
        aura = jdbi.onDemand(AuraDao.class);
    }

    @Test
    void aDuelRecordsItsType() {
        aura.addAura(PLAYER, 10, new AuraCause.Duel(true, DuelType.BOW));

        assertEquals(
                List.of("DUEL_WIN BOW"),
                rows("SELECT e.reason || ' ' || d.duel_type FROM smp_aura_event e"
                        + " JOIN smp_aura_duel d ON d.event_id = e.id"));
    }

    @Test
    void anAdminChangeRecordsTheActorWhoMadeIt() {
        aura.addAura(PLAYER, 40, new AuraCause.Admin(Actor.person(DiscordId.of("100000000000000007"))));
        aura.addAura(PLAYER, -5, new AuraCause.Admin(Actor.HOST));

        assertEquals(
                List.of("ADMIN 40 PERSON 100000000000000007", "ADMIN -5 HOST -"),
                rows("SELECT e.reason || ' ' || e.delta || ' ' || a.actor_kind || ' ' || coalesce(a.actor_id, '-')"
                        + " FROM smp_aura_event e JOIN smp_aura_admin a ON a.event_id = e.id ORDER BY e.delta DESC"));
    }

    @Test
    void aDeathRecordsItsDamageTypeAndOneWithoutNoneAtAll() {
        aura.addAura(PLAYER, -20, new AuraCause.Death(AuraReason.DEATH_LISTED, "minecraft:lava"));
        aura.addAura(PLAYER, -5, new AuraCause.Death(AuraReason.DEATH, null));

        assertEquals(
                List.of("DEATH_LISTED minecraft:lava"),
                rows("SELECT e.reason || ' ' || d.damage_type"
                        + " FROM smp_aura_event e JOIN smp_aura_death d ON d.event_id = e.id"));
        assertEquals(List.of("2"), rows("SELECT count(*) FROM smp_aura_event"));
    }

    @Test
    void anAdvancementRecordsItsKey() {
        aura.addAura(PLAYER, 15, new AuraCause.Advancement("minecraft:story/mine_diamond"));

        assertEquals(
                List.of("ADVANCEMENT minecraft:story/mine_diamond"),
                rows("SELECT e.reason || ' ' || a.advancement"
                        + " FROM smp_aura_event e JOIN smp_aura_advancement a ON a.event_id = e.id"));
    }

    @Test
    void aContributionReferencesItsObjective() {
        final UUID objective = jdbi.withHandle(handle -> {
            handle.execute("INSERT INTO smp_milestone (key, state) VALUES ('foothold', 'ACTIVE')");
            return handle.createQuery("INSERT INTO smp_objective (milestone_key, key, type, target)"
                            + " VALUES ('foothold', 'iron', 'HAND_IN', 10) RETURNING id")
                    .mapTo(UUID.class)
                    .one();
        });

        aura.addAura(PLAYER, 30, new AuraCause.Contribution(objective));

        assertEquals(
                List.of("CONTRIBUTION foothold iron"),
                rows("SELECT e.reason || ' ' || o.milestone_key || ' ' || o.key"
                        + " FROM smp_aura_event e JOIN smp_aura_contribution c ON c.event_id = e.id"
                        + " JOIN smp_objective o ON o.id = c.objective_id"));
    }

    private static List<String> rows(final String sql) {
        return jdbi.withHandle(
                handle -> handle.createQuery(sql).mapTo(String.class).list());
    }
}
