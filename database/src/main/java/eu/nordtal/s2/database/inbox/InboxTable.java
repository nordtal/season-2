package eu.nordtal.s2.database.inbox;

import eu.nordtal.s2.database.notify.Channel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One consumer's inbox table: its name, the channel every change is announced on, and the kinds it takes.
 * The kinds are the records a sealed payload type permits, each stored under its name in upper snake case.
 */
public final class InboxTable<P> {

    private final String name;
    private final Channel channel;
    private final Class<P> payloads;
    private final Map<String, Class<? extends P>> kinds;

    private InboxTable(final String name, final Channel channel, final Class<P> payloads) {
        // The name goes into SQL as an identifier, which has no placeholder.
        if (!Objects.requireNonNull(name, "name").matches("[a-z][a-z0-9_]*_inbox")) {
            throw new IllegalArgumentException("not an inbox table name: '" + name + "'");
        }
        this.name = name;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.kinds = kindsOf(payloads);
    }

    /**
     * Describes a table whose kinds are the records {@code payloads} permits.
     *
     * @throws IllegalArgumentException if {@code payloads} is not a sealed interface of records
     */
    public static <P> InboxTable<P> of(final String name, final Channel channel, final Class<P> payloads) {
        return new InboxTable<>(name, channel, payloads);
    }

    /** Returns the table's name, as the SQL spells it. */
    public String name() {
        return name;
    }

    /** Returns the channel every change of a row is announced on. */
    public Channel channel() {
        return channel;
    }

    /** Returns every kind's name, as the table's {@code kind} column and its {@code CHECK} spell it. */
    public List<String> kinds() {
        return List.copyOf(kinds.keySet());
    }

    /** Returns the kind a payload is stored under. */
    public String kindOf(final P payload) {
        return kindName(Objects.requireNonNull(payload, "payload").getClass());
    }

    /** Returns the kind a payload type is stored under. */
    public String kindOf(final Class<? extends P> type) {
        return kindName(type);
    }

    /** Returns the payload type stored under a kind, or empty for a kind this build does not know. */
    Optional<Class<? extends P>> typeOf(final String kind) {
        return Optional.ofNullable(kinds.get(kind));
    }

    /** Returns the payload type itself, for a cast the compiler cannot prove. */
    Class<P> payloads() {
        return payloads;
    }

    @Override
    public String toString() {
        return name;
    }

    private static <P> Map<String, Class<? extends P>> kindsOf(final Class<P> payloads) {
        final Class<?>[] permitted = payloads.getPermittedSubclasses();
        if (!payloads.isInterface() || permitted == null || permitted.length == 0) {
            throw new IllegalArgumentException(payloads.getName() + " is not a sealed interface");
        }
        final List<Class<? extends P>> records = new ArrayList<>();
        for (final Class<?> type : permitted) {
            if (!type.isRecord()) {
                throw new IllegalArgumentException(type.getName() + " is a kind and therefore a record");
            }
            records.add(type.asSubclass(payloads));
        }
        return records.stream()
                .collect(Collectors.toMap(
                        InboxTable::kindName,
                        Function.identity(),
                        (one, two) -> {
                            throw new IllegalArgumentException(one + " and " + two + " share a kind name");
                        },
                        java.util.LinkedHashMap::new));
    }

    /** {@code SetPlaytime} is {@code SET_PLAYTIME}. */
    private static String kindName(final Class<?> type) {
        return type.getSimpleName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }
}
