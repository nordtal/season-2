package eu.nordtal.s2.smp.command;

import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.OpenPayment;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.progress.ObjectiveEngine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@link SmpEffects} against this server; the one place that decides which thread each piece of work needs.
 *
 * The chat instance uses the async scheduler; the inbox's runs inline, since it settles its row on return.
 */
public final class BukkitSmpEffects implements SmpEffects {

    private final Plugin plugin;
    private final Executor executor;
    private final SmpDao dao;
    private final ObjectiveEngine engine;
    private final Identities identities;
    private final AccessDirectory access;
    private final java.util.function.Supplier<java.util.List<String>> reload;

    public BukkitSmpEffects(
            final Plugin plugin,
            final Executor executor,
            final SmpDao dao,
            final ObjectiveEngine engine,
            final Identities identities,
            final AccessDirectory access,
            final java.util.function.Supplier<java.util.List<String>> reload) {
        this.plugin = plugin;
        this.executor = executor;
        this.dao = dao;
        this.engine = engine;
        this.identities = identities;
        this.access = access;
        this.reload = reload;
    }

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
    public java.util.List<String> reload() {
        return reload.get();
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
            // Somebody reloaded the track since the check.
            throw new IllegalStateException("objective " + milestone + "/" + objective
                    + " disappeared while it was being" + " completed - the track was probably reloaded in between");
        }
        // Null: an admin's completion has nobody behind it.
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
        // Both lookups read state the server owns, which an async task must not touch.
        return onMainThread(() -> {
            final Player online = Bukkit.getPlayer(player);
            return online != null
                    ? Optional.of(online.getName())
                    : Optional.ofNullable(Bukkit.getOfflinePlayer(player).getName());
        });
    }

    @Override
    public Optional<String> discordIdOf(final UUID player) {
        // The cache first, filled at join for everybody here.
        return identities.discordIdOf(player).or(() -> dao.discordIdOf(player));
    }

    @Override
    public void changeAura(final UUID player, final String discordId, final int delta, final String by) {
        dao.addAura(discordId, delta, AuraReason.ADMIN.stored(), "by " + by);
        dao.auraOf(discordId).ifPresent(now -> identities.recordAura(player, now));
        plugin.getLogger().info(by + " changed " + player + "'s aura by " + delta);
    }

    @Override
    public Optional<Access> access(final UUID player) {
        final AccessState state = access.accessState(player);
        return Optional.of(new Access(state.discordId(), state.accessActive(), state.accessValidUntil()));
    }

    @Override
    public Optional<OpenPayment> openPayment(final String discordId) {
        return access.openPayment(discordId);
    }

    private <T> T onMainThread(final Callable<T> work) {
        if (Bukkit.isPrimaryThread()) {
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
            throw new IllegalStateException("interrupted while waiting for the server thread", interrupted);
        } catch (final ExecutionException failure) {
            // {@code callSyncMethod} always sets a cause.
            throw asUnchecked(java.util.Objects.requireNonNull(failure.getCause()));
        }
    }

    private static RuntimeException asUnchecked(final Throwable failure) {
        return failure instanceof RuntimeException unchecked ? unchecked : new IllegalStateException(failure);
    }

}
