package eu.nordtal.s2.smp.progress;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Objective;
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
 * Counts the players who already hold the active milestone's gate advancement, however long ago they earned it.
 *
 * Reads everyone online when the active gate changes and a player on join, on the main thread; credits run off it.
 */
public final class GateHolders implements Listener {

    /** What the count reads from the running server, always on the main thread. */
    public interface Server {

        /** Returns every player online now. */
        Collection<UUID> online();

        /** Returns whether this player is online with every criterion of the advancement done. */
        boolean holds(UUID player, NamespacedKey advancement);

        /** Returns the server this plugin runs on. */
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

    /** The active milestone and the track it was last read under, replaced whole so no reader mixes two. */
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
     * Creates the count.
     *
     * @param track the milestone track, as a supplier, because a settings change replaces it
     * @param discordIds whose Discord account an online player's credit goes to; read on the main thread
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

    /** Takes the active milestone as the async refresh read it, and reads everyone online when its gate changed. */
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

    /** Counts a player who has just joined, on the main thread. */
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
