package eu.nordtal.season.proxy;

import eu.nordtal.season.common.time.Scheduler;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Keeps a player whose re-route failed on the network, in a waiting room.
 * It tries the rooms in turn, {@link #INTERVAL} apart and at most {@link #TRIES} times, before the caller disconnects.
 */
final class WaitingRoomRetry {

    static final Duration INTERVAL = Duration.ofSeconds(2);
    static final int TRIES = 5;

    private final Scheduler scheduler;
    private final NetworkServers servers;
    private final Predicate<String> registered;
    private final Logger logger;

    WaitingRoomRetry(
            final Scheduler scheduler,
            final NetworkServers servers,
            final Predicate<String> registered,
            final Logger logger) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.registered = Objects.requireNonNull(registered, "registered");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Starts the tries for a player whose move onto {@code failed} did not land.
     *
     * @param connect moves the player into the room and completes with whether the player is there, or gone
     * @param giveUp runs once when no try is left, no room is registered or the scheduler stopped
     */
    void start(
            final String player,
            final String failed,
            final Function<String, CompletableFuture<Boolean>> connect,
            final Runnable giveUp) {
        schedule(player, failed, 1, connect, giveUp);
    }

    /**
     * Returns the room for the next try after a failed move onto {@code failed}, {@code null} without one.
     * That is the other room when it is registered, otherwise the same one; a game server tries {@code limbo} first.
     */
    static @Nullable String nextRoom(
            final String failed, final NetworkServers servers, final Predicate<String> registered) {
        final String limbo = servers.limbo();
        final String standby = servers.limboStandby();
        final List<String> order = failed.equals(limbo) ? List.of(standby, limbo) : List.of(limbo, standby);
        for (final String room : order) {
            if (registered.test(room)) {
                return room;
            }
        }
        return null;
    }

    private void schedule(
            final String player,
            final String failed,
            final int attempt,
            final Function<String, CompletableFuture<Boolean>> connect,
            final Runnable giveUp) {
        try {
            final var _ = scheduler.after(INTERVAL, () -> attempt(player, failed, attempt, connect, giveUp));
        } catch (final RejectedExecutionException stopped) {
            logger.warn("waiting room retry not scheduled: player={}", player, stopped);
            giveUp.run();
        }
    }

    private void attempt(
            final String player,
            final String failed,
            final int attempt,
            final Function<String, CompletableFuture<Boolean>> connect,
            final Runnable giveUp) {
        final String room = nextRoom(failed, servers, registered);
        if (room == null) {
            logger.warn("no waiting room registered for the retry: player={}", player);
            giveUp.run();
            return;
        }
        logger.info(
                "re-route into waiting room tried again: player={} waiting_room={} attempt={} of={}",
                player,
                room,
                attempt,
                TRIES);
        CompletableFuture<Boolean> move;
        try {
            move = connect.apply(room);
        } catch (final RuntimeException failure) {
            move = CompletableFuture.failedFuture(failure);
        }
        final var _ = move.whenComplete((landed, failure) -> {
            if (failure == null && Boolean.TRUE.equals(landed)) {
                return;
            }
            if (attempt >= TRIES) {
                logger.warn(
                        "re-route into waiting room given up: player={} waiting_room={} tries={}", player, room, TRIES);
                giveUp.run();
                return;
            }
            schedule(player, room, attempt + 1, connect, giveUp);
        });
    }
}
