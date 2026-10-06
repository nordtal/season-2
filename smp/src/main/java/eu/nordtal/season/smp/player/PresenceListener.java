package eu.nordtal.season.smp.player;

import eu.nordtal.season.displaytags.nametag.NameTagCreateEvent;
import eu.nordtal.season.papercommon.chat.SystemLines;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Join and quit: the operator grant, the surfaces, and the language the join line waits for.
 *
 * An admin is a server operator from join to quit, through {@link AdminOperators}.
 */
public final class PresenceListener implements Listener {

    private final Plugin plugin;
    private final PlayerSurfaces surfaces;
    private final SystemLines lines;
    // The season's opening moment; a callback so this package does not depend on the welcome feature.
    private final Consumer<Player> languageReady;

    public PresenceListener(
            final Plugin plugin,
            final PlayerSurfaces surfaces,
            final SystemLines lines,
            final Consumer<Player> languageReady) {
        this.plugin = plugin;
        this.surfaces = surfaces;
        this.lines = lines;
        this.languageReady = languageReady;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        // Identities is already filled for this player.
        surfaces.refresh(event.getPlayer());
        // Everybody else's ordering depends on who is online, and this player is new to that set.
        PaperScheduler.of(plugin).onMain(surfaces::refreshAll);
    }

    /** Redraws a joined player's surfaces once every join handler ran, and says they arrived. */
    public void languageKnown(final Player player) {
        surfaces.refresh(player);
        lines.announceJoin(player);
        languageReady.accept(player);
    }

    /**
     * Fills in a nametag the moment DisplayTags creates one, on every path that creates it.
     *
     * DisplayTags writes its own lines while constructing the tag, so anything written earlier is overwritten.
     */
    @EventHandler
    public void onNameTagCreate(final NameTagCreateEvent event) {
        surfaces.applyTo(event.getNameTag());
    }
}
