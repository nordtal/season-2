package eu.nordtal.season.papercommon.player;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.access.AdminOperators;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/** The beginning and end of a session: identity at pre-login, operator at join, everything dropped at quit. */
public final class Presence implements Listener {

    /** English only by necessity: without the identity there is no language to read. */
    static final DocumentSpec<DocumentSpec.None> DATABASE_UNREACHABLE =
            DocumentSpec.game("paper-common", "database-unreachable", "Login", DocumentSpec.Shape.KICK_SCREEN);

    private final Plugin plugin;
    private final Identities identities;
    private final AdminOperators operators;
    private final MessageRenderer renderer;
    private final boolean refusesWithoutIdentity;
    private final Consumer<Player> languageKnown;
    private final Logger logger;

    public Presence(
            final Plugin plugin,
            final Identities identities,
            final AdminOperators operators,
            final MessageRenderer renderer,
            final boolean refusesWithoutIdentity,
            final Consumer<Player> languageKnown,
            final Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.operators = Objects.requireNonNull(operators, "operators");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.refusesWithoutIdentity = refusesWithoutIdentity;
        this.languageKnown = Objects.requireNonNull(languageKnown, "languageKnown");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Reads the identity at pre-login. */
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
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    renderer.format(Locale.ENGLISH, Sent.of(DATABASE_UNREACHABLE)));
        }
    }

    /** Lets an admin join a full server. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onFullCheck(final PlayerServerFullCheckEvent event) {
        final UUID id = event.getPlayerProfile().getId();
        if (!event.isAllowed() && id != null && identities.isAdmin(PlayerId.of(id))) {
            event.allow(true);
        }
    }

    /** Grants operator from the held flag and calls {@code languageKnown} once every join handler ran. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        operators.onJoin(player.getUniqueId(), identities.of(player).admin());
        PaperScheduler.of(plugin).onMain(() -> {
            if (player.isOnline()) {
                languageKnown.accept(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        operators.onQuit(id);
        identities.forget(PlayerId.of(id));
    }
}
