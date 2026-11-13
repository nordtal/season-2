package eu.nordtal.season.packbuilder;

import eu.nordtal.season.packbuilder.json.Json;
import eu.nordtal.season.packbuilder.vanilla.VanillaFacts;
import eu.nordtal.season.packbuilder.wheel.WheelPack;
import eu.nordtal.season.packbuilder.zip.ReproducibleZip;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The pack builder's command line, one subcommand a step.
 * {@code facts <client jar> <facts>}, {@code timetable <file>}, {@code wheel <facts> <prizes> <texts> <folder>},
 * {@code zip <facts> <prizes> <texts> <zip> <root>...} and {@code sha1 <file> <sha1 file>}.
 */
public final class PackBuilderMain {

    private PackBuilderMain() {}

    public static void main(final String[] args) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("no subcommand: facts, timetable, wheel, zip or sha1");
        }
        final List<Path> paths = Arrays.stream(args).skip(1).map(Path::of).toList();
        switch (args[0]) {
            case "facts" -> write(paths.get(1), VanillaFacts.extract(paths.get(0)));
            case "timetable" -> write(paths.get(0), PackBuilder.timetable().getBytes(StandardCharsets.UTF_8));
            case "wheel" -> wheel(paths);
            case "zip" -> zip(paths);
            case "sha1" ->
                write(
                        paths.get(1),
                        (ReproducibleZip.sha1(Files.readAllBytes(paths.get(0))) + "\n")
                                .getBytes(StandardCharsets.UTF_8));
            default -> throw new IllegalArgumentException("no subcommand " + args[0]);
        }
    }

    private static Map<String, byte[]> wheelFiles(final List<Path> paths) throws IOException {
        final VanillaFacts facts = VanillaFacts.read(paths.get(0));
        final List<WheelPack.Prize> prizes = WheelPack.prizes(Json.parse(Files.readAllBytes(paths.get(1))));
        final Map<String, String> labels = WheelPack.labels(Json.parse(Files.readAllBytes(paths.get(2))));
        return PackBuilder.wheel(facts, prizes, labels);
    }

    /** Writes the wheel's files into a fresh folder, for the tools that read the pack unzipped. */
    private static void wheel(final List<Path> paths) throws IOException {
        final Path folder = paths.get(3);
        clear(folder);
        for (final Map.Entry<String, byte[]> file : wheelFiles(paths).entrySet()) {
            write(folder.resolve(file.getKey()), file.getValue());
        }
    }

    private static void zip(final List<Path> paths) throws IOException {
        final List<Path> roots = new ArrayList<>(paths.subList(4, paths.size()));
        write(paths.get(3), PackBuilder.build(roots, wheelFiles(paths)));
    }

    private static void clear(final Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (final Path path : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void write(final Path path, final byte[] bytes) {
        try {
            final Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, bytes);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
