package eu.nordtal.season.database.inbox;

import java.lang.reflect.RecordComponent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.core.statement.Update;
import org.jspecify.annotations.Nullable;

/** Writes and reads the row of a request's kind, a column per component, and the rows of its lists of records. */
final class KindRows {

    private final InboxTable<?> table;

    KindRows(final InboxTable<?> table) {
        this.table = Objects.requireNonNull(table, "table");
    }

    /** Writes the row of a row kind's request and a row per element of each of its lists. Other kinds write none. */
    void insertRow(final Handle handle, final long id, final Object payload) {
        if (!table.isRow(payload.getClass())) {
            return;
        }
        insert(handle, table.rowTable(payload.getClass()), id, payload);
        for (final RecordComponent component : payload.getClass().getRecordComponents()) {
            if (InboxTable.elementOf(component).isPresent()) {
                final String elements = table.elementTable(payload.getClass(), component.getName());
                for (final Object element : (List<?>) Objects.requireNonNull(component(component, payload))) {
                    insert(handle, elements, id, element);
                }
            }
        }
    }

    /** Inserts a row of {@code request} and a column per component of {@code record} that is no list of records. */
    private static void insert(final Handle handle, final String into, final long id, final Object record) {
        final StringBuilder columns = new StringBuilder("request");
        final StringBuilder values = new StringBuilder(":request");
        for (final RecordComponent component : scalars(record.getClass())) {
            columns.append(", ").append(InboxTable.column(component));
            values.append(", :").append(component.getName());
        }
        bound(handle.createUpdate("INSERT INTO <row> (" + columns + ") VALUES (" + values + ")"), record)
                .define("row", into)
                .bind("request", id)
                .execute();
    }

    /**
     * Writes a typed answer into the row of its request's kind, a column per component.
     * A list writes each element into the row of the kind's list of that name whose first column matches.
     */
    void answer(final Handle handle, final long id, final Class<?> kind, final Object answer) {
        update(handle, table.rowTable(kind), id, answer, null);
        for (final RecordComponent component : answer.getClass().getRecordComponents()) {
            if (InboxTable.elementOf(component).isPresent()) {
                final String elements = table.elementTable(kind, component.getName());
                for (final Object element : (List<?>) Objects.requireNonNull(component(component, answer))) {
                    update(
                            handle,
                            elements,
                            id,
                            element,
                            element.getClass().getRecordComponents()[0].getName());
                }
            }
        }
    }

    /** Sets a column per component of {@code record} on the row of {@code request} whose {@code key} column matches. */
    private static void update(
            final Handle handle, final String table, final long id, final Object record, final @Nullable String key) {
        final StringBuilder columns = new StringBuilder();
        final StringBuilder where = new StringBuilder("request = :request");
        for (final RecordComponent component : scalars(record.getClass())) {
            final String assigned = InboxTable.column(component) + " = :" + component.getName();
            if (component.getName().equals(key)) {
                where.append(" AND ").append(assigned);
            } else {
                columns.append(columns.isEmpty() ? "" : ", ").append(assigned);
            }
        }
        if (columns.isEmpty()) {
            return;
        }
        final int updated = bound(handle.createUpdate("UPDATE <row> SET " + columns + " WHERE " + where), record)
                .define("row", table)
                .bind("request", id)
                .execute();
        if (updated != 1) {
            throw new IllegalArgumentException(table + " has no row of request " + id + " for " + record);
        }
    }

    /** Returns the components of a record that are a column, every one but a list of records. */
    private static List<RecordComponent> scalars(final Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .filter(component -> InboxTable.elementOf(component).isEmpty())
                .toList();
    }

    /** Binds every component of a record that is a column by its name. */
    private static Update bound(final Update update, final Object record) {
        for (final RecordComponent component : scalars(record.getClass())) {
            update.bindByType(component.getName(), component(component, record), component.getGenericType());
        }
        return update;
    }

    private static @Nullable Object component(final RecordComponent component, final Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (final ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Reads a record off the row of a kind, a component per column: the request, or the answer of its kind. */
    <T> T readRow(final Handle handle, final long id, final Class<?> kind, final Class<T> type) {
        return handle.createQuery("SELECT * FROM <row> WHERE request = :id")
                .define("row", table.rowTable(kind))
                .bind("id", id)
                .map((row, context) -> record(handle, id, kind, type, row, context))
                .findOne()
                .orElseThrow(() ->
                        new IllegalStateException(table + " request " + id + " has no row in " + table.rowTable(kind)));
    }

    /** Reads the elements of a list component off their table, in the order of their first column. */
    private List<?> readElements(final Handle handle, final long id, final Class<?> kind, final RecordComponent list) {
        final Class<?> element = InboxTable.elementOf(list).orElseThrow();
        return handle.createQuery("SELECT * FROM <elements> WHERE request = :id ORDER BY <first>")
                .define("elements", table.elementTable(kind, list.getName()))
                .define("first", InboxTable.column(element.getRecordComponents()[0]))
                .bind("id", id)
                .map((row, context) -> record(handle, id, kind, element, row, context))
                .list();
    }

    /** Builds a record from a row, a component per column, and a list of records from its own table. */
    private <T> T record(
            final Handle handle,
            final long id,
            final Class<?> kind,
            final Class<T> type,
            final ResultSet row,
            final StatementContext context)
            throws SQLException {
        final RecordComponent[] components = type.getRecordComponents();
        final Class<?>[] types = new Class<?>[components.length];
        final Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            final RecordComponent component = components[i];
            types[i] = component.getType();
            values[i] = InboxTable.elementOf(component).isPresent()
                    ? readElements(handle, id, kind, component)
                    : context.findColumnMapperFor(component.getGenericType())
                            .orElseThrow(() -> new IllegalStateException("no column mapper for " + component))
                            .map(row, InboxTable.column(component), context);
        }
        try {
            return type.getDeclaredConstructor(types).newInstance(values);
        } catch (final ReflectiveOperationException failure) {
            throw new IllegalStateException(table + " request " + id + " reads as no " + type, failure);
        }
    }
}
