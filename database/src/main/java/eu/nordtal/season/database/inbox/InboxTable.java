package eu.nordtal.season.database.inbox;

import eu.nordtal.season.database.notify.Channel;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One consumer's inbox table: its name, the channel every change is announced on, and the kinds it takes.
 * The kinds are the records a sealed payload type permits, each stored under its name in upper snake case.
 * A kind is either a row of its own table, one column per component, or still JSON in the inbox's payload column.
 */
public final class InboxTable<P> {

    private final String name;
    private final Channel channel;
    private final Class<P> payloads;
    private final Map<String, Class<? extends P>> kinds;
    private final Set<Class<? extends P>> rows;

    private InboxTable(
            final String name, final Channel channel, final Class<P> payloads, final Set<Class<? extends P>> rows) {
        // The name goes into SQL as an identifier, which has no placeholder.
        if (!Objects.requireNonNull(name, "name").matches("[a-z][a-z0-9_]*_inbox")) {
            throw new IllegalArgumentException("not an inbox table name: '" + name + "'");
        }
        this.name = name;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.kinds = kindsOf(payloads);
        this.rows = Set.copyOf(rows);
        if (!kinds.values().containsAll(this.rows)) {
            throw new IllegalArgumentException(this.rows + " are not all kinds of " + payloads.getName());
        }
    }

    /**
     * Describes a table whose kinds are the records {@code payloads} permits.
     *
     * @throws IllegalArgumentException if {@code payloads} is not a sealed interface of records
     */
    public static <P> InboxTable<P> of(final String name, final Channel channel, final Class<P> payloads) {
        return new InboxTable<>(name, channel, payloads, Set.of());
    }

    /** Returns this table with {@code kinds} stored as rows of their own tables instead of JSON in the payload. */
    @SafeVarargs
    public final InboxTable<P> withRows(final Class<? extends P>... kinds) {
        return new InboxTable<>(name, channel, payloads, Set.of(kinds));
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

    /** Returns whether a kind is a row of its own table rather than JSON in the payload column. */
    boolean isRow(final Class<?> type) {
        return rows.contains(type);
    }

    /** Returns whether some kind is still JSON, so the inbox table still has its payload column. */
    boolean hasPayload() {
        return rows.size() < kinds.size();
    }

    /** Returns the table a kind's rows are in: {@code bank_open_tab} for {@code OpenTab} of {@code bank_inbox}. */
    String rowTable(final Class<?> type) {
        return name.replaceFirst("_inbox$", "_") + kindName(type).toLowerCase(Locale.ROOT);
    }

    /** Returns the column a component is stored in: {@code replaces_local} for {@code replacesLocal}. */
    static String column(final RecordComponent component) {
        return snake(component.getName()).toLowerCase(Locale.ROOT);
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
        return snake(type.getSimpleName()).toUpperCase(Locale.ROOT);
    }

    private static String snake(final String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
    }
}
