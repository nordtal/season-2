package eu.nordtal.s2.smp.player;

import eu.nordtal.displaytags.api.events.NameTagCreateEvent;
import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.papercommon.chat.SystemLines;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Join and quit: the operator grant, the surfaces, and the language the join line waits for.
 *
 * An admin is a server operator from join to quit, through {@link AdminOperators}.
 */
public final class PresenceListener implements Listener {

    private final Plugin plugin;
    private final Identities identities;
    private final PlayerSurfaces surfaces;
    private final PlayerLocales locales;
    private final AdminOperators operators;
    private final SystemLines lines;
    // The season's opening moment; a callback so this package does not depend on the welcome feature.
    private final Consumer<Player> languageReady;

    public PresenceListener(
            final Plugin plugin,
            final Identities identities,
            final PlayerSurfaces surfaces,
            final PlayerLocales locales,
            final AdminOperators operators,
            final SystemLines lines,
            final Consumer<Player> languageReady) {
        this.plugin = plugin;
        this.identities = identities;
        this.surfaces = surfaces;
        this.locales = locales;
        this.operators = operators;
        this.lines = lines;
        this.languageReady = languageReady;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        // Identities is already filled for this player.
        operators.onJoin(
                player.getUniqueId(), identities.of(player.getUniqueId()).admin());
        surfaces.refresh(player);

        // Everybody else's ordering depends on who is online, and this player is new to that set.
        Bukkit.getScheduler().runTask(plugin, surfaces::refreshAll);
        loadLanguage(player);
    }

    /**
     * Reads the player's language off the main thread and redraws their surfaces once it is known.
     *
     * {@code LocaleJoinWiringTest} fails the build if this call is missing.
     */
    private void loadLanguage(final Player player) {
        // Named rather than chained.
        final var _ = locales.joinAsync(
                        player.getUniqueId(), task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task))
                // whenComplete rather than thenRun.
                .whenComplete((locale, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        // Unless already back.
                        if (Bukkit.getPlayer(player.getUniqueId()) == null) {
                            locales.quit(player.getUniqueId());
                        }
                        return;
                    }
                    surfaces.refresh(player);
                    // Here, not in a join handler.
                    lines.announceJoin(player);
                    // Same reason for the opening moment; PlayerJoinEvent would give every player English.
                    languageReady.accept(player);
                }));
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

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        operators.onQuit(event.getPlayer().getUniqueId());
        locales.quit(event.getPlayer().getUniqueId());
        // Identities forgets them in JoinGate's quit handler, which owns the cache's lifetime.
    }
}
