/*
 * This file is part of Cream, licensed under the MIT License.
 *
 *  Copyright (c) Revxrsal <reflxction.github@gmail.com>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
/*
 * Vendored into jcore from io.github.revxrsal:spec:1.5 on 2026-08-30
 * (https://github.com/Revxrsal/spec, sources jar from repo1.maven.org). The MIT licence
 * and copyright notice above belong to the original author and are retained as the licence
 * requires. See NOTICE for the full third-party licence text.
 *
 * Modified by nordtal.eu:
 *   - package revxrsal.spec -> eu.nordtal.jcore.config.spec
 *   - de-Lombok: @SneakyThrows replaced with explicit IOException handling
 *   - load() now uses SnakeYAML's SafeConstructor. Upstream used a bare `new Yaml()`,
 *     which honours explicit YAML tags and can instantiate arbitrary classes from a
 *     config file.
 *   - load() rejects a document whose root is not a mapping instead of throwing a raw
 *     ClassCastException.
 *   - save() writes atomically via AtomicConfigWriter (temp file in the same directory,
 *     fsync, ATOMIC_MOVE) so an interrupted write cannot leave a truncated config behind.
 *   - save() creates the parent directory in both branches, and no longer indexes
 *     charAt(0) on a possibly empty first line.
 *   - save() split into render() + save(), so callers can compare the rendered file with
 *     what is already on disk and skip a pointless rewrite.
 */
package eu.nordtal.jcore.config.spec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import eu.nordtal.jcore.config.internal.AtomicConfigWriter;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.events.*;
import eu.nordtal.jcore.config.spec.Util.PeekingIterator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.LITERAL;

/**
 * A configuration that supports comments. Set comments with
 * {@link #setComments(Map)}
 */
public class CommentedConfiguration {

    private static final ThreadLocal<Yaml> YAML = ThreadLocal.withInitial(() -> {
        DumperOptions options = new DumperOptions();
        setProcessComments(options, false);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        // A bare `new Yaml(options)` resolves explicit YAML tags, which lets a config file
        // name arbitrary classes for SnakeYAML to instantiate. Config files are edited by
        // operators and can arrive from a mounted volume, so the loader is restricted to
        // plain YAML scalars, sequences and mappings.
        return new Yaml(new SafeConstructor(new LoaderOptions()), new Representer(options), options);
    });

    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapterFactory(SpecAdapterFactory.INSTANCE)
            .create();

    /**
     * Pattern for matching newline characters.
     */
    public static final Pattern NEW_LINE = Pattern.compile("\n", LITERAL);
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    /**
     * YAML processor instance for reading and writing YAML data.
     */
    protected final Yaml yaml;

    /**
     * A map storing comments associated with specific configuration keys.
     */
    protected final Map<String, String> configComments = new HashMap<>();

    /**
     * A map storing comments associated with specific configuration keys.
     */
    protected List<String> headers = Collections.emptyList();

    /**
     * Json instance for serializing and deserializing JSON data.
     */
    protected final Gson gson;

    /**
     * Path to the configuration file.
     */
    protected final Path file;

    /**
     * The JSON representation of the configuration data.
     */
    protected Map<String, Object> data = new LinkedHashMap<>();

    /**
     * The array commenting style
     */
    protected final ArrayCommentStyle arrayCommentStyle;

    public CommentedConfiguration(Path file, Gson gson, ArrayCommentStyle arrayCommentStyle, Yaml yaml) {
        this.file = file;
        this.gson = gson;
        this.arrayCommentStyle = arrayCommentStyle;
        this.yaml = yaml;
    }

    public CommentedConfiguration(Path file, Gson gson, ArrayCommentStyle arrayCommentStyle) {
        this(file, gson, arrayCommentStyle, YAML.get());
    }

