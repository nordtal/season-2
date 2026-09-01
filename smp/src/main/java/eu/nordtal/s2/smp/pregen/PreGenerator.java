package eu.nordtal.s2.smp.pregen;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.popcraft.chunky.api.ChunkyAPI;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The one thing this plugin asks Chunky to do: fill a world out to its border, and say when it is
 * finished.
 *
 * <p>Chunky is a <b>required</b> plugin (paper-plugin.yml). Writing our own throttled generator was
 * the alternative and was rejected on 2026-09-01: Chunky already solves exactly this, its throttle
 * is the one operators already know how to turn down, and an in-house copy would be a second thing
 * to re-test on every Minecraft update. What Chunky does not do is decide <em>when</em> - that is
 * the farm world's schedule, and it lives next door.
 *
 * <p>The API arrives through Bukkit's {@code ServicesManager} rather than by casting the plugin
 * instance, which is why the module takes {@code chunky-common} as {@code compileOnly} and never
 * shades it: a bundled copy would be a second set of classes for the same interface and the lookup
 * would hand back something this code could not cast.
 */
public final class PreGenerator {

    private final Plugin plugin;
    private final String pattern;
    private final ChunkyAPI chunky;

    /** One callback per world with a run in flight, fired on the main thread when it completes. */
    private final Map<String, Runnable> waiting = new ConcurrentHashMap<>();

    private PreGenerator(final Plugin plugin, final String pattern, final ChunkyAPI chunky) {
        this.plugin = plugin;
        this.pattern = pattern;
        this.chunky = chunky;
        chunky.onGenerationComplete(event -> {
            final Runnable done = waiting.remove(event.world());
            if (done != null) {
                // Chunky fires this from its own thread. Everything downstream touches worlds.
                Bukkit.getScheduler().runTask(plugin, done);
            }
        });
    }

    /**
     * Looks Chunky's service up.
     *
     * @return empty when Chunky is not there, which {@code paper-plugin.yml} should already have
     *         prevented - checked anyway, because "required" is a promise about load order and not
     *         about a plugin that failed its own enable
     */
    public static java.util.Optional<PreGenerator> open(final Plugin plugin, final String pattern) {
        final var registration = Bukkit.getServicesManager().getRegistration(ChunkyAPI.class);
        if (registration == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new PreGenerator(plugin, pattern, registration.getProvider()));
    }

    public boolean isRunning(final String world) {
        return chunky.isRunning(world);
    }

    /**
     * Starts filling {@code world} out to {@code diameter}, centred on 0/0.
     *
     * <p>Square, not circle: Minecraft's world border is a square, and generating a circle inside it
     * leaves four unbuilt corners that a player can walk into.
     *
     * @param onComplete run on the main thread when Chunky reports this world finished
     * @return false when Chunky refused the task, which it does when one is already running there
     */
    public boolean start(final String world, final int diameter, final Runnable onComplete) {
        final double radius = diameter / 2.0;
        waiting.put(world, onComplete);
        final boolean started = chunky.startTask(world, "square", 0, 0, radius, radius, pattern);
        if (!started) {
            waiting.remove(world);
            plugin.getLogger().warning("Chunky refused a pre-generation task for '" + world
                    + "' - one is probably already running there");
        }
        return started;
    }

    /** Stops a run and forgets its callback, so a cancelled run never completes a reset. */
    public void cancel(final String world) {
        waiting.remove(world);
        chunky.cancelTask(world);
    }
}
