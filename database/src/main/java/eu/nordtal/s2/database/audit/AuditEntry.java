package eu.nordtal.s2.database.audit;

import com.google.gson.JsonObject;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One line of {@code audit_log}, append-only, serialised straight to JSON for Steward to render.
 *
 * @param occurred when it happened, by the database's clock
 * @param action   such as {@code LINK} or {@code GRANT_ACCESS}; a string, as the column has no CHECK
 * @param actor    who did it: a person, Steward on its own, or the host
 * @param subject  the person the line is about, {@code null} when there is no one person
 * @param mcUuid   the Minecraft account for link and unlink, {@code null} otherwise
 * @param facts    the line's typed values by key, as they were written
 */
public record AuditEntry(
        UUID id,
        Instant occurred,
        String action,
        Actor actor,
        @Nullable DiscordId subject,
        @Nullable UUID mcUuid,
        JsonObject facts) {}
