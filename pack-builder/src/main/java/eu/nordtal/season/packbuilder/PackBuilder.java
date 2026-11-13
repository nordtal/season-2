package eu.nordtal.season.packbuilder;

import eu.nordtal.season.packbuilder.vanilla.VanillaFacts;
import eu.nordtal.season.packbuilder.wheel.WheelPack;
import eu.nordtal.season.packbuilder.zip.ReproducibleZip;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Builds the resource pack: the wheel's files for a prize list, merged with the pack's source folders into one zip.
 * Its bytes depend on its inputs alone, so the same inputs give the same SHA-1.
 */
public final class PackBuilder {

    /** How many turn variants the wheel holds. */
    public static final int VARIANTS = 8;

    private PackBuilder() {}

    /** The wheel's files for {@code prizes}, each path a path in the pack. */
    public static Map<String, byte[]> wheel(
            final VanillaFacts facts, final List<WheelPack.Prize> prizes, final Map<String, String> labels) {
        final WheelPack pack = new WheelPack(facts, prizes, labels);
        final Map<String, byte[]> out = new TreeMap<>(pack.statics());
        out.putAll(pack.items(WheelPack.offsets(VARIANTS)));
        return out;
    }

    /** The wheel's timetable, which the plugin reads as {@code nordtal/wheel/wheel.properties}. */
    public static String timetable() {
        return WheelPack.timetable(WheelPack.offsets(VARIANTS));
    }

    /** The pack zip of every file under {@code roots} and of {@code generated}; no path may come twice. */
    public static byte[] build(final List<Path> roots, final Map<String, byte[]> generated) {
        final Map<String, byte[]> files = new TreeMap<>();
        for (final Path root : roots) {
            files(root).forEach((path, bytes) -> add(files, path, bytes));
        }
        generated.forEach((path, bytes) -> add(files, path, bytes));
        return ReproducibleZip.write(files);
    }

    private static void add(final Map<String, byte[]> files, final String path, final byte[] bytes) {
        if (files.putIfAbsent(path, bytes) != null) {
            throw new IllegalArgumentException("the pack holds " + path + " twice");
        }
    }

    /** Every file under {@code root}, by its path from {@code root} with forward slashes. */
    public static Map<String, byte[]> files(final Path root) {
        final Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (final Path path : paths.filter(Files::isRegularFile).toList()) {
                final String name = root.relativize(path).toString().replace('\\', '/');
                out.put(name, Files.readAllBytes(path));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
