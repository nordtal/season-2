package eu.nordtal.s2.common.plugin;

import java.time.Instant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One plugin an admin added to a service from the interface (season-2-ops/129).
 *
 * <p><b>A row here is a wish, not an observation.</b> What is actually installed is the jars in the
 * volume, and {@code Installation} reads those off the disk on every run. This record says only
 * that somebody asked for the plugin, which is why the interface has to draw <em>running</em> and
 * <em>not installed</em> differently: a row exists the moment the button is pressed, and the jar
 * arrives with the next update run.</p>
 *
 * @param service    the compose service name this plugin was added to
 * @param artifact   the artefact id - Modrinth's slug. The label a report line reads by, never the
 *                   identity: see {@link #projectId()}
 * @param projectId  Modrinth's immutable project id, which is what the API is actually asked with
 * @param filePrefix the filename prefix of the jar, as {@code JarName#prefixOf} reads it
 *                   ({@code worldedit-bukkit} for {@code worldedit-bukkit-7.3.0.jar}). Written down
 *                   when the plugin is added, because removing it means deleting that jar and the
 *                   alternative is asking somebody else's API over the internet at the moment a
 *                   button is pressed
 * @param title      the human name, for the interface only
 * @param iconUrl    the thumbnail on Modrinth's CDN, or {@code null} for a project with no icon
 * @param pageUrl    the project's page, or {@code null}
 * @param added      when the row was written, on the database's clock
 * @param addedBy    who asked for it, in the shape {@code update_request.requested_by} uses, or
 *                   {@code null} when it was not a person
 */
public record ManagedPlugin(
        @NotNull String service,
        @NotNull String artifact,
        @NotNull String projectId,
        @NotNull String filePrefix,
        @NotNull String title,
        @Nullable String iconUrl,
        @Nullable String pageUrl,
        @NotNull Instant added,
        @Nullable String addedBy) {}
