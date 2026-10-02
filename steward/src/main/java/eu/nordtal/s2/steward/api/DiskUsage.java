package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.internalapi.agent.AgentClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * How much of the disk one service's volume takes, for the Disk field on its page; steward-agent runs the {@code du}.
 *
 * Only the servers' volumes get a number; after the first, each ask gets the last one and a refresh.
 */
final class DiskUsage {

    static final Duration TTL = Duration.ofMinutes(5);

    /** One measurement: the bytes, or none when {@code du} could not answer, and when it was taken. */
    record Measured(OptionalLong bytes, Instant at) {}

    private final Function<String, OptionalLong> measure;
    private final Executor background;
    private final Supplier<Instant> clock;
    private final Map<String, Refreshed<Measured>> cache = new ConcurrentHashMap<>();

    DiskUsage(final AgentClient agent, final Executor background, final Supplier<Instant> clock) {
        this(service -> sizeOf(agent, service), background, clock);
    }

    DiskUsage(final Function<String, OptionalLong> measure, final Executor background, final Supplier<Instant> clock) {
        this.measure = measure;
        this.background = background;
        this.clock = clock;
    }

    /** The last measurement for {@code service}, a server, or empty when none could be taken. */
    Optional<Measured> of(final String service) {
        final Measured measured = cache.computeIfAbsent(
                        service,
                        name -> new Refreshed<>(
                                () -> new Measured(measure.apply(name), clock.get()), TTL, background, clock))
                .get();
        return measured.bytes().isPresent() ? Optional.of(measured) : Optional.empty();
    }

    /** The agent's answer, and none when it has no such volume or did not answer. */
    private static OptionalLong sizeOf(final AgentClient agent, final String service) {
        try {
            return agent.disk(service);
        } catch (final InternalClient.Failure failed) {
            return OptionalLong.empty();
        }
    }
}
