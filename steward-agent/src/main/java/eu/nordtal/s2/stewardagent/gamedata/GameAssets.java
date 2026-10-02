package eu.nordtal.s2.stewardagent.gamedata;

import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.game.GameCatalogue;
import eu.nordtal.s2.database.game.GameDataStore;
import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.database.notify.Doorbell;
import eu.nordtal.s2.database.notify.SignalHub;
import eu.nordtal.s2.stewardagent.source.Downloads;
import eu.nordtal.s2.stewardagent.source.SourceHttp;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Draws the icons of every Minecraft version a server runs and none are drawn for, from Mojang's client jar.
 *
 * Only with the installer's consent: without it, pickers show the servers' English names and no icons.
 */
@Slf4j
public final class GameAssets implements AutoCloseable {

    /** How long a version whose jar could not be fetched or drawn waits before the next try. */
    static final Duration RETRY = Duration.ofHours(1);

    private final GameDataStore store;
    private final ClientJars jars;
    private final BooleanSupplier consented;
    private final Clock clock;
    private final Doorbell doorbell = new Doorbell();
    private final Map<String, Instant> failed = new HashMap<>();
    private final Thread thread;
    private volatile boolean running = true;

    GameAssets(final GameDataStore store, final ClientJars jars, final BooleanSupplier consented, final Clock clock) {
        this.store = store;
        this.jars = jars;
        this.consented = consented;
        this.clock = clock;
        this.thread = Thread.ofPlatform().name("game-assets").daemon().unstarted(this::serve);
    }

    /** Over Mojang's servers, with temporary jars under {@code scratch} and the timeouts of a run. */
    public static GameAssets fromMojang(
            final GameDataStore store,
            final Path scratch,
            final Duration httpTimeout,
            final Duration downloadTimeout,
            final BooleanSupplier consented,
            final Clock clock) {
        final Waiting waiting = Waiting.on(clock);
        final ClientJars jars = new MojangClient(
                SourceHttp.over(SourceHttp.client(httpTimeout, "", waiting)),
                new Downloads(SourceHttp.client(downloadTimeout, "", waiting)),
                scratch);
        return new GameAssets(store, jars, consented, clock);
    }

    /** Looks again on every signal and reconciliation of {@code signals}, as a server's export rings it. */
    public void listen(final SignalHub signals) {
        signals.on(Channel.GAME_DATA, "the game data", doorbell::ring);
    }

    public void start() {
        thread.start();
    }

    private void serve() {
        while (running) {
            try {
                drawMissing();
                doorbell.await(SignalHub.RECONCILIATION);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (final RuntimeException failure) {
                log.warn("Drawing the game's icons failed; it tries again at the next signal", failure);
                try {
                    doorbell.await(SignalHub.RECONCILIATION);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Draws each version still without icons, once consent is given; a failed one rests for {@link #RETRY}. */
    void drawMissing() {
        if (!consented.getAsBoolean()) {
            return;
        }
        final List<String> missing = store.versionsWithoutIcons();
        if (missing.isEmpty()) {
            return;
        }
        final Map<String, GameCatalogue> catalogues = store.catalogues();
        for (final String version : missing) {
            final Instant last = failed.get(version);
            if (last != null && clock.instant().isBefore(last.plus(RETRY))) {
                continue;
            }
            try (ClientJars.Jar jar = jars.open(version)) {
                final GameDataStore.Icons icons = IconSheet.draw(jar, items(catalogues, version));
                store.storeIcons(version, icons);
                failed.remove(version);
                log.info("Drew {} icons for Minecraft {}", icons.index().slots().size(), version);
            } catch (final IOException | RuntimeException unavailable) {
                failed.put(version, clock.instant());
                log.warn("Could not draw the icons of Minecraft {}: {}", version, unavailable.getMessage());
            }
        }
    }

    /** Every item any server of {@code version} names, sorted, so the sheet is the same each time. */
    private static List<String> items(final Map<String, GameCatalogue> catalogues, final String version) {
        final TreeSet<String> items = new TreeSet<>();
        for (final GameCatalogue catalogue : catalogues.values()) {
            if (catalogue.minecraftVersion().equals(version)) {
                for (final GameCatalogue.Entry entry : catalogue.registries().getOrDefault("item", List.of())) {
                    items.add(entry.id());
                }
            }
        }
        return List.copyOf(items);
    }

    @Override
    public void close() {
        running = false;
        doorbell.ring();
    }
}
