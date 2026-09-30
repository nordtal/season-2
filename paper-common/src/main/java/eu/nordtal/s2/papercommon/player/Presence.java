package eu.nordtal.s2.papercommon.player;

import static eu.nordtal.s2.papercommon.PaperCommonMessages.MESSAGES;

import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.database.access.AdminOperators;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * A session's beginning and end: the identity read at pre-login, operator and language at join, all of it at quit.
 *
 * An admin passes a full server; the language lands off the main thread and is announced to the plugin once known.
 */
public final class Presence implements Listener {

    private final Plugin plugin;
    private final Identities identities;
    private final PlayerLocales locales;
    private final AdminOperators operators;
    private final Messages messages;
    private final boolean refusesWithoutIdentity;
    private final Consumer<Player> languageKnown;
    private final Logger logger;

    /**
     * @param refusesWithoutIdentity whether a login whose identity cannot be read is refused rather than let in
     * @param languageKnown          runs on the main thread once a joined player's language is held
     */
    public Presence(
            final Plugin plugin,
            final Identities identities,
            final PlayerLocales locales,
            final AdminOperators operators,
            final Messages messages,
            final boolean refusesWithoutIdentity,
            final Consumer<Player> languageKnown,
            final Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.locales = Objects.requireNonNull(locales, "locales");
        this.operators = Objects.requireNonNull(operators, "operators");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.refusesWithoutIdentity = refusesWithoutIdentity;
        this.languageKnown = Objects.requireNonNull(languageKnown, "languageKnown");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Reads the identity on the one thread this server may wait on a database from. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        final PlayerId player = PlayerId.of(event.getUniqueId());
        try {
            identities.load(player);
        } catch (final RuntimeException failure) {
            if (!refusesWithoutIdentity) {
                logger.warn("could not read who {} is, so they play as nobody in particular", player, failure);
                identities.holdUnknown(player);
                return;
            }
            logger.error("refusing {}'s login because the database is unreachable", player, failure);
            // English: without the identity there is no language to read, which is itself what is broken.
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    MessageRenderer.of(messages)
                            .format(Locale.ENGLISH, MESSAGES.login().databaseUnreachable()));
        }
    }

    /** Lets an admin onto a server Paper has decided is full, and past nothing but the cap. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onFullCheck(final PlayerServerFullCheckEvent event) {
        final UUID id = event.getPlayerProfile().getId();
        if (!event.isAllowed() && id != null && identities.isAdmin(PlayerId.of(id))) {
            event.allow(true);
        }
    }

    /** Grants operator from the held flag and loads the language off the main thread. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        operators.onJoin(player.getUniqueId(), identities.of(player).admin());
        final var _ = locales.joinAsync(
                        player.getUniqueId(), task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task))
                .whenComplete((locale, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        languageKnown.accept(player);
                    } else if (Bukkit.getPlayer(player.getUniqueId()) == null) {
                        // Quit already ran and this answer would otherwise stay for the life of the process.
                        locales.quit(player.getUniqueId());
                    }
                }));
    }

    /** Drops everything the session held. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        operators.onQuit(id);
        locales.quit(id);
        identities.forget(PlayerId.of(id));
    }
}
