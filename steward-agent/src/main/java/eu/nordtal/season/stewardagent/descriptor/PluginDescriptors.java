package eu.nordtal.season.stewardagent.descriptor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.stewardagent.bundles.ImageJars;
import eu.nordtal.season.stewardagent.bundles.ServiceJar;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code nordtal-plugin.json} of every jar of ours: whose settings a group is, and which editor draws it.
 *
 * Jars beside a service's data first, else its image, asked once per image reference without a descriptor.
 */
public final class PluginDescriptors {

    /** Where the build puts the descriptor in a jar; {@code nordtal.plugin-descriptor} writes it. */
    public static final String ENTRY = "nordtal-plugin.json";

    private static final Logger LOG = LoggerFactory.getLogger(PluginDescriptors.class);

    private final Path configs;
    private final ImageJars images;
    private final Supplier<List<Service>> services;
    private final StampCache<Raw> jars = new StampCache<>(PluginDescriptors::readJar);
    private final Set<String> imagesWithout = ConcurrentHashMap.newKeySet();

    /**
     * @param configs one directory per service, holding its plugins' jars or a whole service's data
     * @param images where a service's own jar is found when nothing under the configs mount names it
     * @param services every service of the project and its image, as compose.yml names them
     */
    public PluginDescriptors(final Path configs, final ImageJars images, final Supplier<List<Service>> services) {
        this.configs = configs;
        this.images = images;
        this.services = services;
    }

    /** Every descriptor found, one per id, under the service named like it where two run the same jar. */
    public List<AgentWire.Descriptor> read() {
        final Map<String, AgentWire.Descriptor> byId = new TreeMap<>();
        for (final Service service : services.get()) {
            for (final Found found : of(service)) {
                final Raw raw = found.raw();
                final AgentWire.Descriptor descriptor =
                        new AgentWire.Descriptor(service.name(), raw.id(), raw.editors());
                byId.merge(raw.id(), descriptor, (kept, next) -> next.id().equals(next.service()) ? next : kept);
            }
        }
        return List.copyOf(byId.values());
    }

    /** Every jar that carries a descriptor, on every service, which is where the message bundles are read too. */
    public List<ServiceJar> jars() {
        final List<ServiceJar> jars = new ArrayList<>();
        for (final Service service : services.get()) {
            for (final Found found : of(service)) {
                jars.add(new ServiceJar(
                        service.name(),
                        found.jar(),
                        found.ownImage(),
                        found.raw().followsMessages()));
            }
        }
        return List.copyOf(jars);
    }

    private List<Found> of(final Service service) {
        final List<Found> found = new ArrayList<>();
        final Path directory = configs.resolve(service.name());
        if (Files.isDirectory(directory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
                for (final Path jar : stream) {
                    final Raw raw = jars.of(jar);
                    if (raw != null) {
                        found.add(new Found(jar, false, raw));
                    }
                }
            } catch (final IOException e) {
                LOG.warn("{} could not be listed for descriptors: {}", directory, e.getMessage());
            }
        }
        final String image = service.name() + "@" + service.image();
        if (found.isEmpty() && !imagesWithout.contains(image)) {
            // ImageJars keeps its copy by image id, so asking again for an image that has one is cheap and current.
            final Path jar;
            try {
                jar = images.jarOf(service.name());
            } catch (final RuntimeException unreachable) {
                // The daemon not answering says nothing about the image, so it is asked again next time.
                LOG.debug("{}'s image could not be asked for its jar: {}", service.name(), unreachable.getMessage());
                return found;
            }
            final Raw raw = jar == null ? null : jars.of(jar);
            if (jar == null || raw == null) {
                imagesWithout.add(image);
            } else {
                found.add(new Found(jar, true, raw));
            }
        }
        return found;
    }

    /** The descriptor in the jar, or {@code null} for a jar that carries none or a broken one, which is logged. */
    static @Nullable Raw readJar(final Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            final ZipEntry entry = zip.getEntry(ENTRY);
            if (entry == null) {
                return null;
            }
            final JsonObject json;
            try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                json = Json.decode(reader, JsonObject.class);
            }
            final String id = text(json, "id");
            if (id == null) {
                LOG.warn("{}: {} names no id and is left out", jar, ENTRY);
                return null;
            }
            final Map<String, String> editors = new LinkedHashMap<>();
            if (json.get("editors") instanceof JsonObject declared) {
                for (final Map.Entry<String, JsonElement> editor : declared.entrySet()) {
                    if (editor.getValue().isJsonPrimitive()) {
                        editors.put(editor.getKey(), editor.getValue().getAsString());
                    }
                }
            }
            final boolean followsMessages = json.get("messages") instanceof JsonPrimitive messages
                    && messages.isBoolean()
                    && messages.getAsBoolean();
            final boolean local =
                    json.get("local") instanceof JsonPrimitive flag && flag.isBoolean() && flag.getAsBoolean();
            return new Raw(id, Map.copyOf(editors), followsMessages, local);
        } catch (final IOException | JsonParseException | IllegalStateException e) {
            LOG.warn("{} could not be read for its descriptor: {}", jar, e.getMessage());
            return null;
        }
    }

    private static @Nullable String text(final @Nullable JsonObject json, final String member) {
        final JsonElement value = json == null ? null : json.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /**
     * One service of the project.
     *
     * @param image its image reference after interpolation, or {@code null} for one that only builds
     */
    public record Service(String name, @Nullable String image) {}

    /** One descriptor as the jar has it; {@code local} for a jar built outside a release. */
    record Raw(String id, Map<String, String> editors, boolean followsMessages, boolean local) {}

    private record Found(Path jar, boolean ownImage, Raw raw) {}
}
