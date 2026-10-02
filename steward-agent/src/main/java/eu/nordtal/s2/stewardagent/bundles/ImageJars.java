package eu.nordtal.s2.stewardagent.bundles;

import eu.nordtal.s2.stewardagent.docker.Docker;
import eu.nordtal.s2.stewardagent.docker.DockerException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The jar a service of ours runs, which is in its image and nowhere on a volume, for a bundle with no plugins folder.
 */
@FunctionalInterface
public interface ImageJars {

    /** Where every image of ours puts the one jar it runs; deploy/jvm/Dockerfile copies it there. */
    String BAKED_JAR = "/app/app.jar";

    /** Finds nothing, for a caller that only reads the configs mount. */
    ImageJars NONE = service -> null;

    /** A local copy of {@code service}'s jar, or {@code null} when it has none or no container of it runs. */
    @Nullable
    Path jarOf(String service);

    /**
     * Copies the jar out of the service's running container, once per image, into {@code cache}.
     *
     * The image id names the copy, so a new release is a new file and an old copy is never read for it.
     */
    static ImageJars fromContainers(final Docker docker, final String project, final Path cache) {
        final Logger log = LoggerFactory.getLogger(ImageJars.class);
        return service -> {
            final Docker.Container container = docker.containers(project).stream()
                    .filter(candidate -> service.equals(candidate.service()) && candidate.isRunning())
                    .findFirst()
                    .orElse(null);
            if (container == null || container.imageId() == null) {
                return null;
            }
            final String image = Objects.requireNonNull(container.imageId()).replaceFirst("^sha256:", "");
            final Path copy = cache.resolve(service + "-" + image.substring(0, Math.min(12, image.length())) + ".jar");
            if (Files.isRegularFile(copy)) {
                return copy;
            }
            try {
                Files.createDirectories(cache);
                docker.copyOut(container.id(), BAKED_JAR, copy);
                return copy;
            } catch (final IOException | DockerException failed) {
                // A Minecraft service has no baked jar; its bundles are found in its plugins folder instead.
                log.debug("{} has no {} to read bundles from: {}", service, BAKED_JAR, failed.getMessage());
                return null;
            }
        };
    }
}
