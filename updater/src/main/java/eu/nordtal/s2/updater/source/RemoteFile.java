package eu.nordtal.s2.updater.source;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;

/**
 * One downloadable file, as the source that published it describes it.
 *
 * <h2>{@code fileName} is the API's name, never one we build</h2>
 * Every source in this module hands back a filename it was told, not one it assembled from a
 * version and a template. The difference shows up immediately in the real payloads:
 * PacketEvents' Modrinth version is {@code 2.13.0+spigot} while its file is
 * {@code packetevents-spigot-2.13.0.jar}, and Chunky's version {@code 1.5.3} becomes
 * {@code Chunky-Bukkit-1.5.3.jar}. Neither is derivable from the other. The Fill API is the same
 * story from the other direction: it publishes {@code paper-26.2-121.jar} as a field, which is
 * the exact name {@code entrypoint.sh} builds by hand - so reading it keeps the two in step for
 * free.
 *
 * <p>This matters beyond tidiness, because <b>the filename is the identity of what is installed</b>
 * ({@link eu.nordtal.s2.updater.plan.JarName}). A name we invented that differs by one character
 * from the name on disk is an update that appears to be needed forever.</p>
 *
 * @param artifact the stable id this module knows the thing by - {@code smp}, {@code chunky},
 *                 {@code paper}. Not the filename and not the project name: it is what the
 *                 topology and the report join on.
 * @param version  the version as the source states it, for humans. Never parsed, never compared
 *                 for ordering - see {@link eu.nordtal.s2.updater.plan.JarName} for why the
 *                 comparison is on filenames instead.
 * @param checksum {@code null} where the source publishes none, which is every GitHub asset.
 */
public record RemoteFile(@NotNull String artifact,
                         @NotNull String version,
                         @NotNull String fileName,
                         @NotNull URI url,
                         @Nullable Checksum checksum) {
}
