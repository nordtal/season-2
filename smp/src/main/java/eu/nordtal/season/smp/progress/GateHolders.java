package eu.nordtal.season.smp.progress;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.Objective;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.Nullable;

/**
 * Credits the participation gate of the active milestone to players who already have its advancement.
 * It checks every online player when the gate changes and each player who joins.
 * It reads the server on the main thread and credits on {@code async}.
 */
public final class GateHolders implements Listener {

    /** The reads of {@link GateHolders} from the server, on the main thread. */
    public interface Server {

        Collection<UUID> online();

        /** Returns whether the player is online and has completed every criterion of the advancement. */
        boolean holds(UUID player, NamespacedKey advancement);

        static Server running() {
            return new Server() {
                @Override
                public Collection<UUID> online() {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getUniqueId)
                            .toList();
                }

                @Override
                public boolean holds(final UUID player, final NamespacedKey advancement) {
                    final Player online = Bukkit.getPlayer(player);
                    final Advancement found = Bukkit.getAdvancement(advancement);
                    return online != null
                            && found != null
                            && online.getAdvancementProgress(found).isDone();
                }
            };
        }
    }

    private record Gate(
            Optional<String> milestone, @Nullable MilestoneTrack track) {}

    private final Supplier<MilestoneTrack> track;
    private final Server server;
    private final Function<UUID, Optional<DiscordId>> discordIds;
    private final Executor mainThread;
    private final Executor async;
    private final ObjectiveEngine engine;

    private volatile Gate gate = new Gate(Optional.empty(), null);

    /**
     * Creates the listener.
     *
     * @param discordIds the Discord account of a player, read on the main thread
     */
    public GateHolders(
            final Supplier<MilestoneTrack> track,
            final Server server,
            final Function<UUID, Optional<DiscordId>> discordIds,
            final Executor mainThread,
            final Executor async,
            final ObjectiveEngine engine) {
        this.track = track;
        this.server = server;
        this.discordIds = discordIds;
        this.mainThread = mainThread;
        this.async = async;
        this.engine = engine;
    }

    /** Sets the active milestone and checks every online player when the milestone or the track changed. */
    public void setActiveMilestone(final Optional<String> key) {
        final Gate now = new Gate(key, track.get());
        final Gate before = gate;
        gate = now;
        if (key.isPresent() && (!key.equals(before.milestone()) || now.track() != before.track())) {
            mainThread.execute(() -> count(server.online()));
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        joined(event.getPlayer().getUniqueId());
    }

    /** Checks one player, on the main thread. */
    public void joined(final UUID player) {
        count(List.of(player));
    }

    private void count(final Collection<UUID> players) {
        final Optional<String> milestone = gate.milestone();
        if (milestone.isEmpty()) {
            return;
        }
        final List<NamespacedKey> advancements = track.get().milestone(milestone.get()).stream()
                .flatMap(active -> active.objectives().stream())
                .filter(Objective::isParticipationGate)
                .flatMap(objective -> objective.advancementKey().stream())
                .toList();
        for (final UUID player : players) {
            final Optional<DiscordId> discordId = discordIds.apply(player);
            if (discordId.isEmpty()) {
                continue;
            }
            for (final NamespacedKey advancement : advancements) {
                if (server.holds(player, advancement)) {
                    async.execute(() -> engine.creditAdvancement(discordId.get(), advancement, player));
                }
            }
        }
    }
}
