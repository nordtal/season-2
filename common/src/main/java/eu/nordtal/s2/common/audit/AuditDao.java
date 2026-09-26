package eu.nordtal.s2.common.audit;

import java.util.List;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The whole SQL surface of the journal, as a JDBI SqlObject interface. Package-private on purpose:
 * {@link AuditDirectory} is the API, and no consumer should hold a {@code Jdbi} or a DAO.
 *
 * <p>Two of the three statements are {@code SELECT}s. The third exists because writing
 * {@code audit_log} belongs to whatever performs the action being recorded, and one of those things
 * is now the web interface: {@code steward-ui} grants and revokes access, and a grant nobody can
 * attribute afterwards is the failure the journal exists to prevent. The phase switch still writes
 * its own row inside the single statement that performs it, and must not use this.
 */
interface AuditDao {

    /**
     * The newest lines. {@code id} breaks the tie on {@code occurred}, which matters more here than
     * anywhere else in the schema: several entries written inside one transaction can share
     * {@code occurred} exactly, because {@code now()} is the transaction's start time and not the
     * statement's.
     */
    @SqlQuery("""
            SELECT id, occurred, action, actor, subject, mc_uuid, detail
            FROM audit_log
            ORDER BY occurred DESC, id DESC
            LIMIT :limit
            """)
    @RegisterRowMapper(AuditEntryMapper.class)
    List<AuditEntry> recent(@Bind("limit") int limit);

    /**
     * The same list, filtered. <b>One statement</b> - the two predicates switch themselves off when
     * the parameter is null, rather than the Java side gluing a {@code WHERE} clause together out of
     * strings. A journal is the last place in a schema that should learn to concatenate SQL, and a
     * single statement is also a single entry in PostgreSQL's plan cache.
     *
     * <p>The casts are load-bearing. A bare {@code :action IS NULL} leaves the parameter's type for
     * PostgreSQL to infer from its context, and {@code IS NULL} gives it none, so the statement is
     * rejected before it runs with "could not determine data type of parameter". Naming the type
     * once on each side settles it.
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
    List<AuditEntry> search(@Bind("action") String action, @Bind("subject") String subject, @Bind("limit") int limit);

    /**
     * One line in the journal.
     *
     * <p>Deliberately not transactional with whatever it describes. An {@code access_grant} that
     * lands and a journal line that does not is a bug worth finding; a grant rolled back because
     * the journal was momentarily unavailable is a member locked out by a bookkeeping error. The
     * caller writes the action first and records it second.</p>
     */
    @SqlUpdate("""
            INSERT INTO audit_log (action, actor, subject, mc_uuid, detail)
            VALUES (:action, :actor, :subject, :mcUuid, :detail)
            """)
    void record(
            @Bind("action") String action,
            @Bind("actor") String actor,
            @Bind("subject") String subject,
            @Bind("mcUuid") UUID mcUuid,
            @Bind("detail") String detail);
}
