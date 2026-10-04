package eu.nordtal.s2.smp.navigate;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.papercommon.time.PaperScheduler;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** Clicks in the {@code /navigate} list, and remembering where somebody died. */
public final class NavigateListener implements Listener {

    private final Plugin plugin;
    private final SmpDao dao;
    private final Navigation navigation;
    private final Identities identities;
    private final SmpSounds sounds;

    public NavigateListener(
            final Plugin plugin,
            final SmpDao dao,
            final Navigation navigation,
            final Identities identities,
            final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.navigation = navigation;
        this.identities = identities;
        this.sounds = sounds;
    }

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof NavigateGui gui)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final NavigateGui.Click click = gui.click(player, event.getRawSlot());
        if (click.sound() != null) {
            sounds.play(player, click.sound());
        }
        // A page turn is an OPEN, not a redraw.
        if (click.open() != null) {
            player.openInventory(click.open().getInventory());
        } else if (click.close()) {
            player.closeInventory();
        }
    }

    /** Records where a death happened, one row per player, overwritten by the next. */
    @EventHandler(ignoreCancelled = true)
    public void onDeath(final PlayerDeathEvent event) {
        final Player player = event.getEntity();
        final Location at = Objects.requireNonNull(player.getLocation());
        final DiscordId discordId = identities.discordIdOf(player.getUniqueId()).orElse(null);
        if (discordId == null) {
            return;
        }
        PaperScheduler.of(plugin)
                .execute(() -> dao.rememberDeath(
                        discordId, at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ()));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        navigation.clear(event.getPlayer().getUniqueId());
    }
}
