package eu.nordtal.season.stewardagent.source;

import java.io.IOException;
import java.nio.file.Path;

/** Puts a {@link RemoteFile} on disk, verified. Tests replace it to fail a download. */
public interface Fetcher {

    void fetch(RemoteFile file, Path destination) throws IOException;
}
