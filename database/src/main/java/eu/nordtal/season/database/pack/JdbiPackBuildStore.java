package eu.nordtal.season.database.pack;

import com.google.gson.reflect.TypeToken;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.Jdbis;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;

/** Implements {@link PackBuildStore} with JDBI. */
final class JdbiPackBuildStore implements PackBuildStore {

    private static final TypeToken<Map<String, String>> LABEL = new TypeToken<>() {};

    private static final String COLUMNS = """
            SELECT id, state, started, finished, prizes::text AS prizes, label::text AS label, sha1, size_bytes,
                   duration_ms, error
            FROM pack_build""";

    private final Jdbi jdbi;

    JdbiPackBuildStore(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public long start(final Instant started, final String prizes, final Map<String, String> label) {
        return jdbi.withHandle(handle -> handle.createUpdate("""
                        INSERT INTO pack_build (state, started, prizes, label)
                        VALUES ('BUILDING', :started, CAST(:prizes AS jsonb), CAST(:label AS jsonb))""")
                .bind("started", at(started))
                .bind("prizes", prizes)
                .bind("label", Json.encode(label))
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class)
                .one());
    }

    @Override
    public void succeed(final long id, final Instant finished, final String sha1, final long sizeBytes) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("UPDATE pack_build SET state = 'SUPERSEDED' WHERE state = 'ACTIVE' AND id <> :id")
                    .bind("id", id)
                    .execute();
            handle.createUpdate("""
                            UPDATE pack_build SET state = 'ACTIVE', finished = :finished, sha1 = :sha1,
                                size_bytes = :size, error = NULL,
                                duration_ms = GREATEST(0, (EXTRACT(EPOCH FROM (CAST(:finished AS timestamptz) - started)) * 1000)::bigint)
                            WHERE id = :id""")
                    .bind("id", id)
                    .bind("finished", at(finished))
                    .bind("sha1", sha1)
                    .bind("size", sizeBytes)
                    .execute();
        });
    }

    @Override
    public void fail(final long id, final Instant finished, final String error) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        UPDATE pack_build SET state = 'FAILED', finished = :finished, error = :error,
                            duration_ms = GREATEST(0, (EXTRACT(EPOCH FROM (CAST(:finished AS timestamptz) - started)) * 1000)::bigint)
                        WHERE id = :id AND state = 'BUILDING'""")
                .bind("id", id)
                .bind("finished", at(finished))
                .bind("error", error)
                .execute());
    }

    @Override
    public void abandon(final Instant finished, final String error) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        UPDATE pack_build SET state = 'FAILED', finished = :finished, error = :error,
                            duration_ms = GREATEST(0, (EXTRACT(EPOCH FROM (CAST(:finished AS timestamptz) - started)) * 1000)::bigint)
                        WHERE state = 'BUILDING'""")
                .bind("finished", at(finished))
                .bind("error", error)
                .execute());
    }

    @Override
    public Optional<PackBuild> active() {
        return jdbi.withHandle(handle -> handle.createQuery(COLUMNS + " WHERE state = 'ACTIVE'")
                .map((rows, context) -> build(rows))
                .findOne());
    }

    @Override
    public List<PackBuild> recent(final int limit) {
        return jdbi.withHandle(handle -> handle.createQuery(COLUMNS + " ORDER BY started DESC, id DESC LIMIT :limit")
                .bind("limit", limit)
                .map((rows, context) -> build(rows))
                .list());
    }

    private static PackBuild build(final ResultSet rows) throws SQLException {
        final OffsetDateTime finished = rows.getObject("finished", OffsetDateTime.class);
        final long duration = rows.getLong("duration_ms");
        final boolean timed = !rows.wasNull();
        final long size = rows.getLong("size_bytes");
        final boolean sized = !rows.wasNull();
        return new PackBuild(
                rows.getLong("id"),
                State.valueOf(rows.getString("state")),
                rows.getObject("started", OffsetDateTime.class).toInstant(),
                finished == null ? null : finished.toInstant(),
                rows.getString("prizes"),
                Json.decode(rows.getString("label"), LABEL),
                rows.getString("sha1"),
                sized ? size : null,
                timed ? Duration.ofMillis(duration) : null,
                rows.getString("error"));
    }

    private static OffsetDateTime at(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
