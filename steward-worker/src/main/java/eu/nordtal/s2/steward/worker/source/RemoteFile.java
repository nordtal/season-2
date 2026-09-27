package eu.nordtal.s2.steward.worker.source;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * One downloadable file, with the filename the source published, never one built here.
 *
 * @param artifact the stable id the topology and the report join on, such as {@code smp} or {@code paper}
 * @param version the version as the source states it, for humans and never compared
 * @param checksum {@code null} where the source publishes none, which is every GitHub asset
 */
public record RemoteFile(
        String artifact,
        String version,
        String fileName,
        URI url,
        @Nullable Checksum checksum) {}
