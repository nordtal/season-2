package eu.nordtal.s2.steward.worker.plan;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * The resource pack the proxy offers, read out of {@code pack.yml} in the {@code proxy} volume.
 *
 * Read with SnakeYAML, since jcore creates a missing file; every scalar is text, so a numeric hash stays one.
 */
public record PackState(
        boolean present, @Nullable String url, @Nullable String sha1) {

    /** The Velocity plugin id, which is the name of its data directory under {@code plugins/}. */
    public static final String PLUGIN_ID = "proxy";

    public static Path fileIn(final Path proxyVolume) {
        return proxyVolume.resolve(Installation.PLUGINS).resolve(PLUGIN_ID).resolve("pack.yml");
    }

    /** Never creates or rewrites the file, and treats an unreadable one as absent with a reason. */
    public static PackState read(final Path proxyVolume) throws IOException {
        final Path file = fileIn(proxyVolume);
        if (!Files.isRegularFile(file)) {
            return new PackState(false, null, null);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            final Object loaded = textOnlyYaml().load(reader);
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new IOException(file + " is not a YAML mapping");
            }
            return new PackState(true, text(map.get("url")), text(map.get("sha1")));
        }
    }

    /** A YAML parser that resolves only {@code null}, so every other scalar arrives as text. */
    private static Yaml textOnlyYaml() {
        final LoaderOptions loading = new LoaderOptions();
        final DumperOptions dumping = new DumperOptions();
        final Resolver textOnly = new Resolver() {
            @Override
            protected void addImplicitResolvers() {
                addImplicitResolver(Tag.NULL, EMPTY, null);
                addImplicitResolver(Tag.NULL, NULL, "~nN\u0000");
                addImplicitResolver(Tag.MERGE, MERGE, "<");
            }
        };
        return new Yaml(new SafeConstructor(loading), new Representer(dumping), dumping, loading, textOnly);
    }

    private static @Nullable String text(final @Nullable Object value) {
        if (value == null) {
            return null;
        }
        final String string = value.toString().strip();
        return string.isEmpty() ? null : string;
    }
}
