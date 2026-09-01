package eu.nordtal.s2.updater.plan;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The resource pack the proxy is currently offering, read out of {@code pack.yml} in the
 * {@code network-control} volume.
 *
 * <h2>Why the file and not the environment - decided 2026-09-01</h2>
 * Both values were made {@code compose.yml} variables earlier the same day, because they were
 * reachable <em>only</em> by editing this file inside a volume and that contradicted {@code .env}
 * being the whole configuration. That fix is being partly taken back, and the reason is a property
 * of jcore's config system: <b>an environment override wins over the file and is never written
 * back to it.</b> So an updater that writes a new sha1 into {@code pack.yml} while
 * {@code NORDTAL_NETWORK_CONTROL_PACK_SHA1} is set would be writing into a value nothing reads -
 * a swap that reports success and changes nothing, which is the worst outcome available.
 *
 * <p>So the file becomes the place, {@code PACK_URL} and {@code PACK_SHA1} leave
 * {@code compose.yml} and {@code .env.example} again (step 3), and the hand-copying of a hash out
 * of a release - which is what those variables replaced - goes away entirely rather than moving.
 * Until step 3 lands, the environment is still set and this reader will disagree with what the
 * proxy actually uses; that is why {@link #read} reports {@link #present} rather than pretending
 * a missing file means an empty pack.</p>
 *
 * <h2>Read with SnakeYAML, not with jcore</h2>
 * {@code ConfigLoader.load()} writes the file when it is not there. Step 1 writes nothing, so it
 * cannot be used here - and reading two strings does not need a config system.
 *
 * <h2>Every scalar is read as text, and that is not a detail</h2>
 * SnakeYAML infers types from the shape of a value, so a SHA-1 made only of digits comes back as
 * a {@code long} - and {@code 0000000000000000000000000000000000000000} then reads as {@code 0}.
 * Found on 2026-09-01 by a test that used exactly that value as its "wrong hash". Forty digits is
 * a hash nobody will ever meet, but the same coercion mangles anything numeric-looking, and the
 * damage is a comparison against a hash that was never in the file. So the implicit resolvers are
 * removed and everything arrives as a {@link String}, which is what both of these values are.
 */
public record PackState(boolean present, @Nullable String url, @Nullable String sha1) {

    /** The Velocity plugin id, which is the name of its data directory under {@code plugins/}. */
    public static final String PLUGIN_ID = "network-control";

    public static @NotNull Path fileIn(final @NotNull Path networkControlVolume) {
        return networkControlVolume.resolve(Installation.PLUGINS).resolve(PLUGIN_ID).resolve("pack.yml");
    }

    /** Never creates the file, never rewrites it, and treats an unreadable one as absent-with-a-reason. */
    public static @NotNull PackState read(final @NotNull Path networkControlVolume) throws IOException {
        final Path file = fileIn(networkControlVolume);
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

    /**
     * A YAML parser with no implicit type resolution: no ints, no floats, no booleans, no
     * timestamps. Only {@code null} is still recognised, so an empty value stays empty rather than
     * becoming the string "null".
     */
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
