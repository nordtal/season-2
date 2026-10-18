package eu.nordtal.season.stewardagent.descriptor;

import eu.nordtal.season.stewardagent.plan.Installation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The jars in a service's volume that were built outside a release, as their {@code nordtal-plugin.json} says.
 *
 * Only our own jars carry a descriptor, read the way {@link PluginDescriptors} reads it and again only when it changes.
 */
public final class LocalJars {

    private static final Logger LOG = LoggerFactory.getLogger(LocalJars.class);

    private final @Nullable Path volumesRoot;
    private final StampCache<Boolean> descriptors = new StampCache<>(jar -> {
        final PluginDescriptors.Raw raw = PluginDescriptors.readJar(jar);
        return raw != null && raw.local();
    });

    public LocalJars(final @Nullable Path volumesRoot) {
        this.volumesRoot = volumesRoot;
    }

    /**
     * Returns the sorted file names of the local jars in {@code plugins/} and {@code .server/}, none on doubt.
     */
    public List<String> of(final String service) {
        if (volumesRoot == null) {
            return List.of();
        }
        final Installation installation;
        try {
            installation = Installation.scan(service, volumesRoot.resolve(service));
        } catch (final IOException e) {
            LOG.debug("{}'s volume could not be listed for local jars: {}", service, e.getMessage());
            return List.of();
        }
        final List<String> local = new ArrayList<>();
        for (final List<Installation.Jar> group : List.of(installation.plugins(), installation.serverJars())) {
            for (final Installation.Jar jar : group) {
                if (isLocal(jar.path())) {
                    local.add(jar.fileName());
                }
            }
        }
        return local.stream().sorted().toList();
    }

    private boolean isLocal(final Path jar) {
        return Boolean.TRUE.equals(descriptors.of(jar));
    }
}
