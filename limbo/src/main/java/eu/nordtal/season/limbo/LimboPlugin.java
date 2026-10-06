package eu.nordtal.season.limbo;

import eu.nordtal.season.limbo.config.LimboCheck;
import eu.nordtal.season.limbo.config.LimboSpec;
import eu.nordtal.season.limbo.net.LimboChannel;
import eu.nordtal.season.limbo.presence.WaitingRoomRules;
import eu.nordtal.season.limbo.waiting.WaitingRoom;
import eu.nordtal.season.limbo.world.WaitingWorld;
import eu.nordtal.season.limboprotocol.LimboProtocol;
import eu.nordtal.season.papercommon.plugin.NordtalPlugin;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.Setting;
import java.util.List;
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
    private WaitingRoomRules presence;
    private @Nullable LimboChannel channel;

    @Override
    protected String settingsPrefix() {
        return "NORDTAL_LIMBO";
    }

    @Override
    protected String commandRoot() {
        return "limbo";
    }

    @Override
    protected List<String> bundles() {
        return List.of("messages/limbo");
    }

    @Override
    protected void prepare() {
        config = setting(Group.of("config", LimboSpec.class).checkedBy(LimboCheck::check));
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
        room = new WaitingRoom(this, config.get(), renderer(), identities(), world);
        room.start();
        final LimboChannel speaking = new LimboChannel(this, room);
        speaking.register();
        channel = speaking;
        presence = new WaitingRoomRules(this, world, room, speaking, renderer(), identities());
        listen(presence);
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
    }
}
