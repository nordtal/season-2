package eu.nordtal.season.messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The packaged bundle format, read in one place: {@code messages/<bundle>/<language>.properties}, UTF-8.
 * A key's first text is {@code key=...}; further variants, one chosen at random, are {@code key[1]=...} and on.
 */
public final class PackagedTexts {

    /** A property name that holds a variant past the first. */
    private static final Pattern VARIANT = Pattern.compile("(.+)\\[([1-9][0-9]*)]");

    private PackagedTexts() {}

    /** Returns where a bundle's language lives on the class path. */
    public static String resource(final String bundle, final String language) {
        return "messages/" + bundle + "/" + language + ".properties";
    }

    /**
     * Reads one language of a bundle from a class loader.
     *
     * @return key to its variants, or {@code null} when the bundle has no file for the language
     * @throws UncheckedIOException  if the file cannot be read
     * @throws IllegalStateException if a variant has a gap before it or no first text
     */
    public static @Nullable Map<String, List<String>> read(
            final ClassLoader classLoader, final String bundle, final String language) {
        final String resource = resource(bundle, language);
        try (InputStream stream = classLoader.getResourceAsStream(resource)) {
            return stream == null ? null : read(stream, resource);
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot read message bundle " + resource, exception);
        }
    }

    /**
     * Reads one language's file from a stream, such as an entry of a jar.
     *
     * @param name the file, for the refusal
     * @return key to its variants, in the order of their numbers
     * @throws IllegalStateException if a variant has a gap before it or no first text
     */
    public static Map<String, List<String>> read(final InputStream stream, final String name) throws IOException {
        // Properties.load(InputStream) is ISO-8859-1 and would garble every umlaut.
        final Properties properties = new Properties();
        properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        final Map<String, TreeMap<Integer, String>> numbered = new TreeMap<>();
        properties.forEach((raw, text) -> {
            final String property = String.valueOf(raw);
            final Matcher variant = VARIANT.matcher(property);
            final String key = variant.matches() ? variant.group(1) : property;
            final int number = variant.matches() ? Integer.parseInt(variant.group(2)) : 0;
            numbered.computeIfAbsent(key, ignored -> new TreeMap<>()).put(number, String.valueOf(text));
        });
        final Map<String, List<String>> texts = new TreeMap<>();
        numbered.forEach((key, variants) -> {
            if (variants.lastKey() != variants.size() - 1) {
                throw new IllegalStateException(name + ": the variants of " + key + " are numbered " + variants.keySet()
                        + "; they start with the key itself and go on from [1] without a gap");
            }
            texts.put(key, List.copyOf(new ArrayList<>(variants.values())));
        });
        return texts;
    }

    /** Returns the key a property name stands for: itself, or the key whose variant it holds. */
    public static String keyOf(final String property) {
        final Matcher variant = VARIANT.matcher(property);
        return variant.matches() ? variant.group(1) : property;
    }

    /**
     * Returns the hash an override records of the packaged texts it replaced, so a release that changes them shows.
     *
     * @return the SHA-256 of the texts in order, each closed by a NUL, in lower-case hex
     */
    public static String hash(final List<String> texts) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every JDK has SHA-256", absent);
        }
        for (final String text : texts) {
            digest.update(text.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
