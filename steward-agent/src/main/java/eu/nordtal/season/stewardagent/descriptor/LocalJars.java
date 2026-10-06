package eu.nordtal.season.stewardagent.descriptor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.stewardagent.plan.Installation;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The jars in a service's volume that were built outside a release, as their {@code nordtal-plugin.json} says.
 *
 * Only our own jars carry a descriptor, and each is read again only when its size or modification time changes.
 */
public final class LocalJars {

    private static final Logger LOG = LoggerFactory.getLogger(LocalJars.class);

    private final @Nullable Path volumesRoot;
    private final Map<String, Read> jars = new ConcurrentHashMap<>();

    /** @param volumesRoot the services' volumes, one directory per service, or {@code null} when none are mounted */
    public LocalJars(final @Nullable Path volumesRoot) {
        this.volumesRoot = volumesRoot;
    }

    /** The file names of the service's local jars in {@code plugins/} and {@code .server/}, sorted; none on doubt. */
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
        final String stamp;
        try {
            final BasicFileAttributes attributes = Files.readAttributes(jar, BasicFileAttributes.class);
            stamp = attributes.size() + "@" + attributes.lastModifiedTime().toMillis();
        } catch (final IOException e) {
            return false;
        }
        final Read known = jars.get(jar.toString());
        if (known != null && known.stamp().equals(stamp)) {
            return known.local();
        }
        final boolean local = readFlag(jar);
        jars.put(jar.toString(), new Read(stamp, local));
        return local;
    }

    /** Whether the jar's descriptor says {@code "local": true}; a jar without one, or unreadable, is not local. */
    static boolean readFlag(final Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            final ZipEntry entry = zip.getEntry(PluginDescriptors.ENTRY);
            if (entry == null) {
                return false;
            }
            try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                final JsonElement flag = Json.decode(reader, JsonObject.class).get("local");
                return flag != null && flag.isJsonPrimitive() && flag.getAsBoolean();
            }
        } catch (final IOException | JsonParseException | IllegalStateException e) {
            LOG.debug("{} could not be read for its descriptor: {}", jar, e.getMessage());
            return false;
        }
    }

    private record Read(String stamp, boolean local) {}
}
