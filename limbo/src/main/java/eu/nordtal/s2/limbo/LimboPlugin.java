package eu.nordtal.s2.limbo;

import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.limbo.LimboCommands;
import eu.nordtal.s2.commands.limbo.LimboEffects;
import eu.nordtal.s2.commands.remote.CommandRequests;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.limbo.command.BukkitLimboEffects;
import eu.nordtal.s2.limbo.command.LimboCommand;
import eu.nordtal.s2.limbo.config.LimboCheck;
import eu.nordtal.s2.limbo.config.LimboSpec;
import eu.nordtal.s2.limbo.listener.PresenceListener;
import eu.nordtal.s2.limbo.net.LimboChannel;
import eu.nordtal.s2.limbo.waiting.WaitingRoom;
import eu.nordtal.s2.limbo.world.WaitingWorld;
import eu.nordtal.s2.limboprotocol.LimboProtocol;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.papercommon.command.PaperCommandInbox;
import eu.nordtal.s2.papercommon.plugin.NordtalPlugin;
import eu.nordtal.s2.settings.Setting;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The season 2 waiting room: every login lands here first and leaves when the proxy says so.
 *
 * The proxy ends every wait on {@code nordtal:limbo} ({@link LimboProtocol}); this only reports arrival.
 */
public final class LimboPlugin extends NordtalPlugin {

    private Setting<LimboSpec> config;
    private WaitingWorld world;
    private WaitingRoom room;
    private PresenceListener presence;
    private @Nullable LimboChannel channel;

    /** The thread a command sent to another process waits on. Shut down before the pool. */
    private @Nullable ScheduledExecutorService commandWaiter;

    @Override
    protected String settingsPrefix() {
        return "NORDTAL_LIMBO";
    }

    @Override
    protected List<String> bundles() {
        return List.of("messages/commands", "messages/limbo");
    }

    @Override
    protected void prepare() {
        config = setting("config", LimboSpec.class, LimboCheck::check);
        final WaitingWorld loaded = WaitingWorld.loadOrCreate(this, config.get());
        if (loaded == null) {
            throw fatal("limbo could not create or load its waiting world '"
                    + config.get().worldName()
                    + "'. Without it every login would be spawned into this server's own level-name world,"
                    + " which is the one thing a waiting room must not show.");
        }
        world = loaded;
    }

    @Override
    protected void enable() {
        room = new WaitingRoom(this, config.get(), messages(), locales(), world);
        room.start();
        final LimboChannel speaking = new LimboChannel(this, room);
        speaking.register();
        channel = speaking;
        presence = new PresenceListener(this, world, room, speaking, locales(), messages(), identities());
        listen(presence);
        wireCommands();
        getLogger()
                .info("waiting world '" + config.get().worldName() + "', title refreshed every "
                        + config.get().titleRefreshSeconds() + "s, speaking " + LimboProtocol.CHANNEL);
    }

    @Override
    protected void languageKnown(final Player player) {
        room.redraw(player);
        presence.sendTabList(player);
    }

    @Override
    protected void disable() {
        final LimboChannel speaking = channel;
        if (speaking != null) {
            quietly("channel.unregister", speaking::unregister);
        }
        if (room != null) {
            quietly("room.stop", room::stop);
        }
        final ScheduledExecutorService waiter = commandWaiter;
        if (waiter != null) {
            // Before the pool: a wait in flight reads the request row through it.
            quietly("commandWaiter.shutdownNow", waiter::shutdownNow);
        }
    }

    /** The command layer: the outbox to other processes and the inbox from them. */
    private void wireCommands() {
        final Messages shared = PaperCommandInbox.sharedBundle(this);
        final LimboEffects chatEffects =
                new BukkitLimboEffects(this, BukkitLimboEffects.async(this), messages(), shared);
        final ScheduledExecutorService waiter = Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, getName() + "-command-waiter");
            thread.setDaemon(true);
            return thread;
        });
        commandWaiter = waiter;
        final CommandRequests requests = CommandRequests.over(pool(), clock());
        final Outbox outbox = new Outbox(
                requests, waiter, (message, failure) -> getLogger().log(Level.WARNING, message, failure), clock());
        final PaperCommandInbox inbox = new PaperCommandInbox(this, Target.LIMBO, requests, access(), shared);
        // A scheduled effect would settle the request row before the command produced its answer; register refuses one.
        LimboCommands.all()
                .forEach(command ->
                        inbox.register(command, new BukkitLimboEffects(this, Runnable::run, messages(), shared)));
        inbox.listen(hub(), this);

        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> LimboCommand.build(
                                        this,
                                        messages(),
                                        locales(),
                                        id -> adminWatch().isAdmin(id),
                                        access()::linkedDiscordAccount,
                                        outbox,
                                        chatEffects,
                                        this::colours)
                                .forEach(node -> event.registrar().register(node)));
    }
}
