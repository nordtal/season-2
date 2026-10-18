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
 * The inbox table of one consumer: its name, the channel for every change and the kinds it takes.
 * The kinds are the records of a sealed payload type, each stored under its name in upper snake case.
 * A kind is a row of its own table, one column per component, or JSON in the payload column.
 */
public final class InboxTable<P> {

    private final String name;
    private final Channel channel;
    private final Class<P> payloads;
    private final Map<String, Class<? extends P>> kinds;
    private final Set<Class<? extends P>> rows;

    private InboxTable(
            final String name, final Channel channel, final Class<P> payloads, final Set<Class<? extends P>> rows) {
        // The name enters SQL as an identifier, which has no placeholder.
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
     * Describes a table whose kinds are the records that {@code payloads} permits.
     *
     * @throws IllegalArgumentException if {@code payloads} is not a sealed interface of records
     */
    public static <P> InboxTable<P> of(final String name, final Channel channel, final Class<P> payloads) {
        return new InboxTable<>(name, channel, payloads, Set.of());
    }

    /** Returns this table with {@code kinds} stored as rows of their own tables instead of JSON. */
    @SafeVarargs
    public final InboxTable<P> withRows(final Class<? extends P>... kinds) {
        return new InboxTable<>(name, channel, payloads, Set.of(kinds));
    }

    public String name() {
        return name;
    }

    public Channel channel() {
        return channel;
    }

    /** Returns the kind names, as the {@code kind} column spells them. */
    public List<String> kinds() {
        return List.copyOf(kinds.keySet());
    }

    public String kindOf(final P payload) {
        return kindName(Objects.requireNonNull(payload, "payload").getClass());
    }

    public String kindOf(final Class<? extends P> type) {
        return kindName(type);
    }

    Optional<Class<? extends P>> typeOf(final String kind) {
        return Optional.ofNullable(kinds.get(kind));
    }

    boolean isRow(final Class<?> type) {
        return rows.contains(type);
    }

    /** Returns whether a kind is still JSON, so the table has its payload column. */
    boolean hasPayload() {
        return rows.size() < kinds.size();
    }

    /** Returns the table of the rows of a kind: {@code bank_open_tab} for {@code OpenTab} of {@code bank_inbox}. */
    String rowTable(final Class<?> type) {
        return name.replaceFirst("_inbox$", "_") + kindName(type).toLowerCase(Locale.ROOT);
    }

    /** Returns the column of a component: {@code replaces_local} for {@code replacesLocal}. */
    static String column(final RecordComponent component) {
        return snake(component.getName()).toLowerCase(Locale.ROOT);
    }

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

    /** Returns the kind name: {@code SetPlaytime} is {@code SET_PLAYTIME}. */
    private static String kindName(final Class<?> type) {
        return snake(type.getSimpleName()).toUpperCase(Locale.ROOT);
    }

    private static String snake(final String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
    }
}
