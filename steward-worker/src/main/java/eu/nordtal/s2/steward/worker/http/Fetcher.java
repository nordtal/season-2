package eu.nordtal.s2.steward.worker.http;

import eu.nordtal.s2.steward.worker.source.RemoteFile;
import java.io.IOException;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

/**
 * Whatever puts a {@link RemoteFile} on disk, verified.
 * <p>
 * One method, extracted purely so that the swap logic can be tested without a network: what
 * {@code Applier} does when a download fails half way through a set of eight jars is the part
 * worth testing, and it is unreachable if the only way to fail is an outage.
 * </p>
 */
public interface Fetcher {

    void fetch(@NotNull RemoteFile file, @NotNull Path destination) throws IOException;
}
