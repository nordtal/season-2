package eu.nordtal.s2.smp.player;

import eu.nordtal.displaytags.api.events.NameTagCreateEvent;
import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.papercommon.chat.SystemLines;

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
 * <h2>Chat needs no plugin, but the line in front of the message does</h2>
 * Chat itself is per Paper server, which is Minecraft's default: the SMP is one server holding four
 * worlds, so Nordtal, the farm world, the Nether and the End share one chat, and that is what keeps
 * a small community feeling like one place instead of four empty ones. The composition in front of
 * the message is drawn by {@link SystemLines} in {@code :paper-common} - it was drawn here until
 * 2026-09-09, which is exactly why the hunger games had none of it.
 *
 * <h2>Permissions without LuckPerms</h2>
 * An admin becomes a server <b>operator</b> at join and stops being one at quit, through
 * {@link AdminOperators}. The admin flag itself is mirrored from Discord into the database by the
 * bot, so there is one truth, no sync cycle, and nothing to reconcile (docs/smp.md#admins).
 *
 * <p>Until 2026-09-04 this attached a configured list of six permission nodes instead. A list
 * cannot answer "an admin must reliably have every permission" - it only knows what somebody wrote
 * down - so {@code config.yml#admin-permissions} is retired and {@link AdminOperators} carries the
 * whole reasoning, including why {@code ops.json} is swept at every enable.</p>
 */
public final class PresenceListener implements Listener {

    private final Plugin plugin;
    private final Identities identities;
    private final PlayerSurfaces surfaces;
    private final PlayerLocales locales;
    private final AdminOperators operators;
    private final SystemLines lines;

    // The composition, the config and the message bundle were constructor arguments until
    // 2026-09-09. The first two of those had already stopped being read by anything here; the third
    // went with the chat renderer. A field nothing reads is a dependency nothing needs, and it is
    // what makes a class look like it does more than it does.
    public PresenceListener(final Plugin plugin, final Identities identities,
                            final PlayerSurfaces surfaces, final PlayerLocales locales,
                            final AdminOperators operators, final SystemLines lines) {
        this.plugin = plugin;
        this.identities = identities;
        this.surfaces = surfaces;
        this.locales = locales;
        this.operators = operators;
        this.lines = lines;
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
     * <p>
     * Missing until 2026-09-05: this module built a {@link PlayerLocales} and handed it to fifteen
     * classes, and nothing ever called {@code joinAsync} - so {@code of()} answered English for
     * every player for the whole season, and the first German account on the local stack read
     * {@code /smp} in English while the proxy had just answered {@code /phase} in German.
     * {@code CLAUDE.md} said this module "inherits the rule rather than rediscovering it"; a rule
     * inherited by nobody. {@code LocaleJoinWiringTest} in {@code :common} is what makes the third
     * backend forgetting this a red build rather than a season in the wrong language.
     * </p>
     * <p>
     * Off the main thread for the reason limbo's listener spells out; the HUD and the boards render
     * from {@code of()} on their own timers and pick the language up by themselves, the tab list
     * header does not, which is why {@code refresh} runs again once the value has landed.
     * </p>
     */
    private void loadLanguage(final Player player) {
        locales.joinAsync(player.getUniqueId(), task -> Bukkit.getScheduler()
                        .runTaskAsynchronously(plugin, task))
                // whenComplete rather than thenRun: a load that fails still has to let the join
                // line through, in English, rather than swallow it.
                .whenComplete((locale, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        // They left while the query was in flight; onQuit has already run and the
                        // entry this just wrote would otherwise stay for the life of the process.
                        // Unless they are already back: the cache is keyed by UUID, so a callback
                        // from the session before a rejoin would drop the language the new one has
                        // just loaded and leave that player English (finding 105).
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
                }));
    }

    /**
     * Fills in a nametag the moment DisplayTags creates one.
     *
     * <p>This is the only place the composition reliably reaches the tag.
     * {@code NameTagManagerImpl#createNameTag} removes the previous tag and constructs a new one
     * whose constructor applies DisplayTags' own configured lines, then fires this event - so a tag
     * written at join is overwritten, and a handler on any later Bukkit event races the tick that
     * has already rendered the stock format. Firing from inside the creation leaves no ordering to
     * get wrong, and it covers every path that creates a tag: join, a world change, a reload.</p>
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

    // The chat line is not rendered here any more, since 2026-09-09. It is
    // :paper-common's SystemLines, together with join, leave, death and advancement - the hunger
    // games needed the same five and had none of them, in yellow, in one language, with no flag on
    // anybody (finding 149). What was specific to this server is the composition, and that is the
    // one thing SystemLines takes as an argument.
}
