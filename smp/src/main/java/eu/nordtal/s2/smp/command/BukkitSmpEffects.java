package eu.nordtal.s2.smp.command;

import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.OpenPayment;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.farm.FarmWorldReset;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.progress.ObjectiveEngine;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * {@link SmpEffects} against this server.
 *
 * <h2>Two of these exist, and the {@link Executor} is the difference</h2>
 * The one behind {@code /smp} in chat is built with the plugin's async scheduler, because a
 * Brigadier handler runs on the main thread and none of this may block it. The one behind the
 * command inbox is built with {@code Runnable::run}, because the inbox settles the request row when
 * the command returns - hand the work to another thread there and the answer is written before it
 * exists. {@code CommandInbox#register} refuses the wrong one at startup.
 *
 * <h2>Which thread each piece of work needs is decided here, and only here</h2>
 * That is the whole reason this class exists rather than the command calling Bukkit directly.
 * Everything below is a database round trip and belongs off the main thread - except the farm world
 * reset, which unloads and deletes a world folder and can only happen <em>on</em> it. So that one
 * hops back and waits, which is exactly the inversion the command layer must not have to know
 * about.
 */
public final class BukkitSmpEffects implements SmpEffects {

    private final Plugin plugin;
    private final Executor executor;
    private final SmpDao dao;
    private final ObjectiveEngine engine;
    private final FarmWorldReset farmReset;
    private final Identities identities;
    private final AccessDirectory access;
    private final Runnable reload;

    public BukkitSmpEffects(final Plugin plugin, final Executor executor, final SmpDao dao,
                            final ObjectiveEngine engine, final FarmWorldReset farmReset,
                            final Identities identities, final AccessDirectory access,
                            final Runnable reload) {
        this.plugin = plugin;
        this.executor = executor;
        this.dao = dao;
        this.engine = engine;
        this.farmReset = farmReset;
        this.identities = identities;
        this.access = access;
        this.reload = reload;
    }

    /** Everything {@code /smp} does off the main thread, on the plugin's async scheduler. */
    public static Executor async(final Plugin plugin) {
        return task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void async(final Runnable work) {
        executor.execute(work);
    }

    @Override
    public void warn(final String what, final Throwable failure) {
        plugin.getLogger().log(java.util.logging.Level.WARNING, what, failure);
    }

    @Override
    public void reload() {
        reload.run();
    }

    @Override
    public void resetFarmWorld() {
        // On the main thread, and waited for: it unloads a world, deletes its folder and
        // regenerates it, none of which is safe from anywhere else. Waiting is what lets a failure
        // reach whoever asked instead of only the console.
        onMainThread(() -> {
            farmReset.resetNow();
            return null;
        });
    }

    @Override
    public Optional<String> activeMilestone() {
        return dao.activeMilestoneKey();
    }

    @Override
    public boolean hasObjective(final String milestone, final String objective) {
        return dao.objective(milestone, objective).isPresent();
    }

    @Override
    public void completeObjective(final String milestone, final String objective) {
        final Optional<ObjectiveRow> row = dao.objective(milestone, objective);
        if (row.isEmpty()) {
            // Between the check and here somebody reloaded the track. Rare, and cheaper to re-read
            // than to pass the row through a platform-free command layer that has no type for it.
            throw new IllegalStateException(
                    "objective " + milestone + "/" + objective + " disappeared while it was being"
                            + " completed - the track was probably reloaded in between");
        }
        // null: an admin's escape hatch has nobody standing behind it, so the milestone it may
        // complete is a network event for everybody rather than a congratulation for whoever typed
        // the command.
        engine.finishObjective(milestone, row.get(), null);
        plugin.getLogger().info("an admin completed objective " + milestone + "/" + objective);
    }

    @Override
    public void unlockMilestone(final String milestone) {
        engine.unlockMilestone(milestone, null);
        plugin.getLogger().info("an admin unlocked milestone " + milestone);
    }

    @Override
    public Optional<String> nameOf(final UUID player) {
        final Player online = Bukkit.getPlayer(player);
        return online != null
                ? Optional.of(online.getName())
                : Optional.ofNullable(Bukkit.getOfflinePlayer(player).getName());
    }

    @Override
    public Optional<String> discordIdOf(final UUID player) {
        // The cache first: it is filled at join for everybody here, and the row is what filled it.
        // A player the cache does not know is one who is not on this server, which the /smp aura
        // path can genuinely reach when the command travelled from Discord.
        return identities.discordIdOf(player).or(() -> dao.discordIdOf(player));
    }

    @Override
    public void changeAura(final UUID player, final String discordId, final int delta,
                           final String by) {
        dao.addAura(discordId, delta, AuraReason.ADMIN.stored(), "by " + by);
        dao.auraOf(discordId).ifPresent(now -> identities.recordAura(player, now));
        plugin.getLogger().info(by + " changed " + player + "'s aura by " + delta);
    }

    @Override
    public Optional<Access> access(final UUID player) {
        final AccessState state = access.accessState(player);
        return Optional.of(new Access(state.discordId(), state.accessActive(),
                state.accessValidUntil()));
    }

    @Override
    public Optional<OpenPayment> openPayment(final String discordId) {
        return access.openPayment(discordId);
    }

    private <T> T onMainThread(final Callable<T> work) {
        if (Bukkit.isPrimaryThread()) {
            // Nothing in this class is called from the main thread today. The branch is here so
            // that a future caller which is does not deadlock waiting for itself.
            try {
                return work.call();
            } catch (final Exception failure) {
                throw asUnchecked(failure);
            }
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, work).get();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the server thread",
                    interrupted);
        } catch (final ExecutionException failure) {
            throw asUnchecked(failure.getCause());
        }
    }

    private static RuntimeException asUnchecked(final Throwable failure) {
        return failure instanceof RuntimeException unchecked
                ? unchecked
                : new IllegalStateException(failure);
    }
}
