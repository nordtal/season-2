package eu.nordtal.s2.common.audit;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One line of {@code audit_log}, append-only, serialised straight to JSON.
 *
 * @param occurred when it happened, by the database's clock
 * @param action   such as {@code LINK} or {@code GRANT_ACCESS}; a string, as the column has no CHECK
 * @param actor    the Discord id of the admin who caused it, {@code null} when the bot acted on its own
 * @param subject  the Discord id the line is about, {@code null} when there is no one person
 * @param mcUuid   the Minecraft account for link and unlink, {@code null} otherwise
 * @param detail   free text, {@code null} when the action says everything
 */
public record AuditEntry(
        UUID id,
        Instant occurred,
        String action,
        @Nullable String actor,
        @Nullable String subject,
        @Nullable UUID mcUuid,
        @Nullable String detail) {}
