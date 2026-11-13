package eu.nordtal.season.packbuilder.vanilla;

import eu.nordtal.season.packbuilder.json.Json;
import eu.nordtal.season.packbuilder.zip.ReproducibleZip;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * What the pack reads of the vanilla client, in one small zip instead of the client jar.
 * It holds every item definition as the jar has it, every model those definitions reach with its parents reduced to
 * {@code parent} and {@code display.gui}, and the font's {@code ascii.png}, each at its path in the jar.
 */
public final class VanillaFacts {

    /** The folder of the item definitions. */
    public static final String ITEMS = "assets/minecraft/items/";

    /** Vanilla's font sheet, 16 by 16 cells of 8 pixels. */
    public static final String ASCII = "assets/minecraft/textures/font/ascii.png";

    private final Map<String, byte[]> files;

    private VanillaFacts(final Map<String, byte[]> files) {
        this.files = files;
    }

    /** The file at {@code path}, if the facts hold it. */
    public Optional<byte[]> file(final String path) {
        return Optional.ofNullable(files.get(path));
    }

    /** Reads the facts that {@link #extract} wrote. */
    public static VanillaFacts read(final Path facts) {
        final Map<String, byte[]> files = new TreeMap<>();
        try (InputStream in = Files.newInputStream(facts);
                ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                files.put(entry.getName(), zip.readAllBytes());
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return new VanillaFacts(files);
    }

    /** The facts of the client jar {@code jar}, as the bytes of their zip. */
    public static byte[] extract(final Path jar) {
        final Map<String, byte[]> out = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            final Deque<String> refs = new ArrayDeque<>();
            zip.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> name.startsWith(ITEMS) && name.endsWith(".json"))
                    .forEach(name -> {
                        final byte[] bytes = bytes(zip, name).orElseThrow();
                        out.put(name, bytes);
                        collect(Json.parse(bytes), refs);
                    });
            final Set<String> seen = new HashSet<>();
            while (!refs.isEmpty()) {
                final String path = modelPath(refs.pop());
                if (!seen.add(path)) {
                    continue;
                }
                final Optional<byte[]> model = bytes(zip, path);
                if (model.isPresent()) {
                    final Map<String, Object> reduced = reduced(Json.parse(model.get()));
                    if (reduced.get("parent") instanceof final String parent) {
                        refs.push(parent);
                    }
                    out.put(path, Json.bytes(reduced));
                }
            }
            out.put(
                    ASCII,
                    bytes(zip, ASCII).orElseThrow(() -> new IllegalStateException("the client jar has no " + ASCII)));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return ReproducibleZip.write(out);
    }

    /** The path in the jar of the model {@code ref}, whose namespace defaults to {@code minecraft}. */
    public static String modelPath(final String ref) {
        final int colon = ref.indexOf(':');
        final String namespace = colon >= 0 ? ref.substring(0, colon) : "minecraft";
        return "assets/" + namespace + "/models/" + ref.substring(colon + 1) + ".json";
    }

    private static Map<String, Object> reduced(final Object model) {
        final Map<String, Object> out = new LinkedHashMap<>();
        if (!(model instanceof final Map<?, ?> map)) {
            return out;
        }
        if (map.get("parent") instanceof final String parent) {
            out.put("parent", parent);
        }
        if (map.get("display") instanceof final Map<?, ?> display
                && display.get("gui") instanceof final Map<?, ?> gui) {
            out.put("display", Map.of("gui", gui));
        }
        return out;
    }

    /** Every model a definition's node names: a model's own, a special model's base. */
    private static void collect(final Object node, final Deque<String> refs) {
        if (node instanceof final Map<?, ?> map) {
            if (map.get("type") instanceof final String type) {
                final String kind = type.startsWith("minecraft:") ? type.substring("minecraft:".length()) : type;
                if (kind.equals("model") && map.get("model") instanceof final String ref) {
                    refs.push(ref);
                }
                if (kind.equals("special") && map.get("base") instanceof final String ref) {
                    refs.push(ref);
                }
            }
            map.values().forEach(value -> collect(value, refs));
        } else if (node instanceof final List<?> list) {
            list.forEach(value -> collect(value, refs));
        }
    }

    private static Optional<byte[]> bytes(final ZipFile zip, final String path) {
        final ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return Optional.empty();
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return Optional.of(in.readAllBytes());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
