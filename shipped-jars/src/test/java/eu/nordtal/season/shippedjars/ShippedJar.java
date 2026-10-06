package eu.nordtal.season.shippedjars;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * One shipped jar, loaded the way its host loads it: the jar and what the host provides, and nothing of this test.
 *
 * The loader's parent is the platform loader, so a class the jar forgot to carry is as missing here as on a server.
 */
final class ShippedJar implements AutoCloseable {

    /** Our own code, the only classes whose references are held against the jar. */
    private static final String OURS = "eu/nordtal/";

    /** A string that names a class, such as the driver a pool is opened with by name. */
    private static final Pattern CLASS_NAME =
            Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)+\\.[A-Z][A-Za-z0-9_$]*");

    /** The entry class a plugin descriptor names: Paper's {@code main:} line and Velocity's {@code "main"} member. */
    private static final Pattern MAIN =
            Pattern.compile("^main:\\s*\"?([A-Za-z0-9_.$]+)|\"main\"\\s*:\\s*\"([A-Za-z0-9_.$]+)\"", Pattern.MULTILINE);

    /** What only the server's own jar holds, which Paper's API does not and a build cannot fetch. */
    private static final List<String> SERVER_INTERNALS = List.of("org/bukkit/craftbukkit/", "net/minecraft/");

    private final String name;
    private final Path jar;
    private final URLClassLoader loader;

    private ShippedJar(final String name, final Path jar, final URLClassLoader loader) {
        this.name = name;
        this.jar = jar;
        this.loader = loader;
    }

    /** Opens the jar {@code shipped.<name>.jar} names, with the host classpath {@code shipped.<name>.host} holds. */
    static ShippedJar named(final String name) throws MalformedURLException {
        final Path jar = Path.of(System.getProperty("shipped." + name + ".jar"));
        final List<URL> urls = new ArrayList<>();
        urls.add(jar.toUri().toURL());
        final String host = System.getProperty("shipped." + name + ".host", "");
        for (final String entry : host.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        return new ShippedJar(
                name, jar, new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()));
    }

    String name() {
        return name;
    }

    /**
     * Every class that a class of ours names, by reference or by a string, and that neither the jar nor its host has.
     *
     * Each answer reads {@code class names missing}.
     */
    List<String> missingClasses() throws IOException {
        final TreeSet<String> missing = new TreeSet<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (final var entries = file.entries(); entries.hasMoreElements(); ) {
                final var entry = entries.nextElement();
                final String path = entry.getName();
                if (!path.startsWith(OURS) || !path.endsWith(".class")) {
                    continue;
                }
                final var model =
                        ClassFile.of().parse(file.getInputStream(entry).readAllBytes());
                for (final PoolEntry pooled : model.constantPool()) {
                    final String named = namedBy(pooled);
                    if (named != null && !present(named)) {
                        missing.add(path.substring(0, path.length() - ".class".length()) + " names " + named);
                    }
                }
            }
        }
        return List.copyOf(missing);
    }

    /** The entry classes a descriptor or the manifest names that neither the jar nor its host carries. */
    List<String> missingEntryPoints() throws IOException {
        final List<String> missing = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            final List<String> entryPoints = new ArrayList<>();
            for (final String descriptor : List.of("paper-plugin.yml", "velocity-plugin.json")) {
                final var entry = file.getEntry(descriptor);
                if (entry != null) {
                    final Matcher matcher =
                            MAIN.matcher(new String(file.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        entryPoints.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
                    }
                }
            }
            final String application = file.getManifest() == null
                    ? null
                    : file.getManifest().getMainAttributes().getValue("Main-Class");
            if (application != null) {
                entryPoints.add(application);
            }
            if (entryPoints.isEmpty()) {
                missing.add("names no entry class in a descriptor or a manifest");
            }
            for (final String entryPoint : entryPoints) {
                if (!present(entryPoint.replace('.', '/'))) {
                    missing.add(entryPoint);
                }
            }
        }
        return missing;
    }

    /**
     * Opens the jar's own pool on the database and runs one query, as the jar does when it starts.
     *
     * The context loader is the jar's, as a server hands it to a plugin; the test's own carries a driver and would
     * hide a missing one.
     */
    void queriesThrough(final String jdbcUrl, final String username, final String password) throws Exception {
        final Thread thread = Thread.currentThread();
        final ClassLoader before = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            final Class<?> spec = loader.loadClass("eu.nordtal.season.settings.DatabaseSpec");
            final InvocationHandler answers = (proxy, method, arguments) -> switch (method.getName()) {
                case "jdbcUrl" -> jdbcUrl;
                case "username" -> username;
                case "password" -> password;
                default -> InvocationHandler.invokeDefault(proxy, method, arguments);
            };
            final Object config = Proxy.newProxyInstance(loader, new Class<?>[] {spec}, answers);
            final Class<?> database = loader.loadClass("eu.nordtal.season.settings.Database");
            final Object opened =
                    database.getMethod("open", spec, String.class).invoke(null, config, "shipped-" + name);
            try {
                final DataSource pool =
                        (DataSource) database.getMethod("dataSource").invoke(opened);
                answerOne(pool);
            } finally {
                ((AutoCloseable) opened).close();
            }
        } finally {
            thread.setContextClassLoader(before);
        }
    }

    private static void answerOne(final DataSource pool) throws SQLException {
        try (Connection connection = pool.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT 1")) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new SQLException("SELECT 1 did not answer 1");
            }
        }
    }

    /** The internal name of the class a pool entry names, or null when it names none. */
    private static String namedBy(final PoolEntry pooled) {
        if (pooled instanceof ClassEntry reference) {
            String internal = reference.asInternalName();
            while (internal.startsWith("[")) {
                internal = internal.substring(1);
            }
            if (internal.startsWith("L") && internal.endsWith(";")) {
                internal = internal.substring(1, internal.length() - 1);
            }
            return internal.length() == 1 || internal.isEmpty() ? null : internal;
        }
        if (pooled instanceof StringEntry text
                && CLASS_NAME.matcher(text.stringValue()).matches()) {
            return text.stringValue().replace('.', '/');
        }
        return null;
    }

    private boolean present(final String internalName) {
        if (SERVER_INTERNALS.stream().anyMatch(internalName::startsWith)) {
            return true;
        }
        return loader.getResource(internalName + ".class") != null;
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }

    @Override
    public String toString() {
        return name;
    }
}
