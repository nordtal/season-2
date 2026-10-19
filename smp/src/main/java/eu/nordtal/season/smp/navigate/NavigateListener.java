package eu.nordtal.season.smp.navigate;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.feedback.SmpSounds;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public final class NavigateListener implements Listener {

    record Arrived(String target) {}

    static final DocumentSpec<Arrived> ARRIVED =
            DocumentSpec.game("smp", "navigate-arrived", "Navigate", DocumentSpec.Shape.CHAT, Arrived.class);

    private final Plugin plugin;
    private final PlaceDao dao;
    private final Navigation navigation;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final SmpSounds sounds;

    public NavigateListener(
            final Plugin plugin,
            final PlaceDao dao,
            final Navigation navigation,
            final Identities identities,
            final MessageRenderer renderer,
            final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.navigation = navigation;
        this.identities = identities;
        this.renderer = renderer;
        this.sounds = sounds;
    }

    /** Ends the navigation when the player reaches its target. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        final Player player = event.getPlayer();
        final Location to = event.getTo();
        navigation
                .arrive(player.getUniqueId(), to.getWorld().getName(), to.getX(), to.getZ())
                .ifPresent(target -> {
                    final java.util.Locale locale = identities.languageOf(player.getUniqueId());
                    player.sendMessage(renderer.format(
                            locale, ARRIVED.with(new Arrived(NavigatePage.label(target, renderer, locale)))));
                    sounds.play(player, Feedback.SMALL_SUCCESS);
                });
    }

    /** Stores the death place of the player, replacing the previous one. */
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
