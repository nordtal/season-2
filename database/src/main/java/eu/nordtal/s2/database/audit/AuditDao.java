package eu.nordtal.s2.database.audit;

import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jspecify.annotations.Nullable;

/**
 * The SQL surface of the journal; {@link AuditDirectory} is the API.
 *
 * The insert is {@link Journal}'s, which every writer goes through.
 */
interface AuditDao {

    /** Returns the newest lines; {@code id} breaks ties, since one transaction's entries share {@code occurred}. */
    @SqlQuery("""
            SELECT id, occurred, action, actor_kind, actor_id, subject, mc_uuid, facts
            FROM audit_log
            ORDER BY occurred DESC, id DESC
            LIMIT :limit
            """)
    @RegisterRowMapper(AuditEntryMapper.class)
    List<AuditEntry> recent(@Bind("limit") int limit);

    /**
     * Returns the newest lines, filtered, in one statement whose predicates switch off on null.
     *
     * The casts are required: {@code :action IS NULL} alone leaves the parameter type undeterminable.
     */
    @SqlQuery("""
            SELECT id, occurred, action, actor_kind, actor_id, subject, mc_uuid, facts
            FROM audit_log
            WHERE (cast(:action AS varchar) IS NULL OR action = cast(:action AS varchar))
              AND (cast(:subject AS varchar) IS NULL OR subject = cast(:subject AS varchar))
            ORDER BY occurred DESC, id DESC
            LIMIT :limit
            """)
    @RegisterRowMapper(AuditEntryMapper.class)
    List<AuditEntry> search(
            @Bind("action") @Nullable String action,
            @Bind("subject") @Nullable String subject,
            @Bind("limit") int limit);
}
