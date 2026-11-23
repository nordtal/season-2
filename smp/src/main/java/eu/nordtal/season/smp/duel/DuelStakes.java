package eu.nordtal.season.smp.duel;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.smp.aura.AuraCause;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.wheel.PendingItems;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The escrow of a duel: both stakes from the lock until the duel pays out or refunds. Never on the main thread.
 *
 * Every method deletes the rows it pays, so each item and aura point leaves the escrow once.
 */
public interface DuelStakes extends SqlObject {

    /** @param data the Base64 of one item's bytes, as {@link PendingItems} stores it */
    record Staked(String item, int amount, String data) {
        public Staked {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(data, "data");
            if (amount <= 0) {
                throw new IllegalArgumentException("a staked stack holds at least one item, not " + amount);
            }
        }
    }

    record Offer(DiscordId owner, int aura, List<Staked> items) {
        public Offer {
            Objects.requireNonNull(owner, "owner");
            items = List.copyOf(items);
            if (aura < 0) {
                throw new IllegalArgumentException("an offer stakes no negative aura: " + aura);
            }
        }
    }

    record Row(
            DiscordId owner,
            int aura,
            @Nullable String item,
            @Nullable Integer amount,
            @Nullable String data) {}

    /**
     * Holds both offers and books their aura off, or holds nothing when a balance no longer covers its offer.
     *
     * @return whether both offers are held
     */
    @Transaction
    default boolean lock(final UUID duel, final List<Offer> offers) {
        final AuraDao aura = getHandle().attach(AuraDao.class);
        for (final Offer offer : offers) {
            aura.bumpAura(offer.owner(), 0); // locks the balance until the transaction ends
            if (aura.auraOf(offer.owner()).orElse(0) < offer.aura()) {
                return false;
            }
        }
        for (final Offer offer : offers) {
            holdAura(duel, offer.owner(), offer.aura());
            offer.items().forEach(each -> holdItem(duel, offer.owner(), each.item(), each.amount(), each.data()));
            if (offer.aura() > 0) {
                aura.addAura(offer.owner(), -offer.aura(), new AuraCause.Stake(false));
            }
        }
        return true;
    }

    /**
     * Gives both stakes to the winner: the items into the pending store, the aura onto the balance.
     *
     * @param kit the name of the kit the duel was fought with, for the aura history
     * @return the aura the winner got, or empty when the duel holds nothing, because it paid out or refunded before
     */
    @Transaction
    default OptionalInt payOut(final UUID duel, final DiscordId winner, final DiscordId loser, final String kit) {
        final List<Row> rows = release(duel);
        if (rows.isEmpty()) {
            return OptionalInt.empty();
        }
        final PendingItems pending = getHandle().attach(PendingItems.class);
        int won = 0;
        for (final Row row : rows) {
            won += row.aura();
            if (row.item() != null) {
                pending.store(
                        winner,
                        row.item(),
                        Objects.requireNonNull(row.amount()),
                        Objects.requireNonNull(row.data()),
                        "",
                        false,
                        false);
            }
        }
        final AuraDao aura = getHandle().attach(AuraDao.class);
        aura.addAura(winner, won, new AuraCause.Duel(true, kit));
        aura.addAura(loser, 0, new AuraCause.Duel(false, kit));
        return OptionalInt.of(won);
    }

    /**
     * Gives each owner their own stake back: the items into the pending store, the aura onto the balance.
     *
     * @return the owners that got something back, empty when the duel holds nothing
     */
    @Transaction
    default Set<DiscordId> refund(final UUID duel) {
        final PendingItems pending = getHandle().attach(PendingItems.class);
        final AuraDao aura = getHandle().attach(AuraDao.class);
        final Set<DiscordId> owners = new LinkedHashSet<>();
        for (final Row row : release(duel)) {
            owners.add(row.owner());
            if (row.item() != null) {
                pending.store(
                        row.owner(),
                        row.item(),
                        Objects.requireNonNull(row.amount()),
                        Objects.requireNonNull(row.data()),
                        "",
                        false,
                        true);
            }
            if (row.aura() > 0) {
                aura.addAura(row.owner(), row.aura(), new AuraCause.Stake(true));
            }
        }
        return owners;
    }

    /**
     * Refunds every duel still held, which a server stop or crash left unpaid. smp calls it at start, before any lock.
     *
     * @return the owners that got something back
     */
    default Set<DiscordId> refundLeftover() {
        final Set<DiscordId> owners = new LinkedHashSet<>();
        heldDuels().forEach(duel -> owners.addAll(refund(duel)));
        return owners;
    }

    /** Puts items that never reached the escrow into the owner's pending store, for an owner who went offline. */
    default void giveBack(final DiscordId owner, final List<Staked> items) {
        final PendingItems pending = getHandle().attach(PendingItems.class);
        items.forEach(each -> pending.store(owner, each.item(), each.amount(), each.data(), "", false, true));
    }

    @SqlUpdate("INSERT INTO smp_duel_stake (duel, discord_id, aura) VALUES (:duel, :owner, :aura)")
    void holdAura(@Bind("duel") UUID duel, @Bind("owner") DiscordId owner, @Bind("aura") int aura);

    @SqlUpdate("""
            INSERT INTO smp_duel_stake (duel, discord_id, item, amount, data)
            VALUES (:duel, :owner, :item, :amount, :data)
            """)
    void holdItem(
            @Bind("duel") UUID duel,
            @Bind("owner") DiscordId owner,
            @Bind("item") String item,
            @Bind("amount") int amount,
            @Bind("data") String data);

    @SqlQuery("""
            DELETE FROM smp_duel_stake
            WHERE duel = :duel
            RETURNING discord_id AS owner, aura, item, amount, data
            """)
    @RegisterConstructorMapper(Row.class)
    List<Row> release(@Bind("duel") UUID duel);

    @SqlQuery("SELECT DISTINCT duel FROM smp_duel_stake")
    List<UUID> heldDuels();
}
