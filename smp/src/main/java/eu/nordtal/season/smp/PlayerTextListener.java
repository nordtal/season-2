package eu.nordtal.season.smp;

import eu.nordtal.season.papercommon.chat.PlayerTextComponents;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

/** Filters player-written signs, anvil names and books. */
final class PlayerTextListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onSign(final SignChangeEvent event) {
        for (int index = 0; index < event.lines().size(); index++) {
            event.line(index, filtered(event.lines().get(index)));
        }
    }

    @EventHandler
    public void onAnvil(final PrepareAnvilEvent event) {
        final ItemStack result = event.getResult();
        if (result == null) {
            return;
        }
        result.editMeta(meta -> {
            final Component name = meta.customName();
            if (name != null) {
                meta.customName(filtered(name));
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onBook(final PlayerEditBookEvent event) {
        final BookMeta meta = event.getNewBookMeta();
        meta.pages(meta.pages().stream().map(PlayerTextListener::filtered).toList());
        final Component title = meta.title();
        if (title != null) {
            meta.title(filtered(title));
        }
        event.setNewBookMeta(meta);
    }

    private static Component filtered(final Component text) {
        return PlayerTextComponents.withoutPrivateUse(text);
    }
}
