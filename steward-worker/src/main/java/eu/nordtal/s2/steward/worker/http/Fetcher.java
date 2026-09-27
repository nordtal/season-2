package eu.nordtal.s2.steward.worker.http;

import eu.nordtal.s2.steward.worker.source.RemoteFile;
import java.io.IOException;
import java.nio.file.Path;

/** Whatever puts a {@link RemoteFile} on disk, verified; an interface so a failed download can be tested. */
public interface Fetcher {

    void fetch(RemoteFile file, Path destination) throws IOException;
}
