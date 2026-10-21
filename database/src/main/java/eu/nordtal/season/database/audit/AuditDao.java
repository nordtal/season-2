package eu.nordtal.season.database.audit;

import eu.nordtal.season.database.AuditEntryMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jspecify.annotations.Nullable;

/** The SQL of the journal. {@link AuditDirectory} is its API and {@link Journal} writes the entries. */
interface AuditDao {

    /** Selects every entry with the values of its family, which {@link AuditEntryMapper} reads. */
    String ENTRIES = """
            SELECT e.id, e.occurred, e.action, e.actor_kind, e.actor_id, e.subject, e.mc_uuid, e.service, e.run,
                   e.payment, p.reference, p.days AS ordered, p.matched_cents, p.matched_by,
                   r.kind AS run_kind, ARRAY(SELECT jsonb_array_elements_text(r.payload -> 'services')) AS run_services,
                   r.payload ->> 'archive' AS run_archive, r.payload ->> 'artifact' AS run_artifact,
                   g.days AS grant_days, g.until AS grant_until, rv.grants, pt.seconds AS playtime_seconds,
                   ul.self_service, b.days AS booked_days, b.donation_cents, ph.from_phase, ph.to_phase, ph.reason,
                   la.opens, ss.starts, ss.moved_grants, k.label, k.unlocked, k.hold_seconds AS key_hold_seconds,
                   k.keys_left, f.hold_seconds AS factor_hold_seconds, f.keys, f.authenticator, f.sessions,
                   pu.alert, pu.channel, pu.enabled, co.command, st.setting_group, bu.bundle, pl.artifact,
                   bk.archive, bk.size_bytes, ob.objective, mi.milestone, un.valid_from, au.aura, sp.spins,
                   ARRAY(SELECT l.language FROM journal_language l WHERE l.entry = e.id ORDER BY l.position)
                       AS languages,
                   ARRAY(SELECT a.discord_id FROM journal_admin_below a WHERE a.entry = e.id ORDER BY a.position)
                       AS below
            FROM journal_entry e
                     LEFT JOIN payment_request p ON p.id = e.payment
                     LEFT JOIN steward_inbox r ON r.id = e.run
                     LEFT JOIN journal_grant g ON g.entry = e.id
                     LEFT JOIN journal_revoke rv ON rv.entry = e.id
                     LEFT JOIN journal_playtime pt ON pt.entry = e.id
                     LEFT JOIN journal_unlink ul ON ul.entry = e.id
                     LEFT JOIN journal_booking b ON b.entry = e.id
                     LEFT JOIN journal_phase ph ON ph.entry = e.id
                     LEFT JOIN journal_launch la ON la.entry = e.id
                     LEFT JOIN journal_smp_start ss ON ss.entry = e.id
                     LEFT JOIN journal_key k ON k.entry = e.id
                     LEFT JOIN journal_factor f ON f.entry = e.id
                     LEFT JOIN journal_push pu ON pu.entry = e.id
                     LEFT JOIN journal_console co ON co.entry = e.id
                     LEFT JOIN journal_setting st ON st.entry = e.id
                     LEFT JOIN journal_bundle bu ON bu.entry = e.id
                     LEFT JOIN journal_plugin pl ON pl.entry = e.id
                     LEFT JOIN journal_backup bk ON bk.entry = e.id
                     LEFT JOIN journal_objective ob ON ob.entry = e.id
                     LEFT JOIN journal_milestone mi ON mi.entry = e.id
                     LEFT JOIN journal_unanchored un ON un.entry = e.id
                     LEFT JOIN journal_aura au ON au.entry = e.id
                     LEFT JOIN journal_spins sp ON sp.entry = e.id
            """;

    /**
     * Returns the newest entries but alerts.
     * {@code id} breaks ties, as one transaction's entries share {@code occurred}.
     */
    @SqlQuery(ENTRIES + """
            WHERE e.level IS NULL
            ORDER BY e.occurred DESC, e.id DESC
            LIMIT :limit
            """)
    @RegisterRowMapper(AuditEntryMapper.class)
    List<AuditEntry> recent(@Bind("limit") int limit);

    @SqlQuery(ENTRIES + "WHERE e.id = :id")
    @RegisterRowMapper(AuditEntryMapper.class)
    Optional<AuditEntry> entry(@Bind("id") UUID id);

    /**
     * Returns the newest entries in one statement whose predicates switch off on null.
     *
     * The casts give {@code :action IS NULL} a type. Days are a grant's or a booking's.
     */
    @SqlQuery(ENTRIES + """
            WHERE e.level IS NULL
              AND (cast(:action AS varchar) IS NULL OR e.action = cast(:action AS varchar))
              AND (cast(:subject AS varchar) IS NULL OR e.subject = cast(:subject AS varchar))
              AND (cast(:reference AS text) IS NULL OR p.reference = cast(:reference AS text))
              AND (cast(:minDays AS integer) IS NULL OR coalesce(g.days, b.days) >= cast(:minDays AS integer))
            ORDER BY e.occurred DESC, e.id DESC
            LIMIT :limit
            """)
    @RegisterRowMapper(AuditEntryMapper.class)
    List<AuditEntry> search(
            @Bind("action") @Nullable String action,
            @Bind("subject") @Nullable String subject,
            @Bind("reference") @Nullable String reference,
            @Bind("minDays") @Nullable Integer minDays,
            @Bind("limit") int limit);
}
