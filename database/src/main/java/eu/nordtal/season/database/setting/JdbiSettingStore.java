package eu.nordtal.season.database.setting;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.Jdbis;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/** The only implementation of {@link SettingStore}; it borrows the pool it is given and owns nothing. */
final class JdbiSettingStore implements SettingStore {

    private static final String GROUP_COLUMNS =
            "service, name, schema::text AS schema, defaults::text AS defaults, environment, live, problem, published";

    private final Jdbi jdbi;

    JdbiSettingStore(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public void publish(
            final String service,
            final String name,
            final String schema,
            final String defaults,
            final List<String> environment,
            final boolean live) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO setting_group (service, name, schema, defaults, environment, live, problem, published)
                        VALUES (:service, :name, CAST(:schema AS jsonb), CAST(:defaults AS jsonb), :environment, :live,
                                NULL, now())
                        ON CONFLICT (service, name) DO UPDATE
                            SET schema = excluded.schema, defaults = excluded.defaults,
                                environment = excluded.environment, live = excluded.live, problem = NULL,
                                published = excluded.published""")
                .bind("service", service)
                .bind("name", name)
                .bind("schema", schema)
                .bind("defaults", defaults)
                .bindArray("environment", String.class, environment)
                .bind("live", live)
                .execute());
    }

    @Override
    public void problem(final String service, final String name, final @Nullable String problem) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "UPDATE setting_group SET problem = :problem WHERE service = :service AND name = :name"
                                + " AND problem IS DISTINCT FROM :problem")
                .bind("service", service)
                .bind("name", name)
                .bind("problem", problem)
                .execute());
    }

    @Override
    public List<Value> overrides(final Collection<String> services) {
        if (services.isEmpty()) {
            return List.of();
        }
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT service, name, path, value::text AS value FROM setting_override
                        WHERE service = ANY(:services) ORDER BY service, name, path""")
                .bindArray("services", String.class, List.copyOf(services))
                .map((rows, context) -> valueOf(rows))
                .list());
    }

    @Override
    public List<Group> groups() {
        return jdbi.withHandle(
                handle -> handle.createQuery("SELECT " + GROUP_COLUMNS + " FROM setting_group ORDER BY service, name")
                        .map((rows, context) -> groupOf(rows))
                        .list());
    }

    @Override
    public Optional<Group> group(final String service, final String name) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT " + GROUP_COLUMNS + " FROM setting_group WHERE service = :service AND name = :name")
                .bind("service", service)
                .bind("name", name)
                .map((rows, context) -> groupOf(rows))
                .findOne());
    }

    @Override
    public boolean change(
            final String service,
            final String name,
            final Map<String, @Nullable String> values,
            final Actor actor,
            final Predicate<List<Value>> current) {
        return jdbi.inTransaction(handle -> {
            // One lock per group to the end of the transaction, which also holds for a group not yet published.
            handle.execute(
                    "SELECT pg_advisory_xact_lock(hashtext('setting_override'), hashtext(?))", service + "/" + name);
            final List<Value> held = handle.createQuery("""
                            SELECT service, name, path, value::text AS value FROM setting_override
                            WHERE service = :service AND name = :name ORDER BY path""")
                    .bind("service", service)
                    .bind("name", name)
                    .map((rows, context) -> valueOf(rows))
                    .list();
            if (!current.test(held)) {
                return false;
            }
            for (final Map.Entry<String, @Nullable String> value : values.entrySet()) {
                if (value.getValue() == null) {
                    remove(handle, service, name, value.getKey());
                } else {
                    set(handle, service, name, value.getKey(), value.getValue(), actor);
                }
            }
            signal(handle, service);
            return true;
        });
    }

    private static void set(
            final Handle handle,
            final String service,
            final String name,
            final String path,
            final String value,
            final Actor actor) {
        handle.createUpdate("""
                        INSERT INTO setting_override (service, name, path, value, actor_kind, actor_id, changed)
                        VALUES (:service, :name, :path, CAST(:value AS jsonb), :kind, :id, now())
                        ON CONFLICT (service, name, path) DO UPDATE
                            SET value = excluded.value, actor_kind = excluded.actor_kind,
                                actor_id = excluded.actor_id, changed = excluded.changed""")
                .bind("service", service)
                .bind("name", name)
                .bind("path", path)
                .bind("value", value)
                .bind("kind", actor.kind().name())
                .bind("id", actor.id())
                .execute();
    }

    private static void remove(final Handle handle, final String service, final String name, final String path) {
        handle.createUpdate("DELETE FROM setting_override WHERE service = :service AND name = :name AND path = :path")
                .bind("service", service)
                .bind("name", name)
                .bind("path", path)
                .execute();
    }

    /** Committed with the change, so nobody re-reads before the rows are there. */
    private static void signal(final Handle handle, final String service) {
        handle.createQuery("SELECT pg_notify('nordtal_settings', :service) IS NULL")
                .bind("service", service)
                .mapTo(Boolean.class)
                .one();
    }

    private static Value valueOf(final ResultSet rows) throws SQLException {
        return new Value(
                rows.getString("service"), rows.getString("name"), rows.getString("path"), rows.getString("value"));
    }

    private static Group groupOf(final ResultSet rows) throws SQLException {
        final Array environment = rows.getArray("environment");
        return new Group(
                rows.getString("service"),
                rows.getString("name"),
                rows.getString("schema"),
                rows.getString("defaults"),
                List.of((String[]) environment.getArray()),
                rows.getBoolean("live"),
                rows.getString("problem"),
                rows.getTimestamp("published").toInstant());
    }
}
