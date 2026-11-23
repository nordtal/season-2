package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.smp.aura.AuraCause;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.pending.PendingItems;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuelStakesIntegrationTest {

    private static final DiscordId FIRST = DiscordId.of("100000000000000041");
    private static final DiscordId SECOND = DiscordId.of("100000000000000042");
    private static final DuelStakes.Staked DIAMONDS = new DuelStakes.Staked("minecraft:diamond", 3, "AQID");

    private static TestDatabase database;
    private static Jdbi owner;
    private static Jdbi smp;

    private DuelStakes stakes;
    private AuraDao aura;
    private PendingItems pending;

    @BeforeAll
    static void startDatabase() {
        database = TestDatabase.fresh();
        owner = Jdbis.over(database.dataSource());
        smp = Jdbis.over(database.dataSourceAs(DatabaseRole.SMP));
    }

    @BeforeEach
    void freshSeason() {
        owner.useHandle(handle -> {
            handle.execute("TRUNCATE TABLE smp_player, smp_aura_event, smp_duel_stake, smp_pending_item,"
                    + " discord_user CASCADE");
            handle.execute("INSERT INTO discord_user (discord_id) VALUES (?), (?)", FIRST.value(), SECOND.value());
        });
        stakes = smp.onDemand(DuelStakes.class);
        aura = smp.onDemand(AuraDao.class);
        pending = smp.onDemand(PendingItems.class);
        aura.addAura(FIRST, 40, new AuraCause.Advancement("minecraft:story/root"));
        aura.addAura(SECOND, 10, new AuraCause.Advancement("minecraft:story/root"));
    }

    @Test
    void aLockBooksBothStakesOffAndThePayoutGivesTheWinnerBothOnce() {
        final UUID duel = UUID.randomUUID();
        assertTrue(stakes.lock(
                duel,
                List.of(
                        new DuelStakes.Offer(FIRST, 30, List.of(DIAMONDS)),
                        new DuelStakes.Offer(SECOND, 5, List.of()))));
        assertEquals(10, aura.auraOf(FIRST).orElseThrow());
        assertEquals(5, aura.auraOf(SECOND).orElseThrow());

        assertEquals(OptionalInt.of(35), stakes.payOut(duel, SECOND, FIRST, "Sword"));
        assertEquals(OptionalInt.empty(), stakes.payOut(duel, SECOND, FIRST, "Sword"));
        assertEquals(Set.of(), stakes.refund(duel));

        assertEquals(10, aura.auraOf(FIRST).orElseThrow());
        assertEquals(40, aura.auraOf(SECOND).orElseThrow());
        assertEquals(
                List.of(new PendingItems.Item("minecraft:diamond", 3, "AQID", "", false, false)),
                pending.claim(SECOND));
        assertTrue(pending.claim(FIRST).isEmpty());
        assertEquals(
                List.of("DUEL_LOSS 0 Sword", "DUEL_WIN 35 Sword"),
                owner.withHandle(handle -> handle.createQuery("SELECT e.reason || ' ' || e.delta || ' ' || d.kit"
                                + " FROM smp_aura_event e JOIN smp_aura_duel d ON d.event_id = e.id ORDER BY e.reason")
                        .mapTo(String.class)
                        .list()));
    }

    @Test
    void aRefundGivesEachOwnerTheirOwnStakeOnce() {
        final UUID duel = UUID.randomUUID();
        assertTrue(stakes.lock(
                duel,
                List.of(
                        new DuelStakes.Offer(FIRST, 0, List.of(DIAMONDS)),
                        new DuelStakes.Offer(SECOND, 10, List.of()))));

        assertEquals(Set.of(FIRST, SECOND), stakes.refund(duel));
        assertEquals(Set.of(), stakes.refund(duel));
        assertEquals(OptionalInt.empty(), stakes.payOut(duel, FIRST, SECOND, "Sword"));

        assertEquals(40, aura.auraOf(FIRST).orElseThrow());
        assertEquals(10, aura.auraOf(SECOND).orElseThrow());
        assertEquals(
                List.of(new PendingItems.Item("minecraft:diamond", 3, "AQID", "", false, true)), pending.claim(FIRST));
    }

    @Test
    void aBalanceBelowItsOfferHoldsNothing() {
        final UUID duel = UUID.randomUUID();
        assertFalse(stakes.lock(
                duel,
                List.of(
                        new DuelStakes.Offer(FIRST, 5, List.of(DIAMONDS)),
                        new DuelStakes.Offer(SECOND, 11, List.of()))));

        assertEquals(40, aura.auraOf(FIRST).orElseThrow());
        assertEquals(Set.of(), stakes.refund(duel));
    }

    @Test
    void aStartRefundsEveryDuelStillHeld() {
        final UUID one = UUID.randomUUID();
        stakes.lock(
                one, List.of(new DuelStakes.Offer(FIRST, 20, List.of()), new DuelStakes.Offer(SECOND, 0, List.of())));
        final UUID two = UUID.randomUUID();
        stakes.lock(
                two,
                List.of(new DuelStakes.Offer(FIRST, 5, List.of(DIAMONDS)), new DuelStakes.Offer(SECOND, 3, List.of())));

        assertEquals(Set.of(FIRST, SECOND), stakes.refundLeftover());
        assertEquals(40, aura.auraOf(FIRST).orElseThrow());
        assertEquals(10, aura.auraOf(SECOND).orElseThrow());
        assertEquals(Set.of(), stakes.refundLeftover());
        assertEquals(OptionalInt.empty(), stakes.payOut(one, SECOND, FIRST, "Bow"));
    }
}
