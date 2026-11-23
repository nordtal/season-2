package eu.nordtal.season.smp.grave;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;

/**
 * Writes the changes the main thread makes to graves, one at a time and the newest per grave.
 * A newer change replaces one still waiting, so a write that runs late never overwrites a newer remainder.
 */
final class GraveWrites {

    private sealed interface Write {}

    private static final class Remainder implements Write {
        private final byte[] contents;

        Remainder(final byte[] contents) {
            this.contents = contents.clone();
        }
    }

    private record Looted(@Nullable String looterId, Runnable onMarked) implements Write {}

    private final GraveDao dao;
    private final Executor background;
    private final Executor main;

    /** The newest change of each grave that no write has taken yet. Guarded by itself. */
    private final Map<UUID, Write> pending = new LinkedHashMap<>();

    /** Held while a write talks to the database, so the writes of all graves run one at a time. */
    private final Object writing = new Object();

    /**
     * @param background runs the database writes
     * @param main       runs a loot mark's follow-up
     */
    GraveWrites(final GraveDao dao, final Executor background, final Executor main) {
        this.dao = dao;
        this.background = background;
        this.main = main;
    }

    /** Stores what is left in a grave. */
    void remainder(final UUID graveId, final byte[] contents) {
        submit(graveId, new Remainder(contents));
    }

    /** Marks a grave as looted and, if no one did so before, hands {@code onMarked} to the main thread. */
    void looted(final UUID graveId, final @Nullable String looterId, final Runnable onMarked) {
        submit(graveId, new Looted(looterId, onMarked));
    }

    /**
     * Writes every waiting change on the calling thread and runs the follow-ups there.
     * Call it on the main thread when the plugin stops, since the server then drops its queued tasks.
     */
    void flush() {
        final List<UUID> graves;
        synchronized (pending) {
            graves = List.copyOf(pending.keySet());
        }
        graves.forEach(graveId -> write(graveId, Runnable::run));
    }

    private void submit(final UUID graveId, final Write write) {
        synchronized (pending) {
            pending.put(graveId, write);
        }
        try {
            background.execute(() -> write(graveId, main));
        } catch (final RejectedExecutionException stopping) {
            // The change stays pending, and flush writes it.
        }
    }

    private void write(final UUID graveId, final Executor followUp) {
        synchronized (writing) {
            final Write write;
            synchronized (pending) {
                write = pending.remove(graveId);
            }
            switch (write) {
                case null -> {}
                case Remainder remainder -> dao.updateGraveContents(graveId, remainder.contents);
                case Looted looted -> {
                    if (dao.markGraveLooted(graveId, looted.looterId()).isPresent()) {
                        followUp.execute(looted.onMarked());
                    }
                }
            }
        }
    }
}
