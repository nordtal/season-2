package eu.nordtal.s2.steward.source;

import java.io.IOException;
import java.nio.file.Path;

/** Whatever puts a {@link RemoteFile} on disk, verified; an interface so a failed download can be tested. */
public interface Fetcher {

    void fetch(RemoteFile file, Path destination) throws IOException;
}
