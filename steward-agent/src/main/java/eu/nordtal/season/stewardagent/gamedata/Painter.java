package eu.nordtal.season.stewardagent.gamedata;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Names the drawing code: a hash of every class of this package that draws, so a fix to any of them is a new painter.
 *
 * A sheet records the painter that drew it, and {@link GameAssets} draws again when it is not the running one.
 */
final class Painter {

    /** The classes of this package that fetch, schedule or name, and so change nothing in a drawing. */
    private static final Set<String> NOT_DRAWING = Set.of("GameAssets", "MojangClient", "ClientJars", "Painter");

    /** How many hex digits of the hash are kept. */
    private static final int LENGTH = 16;

    private static final class Running {
        private static final String ID = identify(drawingClasses());
    }

    private Painter() {}

    /** The identity of the painter this process runs, stable for one build of the classes. */
    static String id() {
        return Running.ID;
    }

    /** The hash of the named class files, in name order. */
    static String identify(final Map<String, byte[]> classes) {
        final MessageDigest digest = digest();
        new TreeMap<>(classes).forEach((name, bytes) -> {
            digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(bytes);
        });
        return HexFormat.of().formatHex(digest.digest()).substring(0, LENGTH);
    }

    /** The class files of this package that draw, by file name, from the directory or jar they load from. */
    static Map<String, byte[]> drawingClasses() {
        final URL here = Painter.class.getResource("Painter.class");
        if (here == null) {
            throw new IllegalStateException("The painter's own class file cannot be found");
        }
        final Map<String, byte[]> classes = new TreeMap<>();
        try {
            if (here.getProtocol().equals("jar")) {
                final JarURLConnection connection = (JarURLConnection) here.openConnection();
                connection.setUseCaches(false);
                final String folder = Painter.class.getPackageName().replace('.', '/') + "/";
                try (JarFile jar = connection.getJarFile()) {
                    for (final JarEntry entry : Collections.list(jar.entries())) {
                        final String name = entry.getName();
                        if (name.startsWith(folder) && name.indexOf('/', folder.length()) < 0) {
                            try (InputStream in = jar.getInputStream(entry)) {
                                keep(classes, name.substring(folder.length()), in.readAllBytes());
                            }
                        }
                    }
                }
            } else {
                final Path folder =
                        Path.of(java.net.URI.create(here.toString())).getParent();
                try (Stream<Path> files = Files.list(folder)) {
                    for (final Path file : (Iterable<Path>) files::iterator) {
                        keep(classes, file.getFileName().toString(), Files.readAllBytes(file));
                    }
                }
            }
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("The painter's classes cannot be read", unreadable);
        }
        return classes;
    }

    private static void keep(final Map<String, byte[]> classes, final String file, final byte[] bytes) {
        if (!file.endsWith(".class")) {
            return;
        }
        final String name = file.substring(0, file.length() - ".class".length());
        final int nested = name.indexOf('$');
        if (!NOT_DRAWING.contains(nested < 0 ? name : name.substring(0, nested))) {
            classes.put(file, bytes);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every JDK has SHA-256", impossible);
        }
    }
}
