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
 * and how to show the answer. The <em>source</em> is not handed in any more - it was, and the proxy
 * named itself {@code CONSOLE} for every player who typed there. It is read off the user now, in
 * {@link #submit}, which is the one place that can tell a player from a console.</p>
 */
public final class DirectoryUpdateEffects implements UpdateEffects {

    private final UpdateDirectory updates;
    private final Consumer<Runnable> executor;
    private final BiConsumer<String, Throwable> logger;
    private final BiConsumer<Long, NordtalUser> watcher;

    /**
     * @param updates  the directory over this process's own pool
     * @param executor where the waiting part may wait - never the main thread of a Paper server
     *                 and never a JDA gateway thread
     * @param logger   how this process reports a failure to its own log
     * @param watcher  how this process shows the answer coming in - see {@link
     *                 UpdateEffects#watch}
     */
    public DirectoryUpdateEffects(final UpdateDirectory updates,
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
    public UpdateRequest submit(final UpdateKind kind, final NordtalUser user) {
        // Every kind is written due immediately, since 2026-09-08. The countdown used to be set
        // here, which meant it ran before anybody knew whether there was anything to install: the
        // ordinary /update now counted thirty seconds down to every player on the network and then
        // answered "everything is already current". The updater sets it now, on the row it has
        // claimed, once its plan has work in it - see UpdateDirectory#startCountdown.
        return updates.submit(kind, sourceOf(user), requesterOf(user), Duration.ZERO);
    }

    /** Which surface a user is on, as the row records it. */
    static UpdateSource sourceOf(final NordtalUser user) {
        return switch (user.origin()) {
            case DISCORD -> UpdateSource.DISCORD;
            case GAME -> UpdateSource.GAME;
            case CONSOLE -> UpdateSource.CONSOLE;
        };
    }

    /**
     * Who to record: the Discord id, the Minecraft name, or nobody.
     *
     * <p>The id and not the display name for Discord, because the id is what {@code discord_user}
     * is keyed by and what the bot's own button path has always written; a display name can be
     * changed by its owner an hour later. The console is {@code null}, which is what the column
     * has meant by it since V7 - a shell in the container is not a person.</p>
     */
    static String requesterOf(final NordtalUser user) {
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
