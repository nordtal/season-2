package eu.nordtal.s2.stewardagent.measure;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** How much of the disk one service's volume takes, by {@code du -sk}, asked fresh on every call. */
public final class VolumeSizes {

    private static final Logger log = LoggerFactory.getLogger(VolumeSizes.class);

    /** A compose service name, so a path segment cannot climb out of the volumes. */
    private static final Pattern SERVICE = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final @Nullable Path volumes;

    /** @param volumes where each service's volume is mounted under its name, or {@code null} for none */
    public VolumeSizes(final @Nullable Path volumes) {
        this.volumes = volumes;
    }

    /** {@code GET} {@link AgentWire#DISK}: a {@code 404} for a service without a volume here. */
    public void route(final Context ctx) {
        final String service = ctx.pathParam("service");
        final Path root = volumes;
        if (root == null || !SERVICE.matcher(service).matches() || !Files.isDirectory(root.resolve(service))) {
            throw new NotFoundResponse("no volume of " + service + " is mounted here");
        }
        final OptionalLong bytes = du(root.resolve(service));
        ctx.json(new AgentWire.Disk(bytes.isPresent() ? bytes.getAsLong() : null));
    }

    /** {@code du -sk}, in bytes; empty when it fails or takes longer than half a minute. */
    static OptionalLong du(final Path path) {
        try {
            final Process process = new ProcessBuilder("du", "-sk", path.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("du on {} took longer than 30 s and was stopped", path);
                return OptionalLong.empty();
            }
            // du exits 1 when a file vanished under it, which a running server does all the time; the total stands.
            final String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            final int tab = out.indexOf('\t');
            if (tab <= 0) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(Long.parseLong(out.substring(0, tab)) * 1024L);
        } catch (IOException | NumberFormatException failed) {
            log.warn("du on {} failed: {}", path, failed.toString());
            return OptionalLong.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return OptionalLong.empty();
        }
    }
}
