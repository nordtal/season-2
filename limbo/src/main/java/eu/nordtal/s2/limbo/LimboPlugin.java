package eu.nordtal.s2.limbo;

import com.zaxxer.hikari.HikariDataSource;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.limbo.LimboProtocol;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.limbo.config.Configs;
import eu.nordtal.s2.limbo.config.DatabaseSpec;
import eu.nordtal.s2.limbo.config.LimboSpec;
import eu.nordtal.s2.limbo.db.LimboPool;
import eu.nordtal.s2.limbo.listener.PresenceListener;
import eu.nordtal.s2.limbo.net.LimboChannel;
import eu.nordtal.s2.limbo.waiting.WaitingRoom;
import eu.nordtal.s2.limbo.world.WaitingWorld;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * The season 2 waiting room. Every login lands here first, whatever the phase, and leaves when the
 * proxy says so.
 *
 * <p>What it is, in full (docs/architecture.md, decided 2026-08-31): <b>nothing</b>. Black, no
 * visible world, no other players and no chat. A title in the player's language says what they are
 * waiting for, and that is the entire interface.
 *
 * <h2>The three things that end a wait</h2>
 * All three are the proxy's to know, and all three arrive as a {@code nordtal:limbo} plugin message
 * ({@link LimboProtocol}): the resource pack is applied, the phase's backend is up, and maintenance
 * is over. This plugin's only outgoing message says "this player has arrived"; it never says where
 * anybody should go, because docs/season-phases.md#routing puts routing in one process and this is
 * not that process.
 *
 * <h2>Why a waiting room has a database connection</h2>
 * Because its one line of text is translated, and docs/i18n.md settles that a plugin reads a
 * player's language from the database at join through {@code :common}'s {@link PlayerLocales}. That
 * is the whole of it - one indexed lookup per join, no writes, ever. The alternative of having the
 * proxy send the language in a plugin message was rejected in that document on its own merits, and
 * docs/architecture.md's older guess that this module needed no persistence lost to it on
 * 2026-09-01.
 *
 * <h2>What happens when something here is wrong</h2>
 * The plugin disables itself and the server keeps running - the standing rule for a Paper plugin
 * with a broken config. For <em>this</em> module that leaves a server which accepts players and
 * shows them the level-name world instead of a black screen, so both failure paths below log what
 * has actually gone wrong rather than only that something did.
 */
public final class LimboPlugin extends JavaPlugin {

    private ConfigHandle<LimboSpec> configHandle;
    private ConfigHandle<DatabaseSpec> databaseHandle;
    private HikariDataSource pool;
    private AccessDirectory access;

    private WaitingRoom room;
    private LimboChannel channel;

    @Override
    public void onEnable() {
        try {
            configHandle = Configs.load(getDataFolder().toPath(), slf4j());
            databaseHandle = Configs.database(getDataFolder().toPath(), slf4j());
        } catch (final ConfigException exception) {
            getLogger().severe("limbo is not starting because its configuration could not be read: "
                    + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        final LimboSpec config = configHandle.get();

        final WaitingWorld world = WaitingWorld.loadOrCreate(this, config);
        if (world == null) {
            getLogger().severe("limbo could not create or load its waiting world '" + config.worldName()
                    + "'. Without it every login would be spawned into this server's own level-name "
                    + "world, which is the one thing a waiting room must not show - disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        pool = LimboPool.open(databaseHandle.get());
        access = AccessDirectory.using(pool);
        final PlayerLocales locales = new PlayerLocales(access::locale);

        final Messages messages = Messages.load(getClass().getClassLoader(), "messages/limbo",
                Locale.ENGLISH, Locale.GERMAN);

        room = new WaitingRoom(this, config, messages, locales, world);
        room.start();

        channel = new LimboChannel(this, room);
        channel.register();

        getServer().getPluginManager()
                .registerEvents(new PresenceListener(this, world, room, channel, locales), this);

        getLogger().info("limbo enabled - waiting world '" + config.worldName() + "', title refreshed "
                + "every " + config.titleRefreshSeconds() + "s, speaking " + LimboProtocol.CHANNEL);
    }

    @Override
    public void onDisable() {
        if (channel != null) {
            channel.unregister();
        }
        if (room != null) {
            room.stop();
        }
        // access.close() is a no-op - AccessDirectory.using(...) never owns the pool it is handed -
        // so this plugin closes the pool it built itself.
        if (pool != null) {
            pool.close();
        }
        getLogger().info("limbo disabled");
    }

    // JavaPlugin#getLogger() returns java.util.logging.Logger; jcore's ConfigLoader wants an
    // slf4j.Logger, matching every other module's Configs class - this is the one adapter point.
    private org.slf4j.Logger slf4j() {
        return org.slf4j.LoggerFactory.getLogger(LimboPlugin.class);
    }
}
