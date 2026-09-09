package eu.nordtal.s2.smp.player;

import eu.nordtal.displaytags.api.events.NameTagCreateEvent;
import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.papercommon.chat.SystemLines;
import eu.nordtal.s2.smp.welcome.SeasonWelcome;

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
 * <p>Chat itself is per Paper server: the SMP is one server holding four worlds, so Nordtal, the
 * farm world, the Nether and the End share one chat. The composition in front of the message is
 * drawn by {@link SystemLines} in {@code :paper-common}.
 *
 * <p>An admin becomes a server <b>operator</b> at join and stops being one at quit, through
 * {@link AdminOperators}. The admin flag is mirrored from Discord into the database by the bot, so
 * there is one truth and nothing to reconcile.
 */
public final class PresenceListener implements Listener {

    private final Plugin plugin;
    private final Identities identities;
    private final PlayerSurfaces surfaces;
    private final PlayerLocales locales;
    private final AdminOperators operators;
    private final SystemLines lines;
    private final SeasonWelcome welcome;

    public PresenceListener(final Plugin plugin, final Identities identities,
                            final PlayerSurfaces surfaces, final PlayerLocales locales,
                            final AdminOperators operators, final SystemLines lines,
                            final SeasonWelcome welcome) {
        this.plugin = plugin;
        this.identities = identities;
        this.surfaces = surfaces;
        this.locales = locales;
        this.operators = operators;
        this.lines = lines;
        this.welcome = welcome;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        // Identities is already filled for this player - JoinGate reads it at pre-login, on the
        // thread that is allowed to wait - so this is a map read and not a query.
        operators.onJoin(player.getUniqueId(), identities.of(player.getUniqueId()).admin());
        surfaces.refresh(player);

        // Everybody else's ordering depends on who is online, and this player is new to that set.
        Bukkit.getScheduler().runTask(plugin, surfaces::refreshAll);
        loadLanguage(player);
    }

    /**
     * Reads the player's language off the main thread and redraws their surfaces once it is known.
     *
     * <p>Without this call {@code PlayerLocales#of} answers English for every player, so
     * {@code LocaleJoinWiringTest} in {@code :common} fails the build if a backend omits it. The
     * HUD and the boards pick the language up on their own timers; the tab-list header does not,
     * which is why {@code refresh} runs again once the value has landed.</p>
     */
    private void loadLanguage(final Player player) {
        locales.joinAsync(player.getUniqueId(), task -> Bukkit.getScheduler()
                        .runTaskAsynchronously(plugin, task))
                // whenComplete rather than thenRun: a load that fails still has to let the join
                // line through, in English, rather than swallow it.
                .whenComplete((locale, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        // They left while the query was in flight, so the entry this just wrote
                        // would stay for the life of the process. Unless they are already back:
                        // the cache is keyed by UUID, and a callback from the previous session
                        // would drop the language the new one has just loaded.
                        if (Bukkit.getPlayer(player.getUniqueId()) == null) {
                            locales.quit(player.getUniqueId());
                        }
                        return;
                    }
                    surfaces.refresh(player);
                    // Here, and not in a join handler: this is the first moment the line can be
                    // rendered in the language of the player it is about. See
                    // SystemLines#announceJoin.
                    lines.announceJoin(player);
                    // ...and for the same reason, the season's opening moment. Its subtitle would
                    // be English for every player if it ran from PlayerJoinEvent, and there is no
                    // second event that fires when the language lands - this callback is it.
                    welcome.onLanguageReady(player);
                }));
    }

    /**
     * Fills in a nametag the moment DisplayTags creates one.
     *
     * <p>The only place the composition reliably reaches the tag: DisplayTags applies its own
     * configured lines while constructing the tag and fires this event afterwards, so anything
     * written at join or on a later Bukkit event is overwritten or races the render. This covers
     * every path that creates a tag: join, a world change, a reload.</p>
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
