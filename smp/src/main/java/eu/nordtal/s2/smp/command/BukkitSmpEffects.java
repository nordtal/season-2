package eu.nordtal.s2.smp.command;

import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.OpenPayment;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.db.AuraPlace;
import eu.nordtal.s2.smp.db.AuraRow;
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
 * {@link SmpEffects} against this server.
 *
 * <b>Two of these exist, and the {@link Executor} is the difference</b>
 *
 * The one behind {@code /smp} in chat is built with the plugin's async scheduler, because a Brigadier handler runs
 * on the main thread and none of this may block it. The one behind the command inbox is built with
 * {@code Runnable::run}, because the inbox settles the request row when the command returns - hand the work to
 * another thread there and the answer is written before it exists. {@code CommandInbox#register} refuses the wrong
 * one at startup.
 *
 * <b>Which thread each piece of work needs is decided here, and only here</b>
 *
 * That is the whole reason this class exists rather than the command calling Bukkit directly. Everything below is a
 * database round trip and belongs off the main thread.
 */
public final class BukkitSmpEffects implements SmpEffects, Standing {

    private final Plugin plugin;
    private final Executor executor;
    private final SmpDao dao;
    private final ObjectiveEngine engine;
    private final Identities identities;
    private final AccessDirectory access;
    private final java.util.function.Supplier<java.util.List<String>> reload;

    private final java.util.function.Function<java.util.Locale, Status> status;

    /**
     * The connection the three aura reads share, so they answer about one moment.
     *
     * {@code dao} is on-demand: every call takes its own connection, so a single aura event between them can leave
     * {@code /aura} printing a rank against a leaderboard from a different state. One handle in a
     * {@code REPEATABLE READ} transaction is what makes the three one answer.
     */
    private final org.jdbi.v3.core.Jdbi jdbi;

    public BukkitSmpEffects(
            final Plugin plugin,
            final Executor executor,
            final org.jdbi.v3.core.Jdbi jdbi,
            final SmpDao dao,
            final ObjectiveEngine engine,
            final Identities identities,
            final AccessDirectory access,
            final java.util.function.Supplier<java.util.List<String>> reload,
            final java.util.function.Function<java.util.Locale, Status> status) {
        this.status = java.util.Objects.requireNonNull(status, "status");
        this.plugin = plugin;
        this.executor = executor;
        this.jdbi = java.util.Objects.requireNonNull(jdbi, "jdbi");
        this.dao = dao;
        this.engine = engine;
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
            // Between the check and here somebody reloaded the track.
            throw new IllegalStateException("objective " + milestone + "/" + objective
                    + " disappeared while it was being" + " completed - the track was probably reloaded in between");
        }
        // null: an admin's escape hatch has nobody behind it, a network event rather than a congratulation.
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
        // On the server thread: both lookups read state the server owns, and an async task must not touch Bukkit's API.
        return onMainThread(() -> {
            final Player online = Bukkit.getPlayer(player);
            return online != null
                    ? Optional.of(online.getName())
                    : Optional.ofNullable(Bukkit.getOfflinePlayer(player).getName());
        });
    }

    @Override
    public Optional<String> discordIdOf(final UUID player) {
        // The cache first: filled at join for everybody here.
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
    public Status status(final java.util.Locale locale) {
        // Built by the plugin, which holds the season state, bundle and pool.
        return status.apply(locale);
    }

    @Override
    public Optional<OpenPayment> openPayment(final String discordId) {
        return access.openPayment(discordId);
    }

    /**
     * {@code /aura}: three reads and one hop to the server thread for the names.
     *
     * <b>Why the names are resolved in one hop and not one each</b>
     *
     * {@link #nameOf} waits for the server thread per call, which is the right shape for a command that names one
     * person
     * and the wrong one for a list of ten - ten round trips through the scheduler, on a command any player can type as
     * often as they like. So the whole list is resolved inside a single {@code callSyncMethod}.
     *
     * The database reads stay on this thread, which is an async one by construction: everything that reaches this class
     * comes through {@code CommandEffects#async} .
     */
    @Override
    public Optional<AuraStanding> auraStanding(final UUID player) {
        final Optional<String> discordId = discordIdOf(player);
        if (discordId.isEmpty()) {
            return Optional.empty();
        }
        // All three in one REPEATABLE READ transaction.
        final AuraSnapshot snapshot =
                jdbi.inTransaction(org.jdbi.v3.core.transaction.TransactionIsolationLevel.REPEATABLE_READ, handle -> {
                    final SmpDao attached = handle.attach(SmpDao.class);
                    // A player never given aura has no smp_player row yet.
                    final int own = attached.auraOf(discordId.get()).orElse(0);
                    return new AuraSnapshot(own, attached.auraPlace(own, discordId.get()), attached.topAura(10));
                });
        final int aura = snapshot.aura();
        final AuraPlace place = snapshot.place();
        final List<AuraRow> top = snapshot.top();

        final List<String> names = onMainThread(() -> top.stream()
                .map(row -> {
                    final Player online = Bukkit.getPlayer(row.mcUuid());
                    final String name = online != null
                            ? online.getName()
                            : Bukkit.getOfflinePlayer(row.mcUuid()).getName();
                    // The board falls back to the UUID's first eight characters.
                    return name == null ? row.mcUuid().toString().substring(0, 8) : name;
                })
                .toList());

        final List<AuraLine> lines = new java.util.ArrayList<>(top.size());
        for (int at = 0; at < top.size(); at++) {
            lines.add(new AuraLine(
                    at + 1,
                    names.get(at),
                    top.get(at).aura(),
                    top.get(at).mcUuid().equals(player)));
        }
        return Optional.of(new AuraStanding(aura, place.place(), place.total(), List.copyOf(lines)));
    }

    private <T> T onMainThread(final Callable<T> work) {
        if (Bukkit.isPrimaryThread()) {
            // Nothing in this class is called from the main thread today.
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
            // getCause() is only null for an ExecutionException built without one, which callSyncMethod never does.
            throw asUnchecked(java.util.Objects.requireNonNull(failure.getCause()));
        }
    }

    private static RuntimeException asUnchecked(final Throwable failure) {
        return failure instanceof RuntimeException unchecked ? unchecked : new IllegalStateException(failure);
    }

    /** The three aura reads, taken together. */
    private record AuraSnapshot(int aura, AuraPlace place, List<AuraRow> top) {}
}
