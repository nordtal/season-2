package eu.nordtal.season.smp.pack;

import eu.nordtal.season.database.access.PackExemptions;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.pack.PackId;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.sql.DataSource;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

/**
 * Sends the current resource pack to the players here in place of theirs, for a live swap.
 * Each player stands still and takes no damage until their client is done with the pack or {@link #HOLD} ran out.
 * A declined or failed pack only ends the hold: the proxy sees the same answer and disconnects them as at join.
 */
public final class PackSwap implements Listener {

    /** The longest a player stands still for one pack, so a client that never answers does not hold them. */
    static final Duration HOLD = Duration.ofSeconds(60);

    private final Plugin plugin;
    private final Inbox<SmpRequest> inbox;
    private final PackExemptions exemptions;
    private final Logger log;

    /** The longest the hub's thread waits for the main thread's list of the players here. */
    private static final Duration LIST = Duration.ofSeconds(10);

    // The players still loading the pack of a swap. Main thread only.
    private final SwapHolds holds = new SwapHolds();

    public PackSwap(final Plugin plugin, final DataSource pool) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.inbox = Inbox.over(pool, SmpRequest.TABLE);
        this.exemptions = PackExemptions.using(pool);
        this.log = plugin.getLogger();
    }

    /**
     * Sends the pack at {@code url} to every player here who is not exempt.
     * It leaves {@code request} running until each of them is done with it. Runs on the hub's thread.
     */
    public Answer send(final long request, final String url, final String sha1) {
        final List<UUID> here = onMainList();
        final Set<UUID> exempt = exemptions.exemptAmong(here);
        final UUID id = PackId.of(sha1);
        final ResourcePackRequest pack = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(id, URI.create(url), sha1))
                .replace(true)
                .required(true)
                .build();
        PaperScheduler.of(plugin).onMain(() -> {
            int sent = 0;
            for (final UUID uuid : here) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player == null || exempt.contains(uuid)) {
                    continue;
                }
                holds.hold(uuid, request, id, System.nanoTime()).ifPresent(this::settle);
                player.sendResourcePacks(pack);
                sent++;
            }
            log.info("pack swap sent: request=" + request + " players=" + sent + " exempt=" + exempt.size() + " sha1="
                    + sha1);
            if (sent == 0) {
                settle(request);
                return;
            }
            PaperScheduler.of(plugin).onMainAfter(HOLD, () -> release(request));
        });
        return Answer.open();
    }

    private List<UUID> onMainList() {
        final CompletableFuture<List<UUID>> online = new CompletableFuture<>();
        PaperScheduler.of(plugin)
                .onMain(() -> online.complete(plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getUniqueId)
                        .toList()));
        return online.orTimeout(LIST.toMillis(), TimeUnit.MILLISECONDS).join();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(final PlayerResourcePackStatusEvent event) {
        holds.answer(event.getPlayer().getUniqueId(), event.getID(), event.getStatus(), System.nanoTime())
                .ifPresent(answered -> {
                    log.info("pack swap answered: request=" + answered.request() + " player="
                            + event.getPlayer().getName() + " status=" + event.getStatus() + " ms="
                            + answered.millis());
                    if (answered.last()) {
                        settle(answered.request());
                    }
                });
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        holds.leave(event.getPlayer().getUniqueId()).ifPresent(this::settle);
    }

    /** Keeps a loading player in place, while they may still turn their head. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (holds.holds(event.getPlayer().getUniqueId()) && event.hasChangedPosition()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && holds.holds(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Lets go of every player of {@code request} who is still loading once the hold ran out. */
    private void release(final long request) {
        final int released = holds.release(request);
        if (released > 0) {
            log.warning("pack swap hold ran out: request=" + request + " players=" + released);
            settle(request);
        }
    }

    private void settle(final long request) {
        PaperScheduler.of(plugin).execute(() -> {
            if (inbox.settle(request, Outcome.done(null)).isEmpty()) {
                log.info("pack swap withdrawn before every player loaded it: request=" + request);
            }
        });
    }
}