    /**
     * Loads the content of this configuration
     */
    public void load() {
        if (!Files.exists(file)) {
            data = new LinkedHashMap<>();
            return;
        }
        Object root;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            root = yaml.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read config file " + file, e);
        }
        if (root == null) {
            data = new LinkedHashMap<>();
            return;
        }
        if (!(root instanceof Map)) {
            // An empty file yields null and is fine; anything else that is not a mapping is a
            // broken file, and the raw ClassCastException upstream threw named neither the file
            // nor what was actually found.
            throw new IllegalArgumentException(
                    "Config file " + file + " must contain a YAML mapping at its root, found "
                            + root.getClass().getSimpleName() + ".");
        }
        data = new LinkedHashMap<>((Map<String, Object>) root);
    }

    /**
     * Sets the comment of the given path.
     *
     * @param path    The comment path. Subkeys are delimited by '.', and array entries have 0
     *                as their parent.
     * @param comment The comment
     */
    public void setComment(@NotNull String path, @NotNull String comment) {
        this.configComments.put(path, comment);
    }

    /**
     * Sets the comments of this configuration file.
     *
     * @param comments The comments to set. Supports multiple lines (use \n as a spacer).
     */
    public void setComments(@NotNull Map<String, String> comments) {
        this.configComments.clear();
        this.configComments.putAll(comments);
    }

    /**
     * Saves this configuration file with comments set with {@link #setComments(Map)}.
     */
    public void save() {
        AtomicConfigWriter.write(file, render());
    }

    /**
     * Renders this configuration, with its comments and header, exactly as {@link #save()}
     * would write it - but returns it instead of writing.
     *
     * @return the complete file content
     */
    public @NotNull String render() {
        if (configComments.isEmpty()) {
            return yaml.dump(data);
        }
        String simpleDump = yaml.dump(data);
        String[] split = NEW_LINE.split(simpleDump);
        List<String> lines = new ArrayList<>(split.length);
        Collections.addAll(lines, split);
        StringReader reader = new StringReader(simpleDump);
        Iterable<Event> events = yaml.parse(reader);
        handleEvents(events.iterator(), lines); // terribly inefficient way but I can't care less lol
        if (!lines.isEmpty()) {
            String first = lines.get(0);
            // Upstream indexed charAt(0) unconditionally, which throws on an empty first line.
            if (!first.isEmpty() && Character.isWhitespace(first.charAt(0))) {
                lines.set(0, first.substring(1));
            }
        }
        for (int i = 0; i < headers.size(); i++) {
            String l = headers.get(i);
            if (l.startsWith("#"))
                lines.add(i, "#" + l);
            else
                lines.add(i, "# " + l);
        }
        if (!headers.isEmpty()) {
            lines.add(headers.size(), "");
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    /**
     * Create a config from a file
     *
     * @param file              The file to load the config from.
     * @param json              The JSON instance to deserialize with
     * @param arrayCommentStyle The array commenting style. See {@link ArrayCommentStyle}.
     * @return A new instance of CommentedConfiguration
     */
    public static @NotNull CommentedConfiguration from(
            @NotNull Path file,
            @NotNull Gson json,
            @NotNull ArrayCommentStyle arrayCommentStyle
    ) {
        //Creating a blank instance of the config.
        return new CommentedConfiguration(file, json, arrayCommentStyle);
    }

    /**
     * Create a config from a file
     *
     * @param file              The file to load the config from.
     * @param arrayCommentStyle The array commenting style. See {@link ArrayCommentStyle}.
     * @return A new instance of CommentedConfiguration
     */
    public static @NotNull CommentedConfiguration from(
            @NotNull Path file,
            @NotNull ArrayCommentStyle arrayCommentStyle
    ) {
        //Creating a blank instance of the config.
        return new CommentedConfiguration(file, GSON, arrayCommentStyle);
    }

    /**
     * Create a config from a file
     *
     * @param file The file to load the config from.
     * @param gson The JSON instance to deserialize with
     * @return A new instance of CommentedConfiguration
     */
    public static @NotNull CommentedConfiguration from(
            @NotNull Path file,
            @NotNull Gson gson
    ) {
        //Creating a blank instance of the config.
        return new CommentedConfiguration(file, gson, ArrayCommentStyle.COMMENT_FIRST_ELEMENT);
    }

    /**
     * Create a config from a file
     *
     * @param file The file to load the config from.
     * @return A new instance of CommentedConfiguration
     */
    public static @NotNull CommentedConfiguration from(@NotNull Path file) {
        //Creating a blank instance of the config.
        return from(file, GSON);
    }

    /**
     * Retrieves the value for a key and deserializes it to the specified type.
     *
     * @param key  The key to retrieve the value for.
     * @param type The type to deserialize the value into.
     * @param <T>  The type of the returned value.
     * @return The deserialized value.
     */
    public <T> T get(@NotNull String key, @NotNull Type type) {
        return fromValue(gson, data.get(key), Object.class, type);
    }


    /**
     * Deserializes the entire configuration data to the specified type.
     *
     * @param type The type to deserialize the data into.
     * @param <T>  The type of the returned value.
     * @return The deserialized data.
     */
    public <T> T getAs(@NotNull Type type) {
        return fromValue(gson, data, MAP_TYPE, type);
    }

    /**
     * Retrieves the value for a key and deserializes it to the specified class.
     *
     * @param key  The key to retrieve the value for.
     * @param type The class to deserialize the value into.
     * @param <T>  The type of the returned value.
     * @return The deserialized value.
     */
    public <T> T get(@NotNull String key, @NotNull Class<T> type) {
        return get(key, (Type) type);
    }

    /**
     * Sets a value for a key using JSON serialization.
     *
     * @param key The key to set the value for.
     * @param v   The value to set.
     */
    public void set(@NotNull String key, @Nullable Object v) {
        if (v == null)
            data.remove(key);
        else
            data.put(key, toJsonValue(gson, v, v.getClass()));
    }

    /**
     * Sets a value for a key using JSON serialization with a specific type.
     *
     * @param key  The key to set the value for.
     * @param v    The value to set.
     * @param type The type used for serialization.
     */
    public void set(@NotNull String key, @NotNull Object v, @NotNull Type type) {
        data.put(key, toJsonValue(gson, v, type));
    }

    private static Object toJsonValue(Gson gson, @NotNull Object o, @NotNull Type type) {
        String toJson = gson.toJson(o, type);
        return gson.fromJson(toJson, Object.class);
    }

    private static <T> T fromValue(Gson gson, Object o, @NotNull Type valueType, @NotNull Type javaType) {
        String toJson = gson.toJson(o, valueType);
        return gson.fromJson(toJson, javaType);
    }

    /**
     * Checks if the configuration contains a value for the given path.
     *
     * @param path The path to check.
     * @return {@code true} if the path exists, {@code false} otherwise.
     */
    public boolean contains(@NotNull String path) {
        return data.containsKey(path);
    }

    public void setHeaders(@NotNull List<String> headers) {
        this.headers = headers;
    }

    /**
     * Replaces the configuration data with the given JSON object.
     *
     * @param data The new JSON object to set.
     */
    public void setTo(@NotNull Object data, Type type) {
        Object value = toJsonValue(gson, data, type);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected data to be a map-like structure, found " + value);
        }
        //noinspection unchecked
        this.data = new LinkedHashMap<>((Map<String, Object>) value);
    }

    /**
     * Replaces the configuration data with the given JSON object.
     *
     * @param data The new JSON object to set.
     */
    public void setTo(@NotNull Object data) {
        setTo(data, data.getClass());
    }

    /**
     * Retrieves the entire configuration data as a JSON object.
     *
     * @return The configuration data.
     */
    public @UnmodifiableView Map<String, Object> getData() {
        return Collections.unmodifiableMap(data);
    }

    protected void handleEvents(Iterator<Event> eventsI, List<String> lines) {
        PeekingIterator<Event> events = PeekingIterator.from(eventsI);
        LinkedList<String> path = new LinkedList<>();
        Set<String> commentsAdded = new HashSet<>();
        boolean expectKey = true;
        boolean lastWasScalar = false;
        int offset = 0;
        while (events.hasNext()) {
            Event event = events.next();
            if (event instanceof DocumentStartEvent) {
                expectKey = true;
            }
            if (event instanceof MappingStartEvent) {
                expectKey = true;
            } else if (event instanceof MappingEndEvent) {
                path.pollLast();
                expectKey = true;
                if (events.hasNext()) {
                    Event next = events.peek();
                    if (next instanceof ScalarEvent) {
                        path.pollLast();
                    }
                }
            } else if (event instanceof ScalarEvent) {
                if (expectKey) {
                    expectKey = false;
                    if (lastWasScalar)
                        path.removeLast();
                    path.add(((ScalarEvent) event).getValue());
                } else {
                    expectKey = true;
                }
            }
            if (event instanceof SequenceStartEvent) {
                path.add(SpecClass.ARRAY_INDEX);
            } else if (event instanceof SequenceEndEvent) {
                path.pollLast();
                expectKey = true;
                if (events.hasNext()) {
                    Event next = events.peek();
                    if (next instanceof ScalarEvent) {
                        path.pollLast();
                    }
                }
            }

            lastWasScalar = event instanceof ScalarEvent;
            String commentPath = String.join(".", path);
            String comment = configComments.get(commentPath);
            if (comment != null && (commentsAdded.add(commentPath) || arrayCommentStyle == ArrayCommentStyle.COMMENT_ALL_ELEMENTS)) {
                lines.add(event.getStartMark().getLine() + (offset++), comment);
            }
        }
    }

    /**
     * Reflective access to the `setProcessComments` method in {@link DumperOptions}.
     */
    private static @Nullable Method SET_PROCESS_COMMENTS;

    static {
        try {
            // Attempt to retrieve the private `setProcessComments` method.
            SET_PROCESS_COMMENTS = DumperOptions.class.getDeclaredMethod("setProcessComments", boolean.class);
            SET_PROCESS_COMMENTS.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
            // Ignored as the method may not exist in older versions.
        }
    }

    /**
     * Sets the `processComments` flag on the given {@link DumperOptions} instance.
     *
     * @param options The {@link DumperOptions} instance.
     * @param process The value to set for `processComments`.
     */
    protected static void setProcessComments(@NotNull DumperOptions options, boolean process) {
        try {
            if (SET_PROCESS_COMMENTS != null)
                SET_PROCESS_COMMENTS.invoke(options, process);
        } catch (ReflectiveOperationException ignored) {
            // Best effort; the flag only affects how SnakeYAML re-emits comments it parsed,
            // and this class writes its comments itself.
        }
    }
}
