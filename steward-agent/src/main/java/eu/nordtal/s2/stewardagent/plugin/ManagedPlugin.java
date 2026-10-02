package eu.nordtal.s2.stewardagent.plugin;

import eu.nordtal.s2.common.id.Actor;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One plugin an admin added to a service; a wish, not an observation of what is installed.
 *
 * @param service    the compose service name
 * @param artifact   Modrinth's slug, a label for reports and never the identity
 * @param projectId  Modrinth's immutable project id, which the API is asked with
 * @param filePrefix the jar's filename prefix, kept so removal needs no remote API call
 * @param title      the human name, for the interface only
 * @param iconUrl    the thumbnail on Modrinth's CDN, or {@code null}
 * @param pageUrl    the project's page, or {@code null}
 * @param added      when the row was written, on the database's clock
 * @param addedBy    who asked for it
 */
public record ManagedPlugin(
        String service,
        String artifact,
        String projectId,
        String filePrefix,
        String title,
        @Nullable String iconUrl,
        @Nullable String pageUrl,
        Instant added,
        Actor addedBy) {}
