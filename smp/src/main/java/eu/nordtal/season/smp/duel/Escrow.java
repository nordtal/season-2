package eu.nordtal.season.smp.duel;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.smp.port.ItemRewards;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.inventory.ItemStack;

/**
 * Runs the {@link DuelStakes} writes off the main thread and hands what they pay to the players online.
 *
 * {@link #stop} waits for the writes still running, so a server stop between lock and payout loses nothing.
 */
public final class Escrow {

    private static final long STOP_WAIT_SECONDS = 10;

    private final DuelStakes stakes;
    private final ItemRewards handout;
    private final Executor background;
    private final Logger logger;

    /** One party for {@link #stop} and one for each write still running. */
    private final Phaser running = new Phaser(1);

    public Escrow(final DuelStakes stakes, final ItemRewards handout, final Executor background, final Logger logger) {
        this.stakes = stakes;
        this.handout = handout;
        this.background = background;
        this.logger = logger;
    }

    /** Returns a stack as a stake holds it: its item key, its count and the bytes of one item. */
    static DuelStakes.Staked staked(final ItemStack stack) {
        return new DuelStakes.Staked(
                stack.getType().getKey().asString(),
                stack.getAmount(),
                Base64.getEncoder().encodeToString(stack.asOne().serializeAsBytes()));
    }

    /**
     * Holds both offers and then tells {@code answered}, in the background, whether both are held.
     *
     * When they are not, the items go back to their owners through the pending store, which reaches offline owners too.
     */
    void lock(final UUID duel, final List<DuelStakes.Offer> offers, final Consumer<Boolean> answered) {
        later(() -> {
            boolean held;
            try {
                held = stakes.lock(duel, offers);
            } catch (final RuntimeException failure) {
                logger.log(Level.WARNING, "duel stake lock failed, the items go back", failure);
                held = false;
            }
            if (!held) {
                offers.forEach(offer -> {
                    stakes.giveBack(offer.owner(), offer.items());
                    handout.handOutIfOnline(offer.owner());
                });
            }
            answered.accept(held);
        });
    }

    /** Gives both stakes of {@code duel} to the winner, once. */
    void payOut(final UUID duel, final DiscordId winner, final DiscordId loser, final String kit) {
        later(() -> {
            stakes.payOut(duel, winner, loser, kit);
            handout.handOutIfOnline(winner);
        });
    }

    /** Gives each owner their own stake of {@code duel} back, once. */
    void refund(final UUID duel) {
        later(() -> {
            stakes.refund(duel).forEach(handout::handOutIfOnline);
        });
    }

    /** Refunds the duels a stop or crash left locked before {@code since}. */
    public void refundLeftover(final Instant since) {
        later(() -> {
            stakes.refundLeftover(since).forEach(handout::handOutIfOnline);
        });
    }

    /** Waits for the writes still running; smp calls it while it stops, before the item work closes. */
    void stop() {
        try {
            running.awaitAdvanceInterruptibly(running.arrive(), STOP_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException late) {
            logger.warning("duel stake writes still running at stop: count=" + running.getUnarrivedParties());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs {@code work} in the background and logs its failure. */
    private void later(final Runnable work) {
        running.register();
        try {
            background.execute(() -> {
                try {
                    work.run();
                } catch (final RuntimeException failure) {
                    logger.log(Level.WARNING, "duel stake write failed, the next start refunds it", failure);
                } finally {
                    running.arriveAndDeregister();
                }
            });
        } catch (final RuntimeException refused) {
            running.arriveAndDeregister();
            logger.log(Level.WARNING, "duel stake write refused, the next start refunds it", refused);
        }
    }
}
