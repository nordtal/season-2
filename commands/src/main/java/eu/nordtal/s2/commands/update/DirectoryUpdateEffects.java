package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * {@code /update}'s effects, once, for all five processes.
 *
 * One implementation, so no two processes can differ; the source is read off the user in {@link #submit}.
 */
public final class DirectoryUpdateEffects implements UpdateEffects {

    private final UpdateDirectory updates;
    private final Consumer<Runnable> executor;
    private final BiConsumer<String, Throwable> logger;
    private final BiConsumer<Long, NordtalUser> watcher;

    /**
     * @param updates  the directory over this process's own pool
     * @param executor where the waiting part may wait, never a Paper main thread or a JDA gateway thread
     * @param logger   how this process reports a failure to its own log
     * @param watcher  how this process shows the answer coming in, see {@link UpdateEffects#watch}
     */
    public DirectoryUpdateEffects(
            final UpdateDirectory updates,
            final Consumer<Runnable> executor,
            final BiConsumer<String, Throwable> logger,
            final BiConsumer<Long, NordtalUser> watcher) {
        this.updates = updates;
        this.executor = executor;
        this.logger = logger;
        this.watcher = watcher;
    }

    @Override
    public void watch(final long id, final NordtalUser user) {
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
    public UpdateRequest submit(final UpdateKind kind, final NordtalUser user, final java.util.List<String> services) {
        // Every kind is written due immediately: a countdown must not run before anybody knows there is work.
        return updates.submit(kind, sourceOf(user), requesterOf(user), Duration.ZERO, services);
    }

    /** Returns which surface a user is on, as the row records it. */
    static UpdateSource sourceOf(final NordtalUser user) {
        return switch (user.origin()) {
            case DISCORD -> UpdateSource.DISCORD;
            case GAME -> UpdateSource.GAME;
            case CONSOLE -> UpdateSource.CONSOLE;
        };
    }

    /**
     * Returns who to record: the Discord id, the Minecraft name, or {@code null} for the console.
     *
     * The Discord id rather than the display name, which its owner can change.
     */
    static @Nullable String requesterOf(final NordtalUser user) {
        return switch (user.origin()) {
            case DISCORD -> user.discordId().orElseGet(user::name);
            case GAME -> user.name();
            case CONSOLE -> null;
        };
    }

    @Override
    public Optional<UpdateRequest> find(final long id) {
        return updates.find(id);
    }

    @Override
    public Optional<UpdateRequest> cancel(final String reason) {
        return updates.cancelCountdown(reason);
    }
}
