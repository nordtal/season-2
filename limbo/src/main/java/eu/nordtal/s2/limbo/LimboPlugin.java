package eu.nordtal.s2.limbo;

import com.zaxxer.hikari.HikariDataSource;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.limbo.LimboProtocol;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.limbo.command.LimboCommand;
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
    private org.bukkit.scheduler.BukkitTask heartbeat;

    @Override
    public void onEnable() {
        try {
            configHandle = Configs.load(getDataFolder().toPath(), slf4j());
            databaseHandle = Configs.database(getDataFolder().toPath(), slf4j());
        } catch (final ConfigException exception) {
            severe("limbo is not starting because its configuration could not be read: "
                    + exception.getMessage());
            return;
        }

        final LimboSpec config = configHandle.get();

        final WaitingWorld world = WaitingWorld.loadOrCreate(this, config);
        if (world == null) {
            severe("limbo could not create or load its waiting world '" + config.worldName()
                    + "'. Without it every login would be spawned into this server's own level-name "
                    + "world, which is the one thing a waiting room must not show. Stopping the "
                    + "server rather than accepting logins onto it.");
            return;
        }

        pool = LimboPool.open(databaseHandle.get());
        access = AccessDirectory.using(pool);
        final PlayerLocales locales = new PlayerLocales(access::locale);

        final Messages messages = Messages.load(getClass().getClassLoader(), "messages/limbo",
                getDataFolder().toPath().resolve("messages"), Locale.ENGLISH, Locale.GERMAN);
        messages.unknownOverrideKeys().forEach(key -> getLogger().warning(
                "the message override names " + key + ", which no bundle declares - it is stored"
                        + " and never used; check the spelling"));

        room = new WaitingRoom(this, config, messages, locales, world);
        room.start();

        channel = new LimboChannel(this, room);
        channel.register();

        getServer().getPluginManager()
                .registerEvents(new PresenceListener(this, world, room, channel, locales, messages), this);

        getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                event -> event.registrar().register(new LimboCommand(this, messages).build()));

        startHeartbeat();

        getLogger().info("limbo enabled - waiting world '" + config.worldName() + "', title refreshed "
                + "every " + config.titleRefreshSeconds() + "s, speaking " + LimboProtocol.CHANNEL);
    }

    /**
     * The container readiness marker - see {@link Readiness}, and note where this call sits.
     *
     * <p>It is the <b>last</b> thing {@code onEnable} does, because that is the entire rule: every
     * refusal above returns before reaching it, so a marker on disk means this plugin got all the
     * way through. Written from Bukkit's async scheduler, which is also deliberate - a repeating
     * async task is re-queued by the main-thread heartbeat, so a server frozen mid-tick stops
     * beating and the container goes stale rather than staying green on an open port.</p>
     */
    private void startHeartbeat() {
        final Readiness readiness = Readiness.onDefaultPath(getLogger()::warning);
        final long ticks = Readiness.BEAT.toSeconds() * 20L;
        heartbeat = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, readiness::refresh, 0L, ticks);
    }

    @Override
    public void onDisable() {
        // Stops the beat, so a server that is going down stops claiming to be up. The marker is
        // deliberately not deleted: going stale is the signal, and it costs nothing here.
        if (heartbeat != null) {
            heartbeat.cancel();
        }
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

    /**
     * The plugin cannot run, so neither can this server.
     *
     * <h2>Why it takes the server with it, since 2026-09-02</h2>
     * Logging and disabling alone is the convention this repository states for
     * {@code papermc-display-tags} - a plugin on somebody else's server, where "the plugin goes
     * down, the server keeps running" is plainly right. On our own dedicated backends it is plainly
     * wrong, and the first deployment showed what it costs: {@code smp}'s config threw on every
     * start, the plugin disabled itself, Paper carried on, and the container stayed up and green
     * with no season on it.
     *
     * <p>No check outside the JVM could tell that state from a healthy one when this rule was
     * written - every jar is in the folder, so the entrypoint's guard passes, and the port was open,
     * so the port check passed. Here is the only place the difference is knowable.</p>
     *
     * <p>Since 2026-09-04 the container does report it, because {@link #startHeartbeat()} is below
     * every refusal and its marker is never written on this path. That does not soften the rule: an
     * unhealthy container is a red square in Arcane and nothing else - Docker restarts nothing on
     * health alone - so without the shutdown the server would still be up, still accepting players,
     * and merely honest about it.</p>
     */
    private void severe(final String message) {
        getLogger().severe(message);
        getServer().getPluginManager().disablePlugin(this);
        getServer().shutdown();
    }

}
