package eu.nordtal.season.smp.takein;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.inbox.SmpRequest.TakePosition;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Answers where an admin's player stands, or which block they look at, which Steward puts into a position setting.
 * It runs on the hub's thread, reads the player on the main thread and settles the request with the place.
 */
public final class PositionTake {

    /** The farthest block a look reaches, as far as a player sees one clearly; the no-block refusal names it. */
    static final int REACH = 64;

    private static final long WAIT_SECONDS = 5;

    private final Plugin plugin;
    private final Inbox<SmpRequest> inbox;

    public PositionTake(final Plugin plugin, final DataSource pool) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.inbox = Inbox.over(pool, SmpRequest.TABLE);
    }

    /** Settles {@code request} with the player's place, or refuses while the player is not here or sees no block. */
    public Answer take(final long request, final PlayerId player, final boolean block) {
        final Found found = onMain(() -> find(player, block));
        if (found.refusal() != null) {
            return Answer.refused(found.refusal().with());
        }
        if (inbox.settle(request, Outcome.done(found.place())).isEmpty()) {
            plugin.getLogger().info("position take withdrawn before it was answered: request=" + request);
        }
        return Answer.open();
    }

    private record Found(
            TakePosition.@Nullable Answer place, @Nullable ServerRefusal refusal) {}

    private Found find(final PlayerId player, final boolean block) {
        final Player here = plugin.getServer().getPlayer(player.value());
        if (here == null) {
            return new Found(null, ServerRefusal.NOT_HERE);
        }
        final Location feet = Objects.requireNonNull(here.getLocation(), "location");
        if (!block) {
            return new Found(place(feet, feet.getX(), feet.getY(), feet.getZ()), null);
        }
        final Block seen = here.getTargetBlockExact(REACH);
        return seen == null
                ? new Found(null, ServerRefusal.NO_BLOCK_IN_SIGHT)
                : new Found(place(feet, seen.getX(), seen.getY(), seen.getZ()), null);
    }

    private static TakePosition.Answer place(final Location facing, final double x, final double y, final double z) {
        return answer(facing.getWorld().getName(), x, y, z, facing.getYaw(), facing.getPitch());
    }

    /** Returns the place to the hundredth of a block and the facing to the tenth of a degree, from -180 to 180. */
    static TakePosition.Answer answer(
            final String world, final double x, final double y, final double z, final float yaw, final float pitch) {
        return new TakePosition.Answer(
                world,
                hundredth(x),
                hundredth(y),
                hundredth(z),
                tenth(Location.normalizeYaw(yaw)),
                tenth(Location.normalizePitch(pitch)));
    }

    private static double hundredth(final double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static float tenth(final float value) {
        return Math.round(value * 10) / 10f;
    }

    private <T> T onMain(final Supplier<T> work) {
        final CompletableFuture<T> done =
                CompletableFuture.supplyAsync(work, PaperScheduler.of(plugin).mainThread());
        try {
            return done.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final ExecutionException | TimeoutException failed) {
            throw new IllegalStateException("the player could not be read", failed);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
