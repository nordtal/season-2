package eu.nordtal.season.stewardagent.source;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * One downloadable file, with the filename the source published, never one built here.
 *
 * @param artifact the stable id the topology and the report join on, such as {@code smp} or {@code paper}
 * @param version the version as the source states it, for humans and never compared
 * @param checksum {@code null} where Modrinth or Fill publish none; a season jar without one is never resolved
 */
public record RemoteFile(
        String artifact,
        String version,
        String fileName,
        URI url,
        @Nullable Checksum checksum) {}
