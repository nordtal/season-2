package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * {@code /update}'s effects, once, for all five processes.
 *
 * <h2>Why one implementation and not one per process</h2>
 * Every other effect interface in this module is implemented per process, because every other one
 * touches something only that process has - a world, a guild, a proxy's player list. This one
 * touches {@link UpdateDirectory}, which is a {@code :common} type over a {@code javax.sql
 * .DataSource}, and all five processes already hold that pool. Writing it three times would be
 * three chances for the countdown length or the source label to differ, which is the failure this
 * module exists to prevent.
 *
 * <p>What genuinely differs per process is handed in: where to run the waiting part, how to log,
 * and which {@link UpdateSource} to record. The last one is not decoration - it is what a person
 * reading {@code update_request} weeks later uses to tell "somebody clicked this in Discord" from
 * "somebody was standing on the SMP".</p>
 */
public final class DirectoryUpdateEffects implements UpdateEffects {

    private final UpdateDirectory updates;
    private final UpdateSource source;
    private final Consumer<Runnable> executor;
    private final BiConsumer<String, Throwable> logger;
    private final BiConsumer<Long, eu.nordtal.s2.commands.NordtalUser> watcher;

    /**
     * @param updates  the directory over this process's own pool
     * @param source   which surface this process is, recorded on every row it writes
     * @param executor where the waiting part may wait - never the main thread of a Paper server
     *                 and never a JDA gateway thread
     * @param logger   how this process reports a failure to its own log
     * @param watcher  how this process shows the answer coming in - see {@link
     *                 UpdateEffects#watch}. {@code (id, user) -> { }} for a surface with nothing to
     *                 draw
     */
    public DirectoryUpdateEffects(final UpdateDirectory updates, final UpdateSource source,
                                  final Consumer<Runnable> executor,
                                  final BiConsumer<String, Throwable> logger,
                                  final BiConsumer<Long, eu.nordtal.s2.commands.NordtalUser> watcher) {
        this.updates = updates;
        this.source = source;
        this.executor = executor;
        this.logger = logger;
        this.watcher = watcher;
    }

    @Override
    public void watch(final long id, final eu.nordtal.s2.commands.NordtalUser user) {
        watcher.accept(id, user);
    }

    @Override
    public void async(final Runnable work) {
        executor.accept(work);
    }

    @Override
    public void warn(final String what, final Throwable failure) {
        logger.accept(what, failure);
    }

    @Override
    public UpdateRequest submit(final UpdateKind kind, final String requester) {
        // The countdown belongs to the kind and not to the caller. Every surface asked for it
        // separately before this class existed, and the Discord one asked for zero on an update -
        // which would have taken four servers away with no warning shown anywhere.
        final Duration delay = kind.stopsServers()
                ? UpdateDirectory.UPDATE_COUNTDOWN : Duration.ZERO;
        return updates.submit(kind, source, requester, delay);
    }

    @Override
    public Optional<UpdateRequest> find(final long id) {
        return updates.find(id);
    }

    @Override
    public Optional<UpdateRequest> cancel(final String reason) {
        return updates.cancelPendingRestart(reason);
    }
}
