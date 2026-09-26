package eu.nordtal.s2.common.audit;

import java.util.List;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/**
 * The SQL surface of the journal; {@link AuditDirectory} is the API.
 *
 * The insert is for steward-ui's grants and revokes; the phase switch writes its own row and must not use it.
 */
interface AuditDao {

    /** Returns the newest lines; {@code id} breaks ties, since one transaction's entries share {@code occurred}. */
    @SqlQuery("""
            SELECT id, occurred, action, actor, subject, mc_uuid, detail
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
            SELECT id, occurred, action, actor, subject, mc_uuid, detail
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

    /**
     * One line in the journal.
     *
     * Deliberately not transactional with whatever it describes. An {@code access_grant} that
     * lands and a journal line that does not is a bug worth finding; a grant rolled back because
     * the journal was momentarily unavailable is a member locked out by a bookkeeping error. The
     * caller writes the action first and records it second.
     */
    @SqlUpdate("""
            INSERT INTO audit_log (action, actor, subject, mc_uuid, detail)
            VALUES (:action, :actor, :subject, :mcUuid, :detail)
            """)
    void record(
            @Bind("action") String action,
            @Bind("actor") @Nullable String actor,
            @Bind("subject") @Nullable String subject,
            @Bind("mcUuid") @Nullable UUID mcUuid,
            @Bind("detail") @Nullable String detail);
}
