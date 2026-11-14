package eu.nordtal.season.smp.pack;

import eu.nordtal.season.database.access.PackExemptions;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    // The players still loading the pack of the swap under way, by player. Main thread only.
    private final Map<UUID, Loading> loading = new HashMap<>();

    private record Loading(long request, UUID pack, long sent) {}

    public PackSwap(final Plugin plugin, final DataSource pool) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.inbox = Inbox.over(pool, SmpRequest.TABLE);
        this.exemptions = PackExemptions.using(pool);
        this.log = plugin.getLogger();
    }

    /** Returns the id the proxy gives the pack of {@code sha1}, so a client holds one pack for it, not two. */
    static UUID idOf(final String sha1) {
        return UUID.nameUUIDFromBytes(sha1.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Sends the pack at {@code url} to every player here who is not exempt.
     * It leaves {@code request} running until each of them is done with it. Runs on the hub's thread.
     */
    public Answer send(final long request, final String url, final String sha1) {
        final List<UUID> here = onMainList();
        final Set<UUID> exempt = exemptions.exemptAmong(here);
        final ResourcePackRequest pack = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(idOf(sha1), URI.create(url), sha1))
                .replace(true)
                .required(true)
                .build();
        PaperScheduler.of(plugin).onMain(() -> {
            int sent = 0;
            for (final UUID id : here) {
                final Player player = plugin.getServer().getPlayer(id);
                if (player == null || exempt.contains(id)) {
                    continue;
                }
                loading.put(id, new Loading(request, idOf(sha1), System.nanoTime()));
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
        return online.join();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(final PlayerResourcePackStatusEvent event) {
        final Loading held = loading.get(event.getPlayer().getUniqueId());
        if (held == null || !held.pack().equals(event.getID()) || !isFinal(event.getStatus())) {
            return;
        }
        loading.remove(event.getPlayer().getUniqueId());
        log.info("pack swap answered: request=" + held.request() + " player="
                + event.getPlayer().getName() + " status=" + event.getStatus() + " ms="
                + (System.nanoTime() - held.sent()) / 1_000_000);
        settleIfDone(held.request());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final Loading held = loading.remove(event.getPlayer().getUniqueId());
        if (held != null) {
            settleIfDone(held.request());
        }
    }

    /** Keeps a loading player in place, while they may still turn their head. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (loading.containsKey(event.getPlayer().getUniqueId()) && event.hasChangedPosition()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && loading.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    static boolean isFinal(final PlayerResourcePackStatusEvent.Status status) {
        return switch (status) {
            case SUCCESSFULLY_LOADED, DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> true;
            case ACCEPTED, DOWNLOADED -> false;
        };
    }

    /** Lets go of every player of {@code request} who is still loading once the hold ran out. */
    private void release(final long request) {
        final int before = loading.size();
        loading.values().removeIf(held -> held.request() == request);
        if (before != loading.size()) {
            log.warning("pack swap hold ran out: request=" + request + " players=" + (before - loading.size()));
            settle(request);
        }
    }

    private void settleIfDone(final long request) {
        if (loading.values().stream().noneMatch(held -> held.request() == request)) {
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
