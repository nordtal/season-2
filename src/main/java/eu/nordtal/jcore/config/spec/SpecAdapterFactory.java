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
 * Vendored into jcore from io.github.revxrsal:spec:1.5
 * (https://github.com/Revxrsal/spec, sources jar from repo1.maven.org). The MIT licence
 * and copyright notice above belong to the original author and are retained as the licence
 * requires. See NOTICE for the full third-party licence text.
 *
 * Modified by nordtal.eu:
 *   - package revxrsal.spec -> eu.nordtal.jcore.config.spec
 *   - de-Lombok: @SneakyThrows removed
 *   - FIX: a list of nested specs could not be written. Gson serializes a collection element
 *     by its runtime type, which for a spec is a java.lang.reflect.Proxy class rather than
 *     the interface, so upstream's isConfigSpec() check missed it, Gson fell back to
 *     reflective serialization and failed on Proxy.h. Proxy classes are now mapped back to
 *     the spec interface they implement.
 *   - removed the @JsonAdapter support that read Gson's private 'constructorConstructor'
 *     field through a private lookup and referenced com.google.gson.internal.
 *     That reflection breaks silently on any Gson internal change and already degraded
 *     silently when it failed. Register TypeAdapters on the GsonBuilder instead.
 */
package eu.nordtal.jcore.config.spec;

import static eu.nordtal.jcore.config.spec.Specs.createDefault;
import static eu.nordtal.jcore.config.spec.Specs.isConfigSpec;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Serializes and deserializes {@code @ConfigSpec} interfaces through Gson. */
@SuppressWarnings({"unchecked"})
public final class SpecAdapterFactory implements TypeAdapterFactory {

    /** The single shared instance; this factory holds no per-Gson state. */
    public static final SpecAdapterFactory INSTANCE = new SpecAdapterFactory();

    @Override
    public <T> @Nullable TypeAdapter<T> create(final Gson gson, final TypeToken<T> type) {
        final Class<?> rawType = type.getRawType();
        if (!isConfigSpec(rawType)) {
            // Gson writes a list element by its runtime type: the generated Proxy class, not the interface.
            final Class<?> specInterface = specInterfaceOf(rawType);
            if (specInterface == null) {
                return null;
            }
            return (TypeAdapter<T>) gson.getAdapter(specInterface);
        }
        final Map<String, BoundField> fieldsMap = fieldsOf(gson, rawType);
        return new TypeAdapter<T>() {
            @Override
            public void write(final JsonWriter out, final T value) throws IOException {
                out.beginObject();
                final Map<String, Object> map = MapProxy.getInternalMap(value);
                for (final BoundField boundField : fieldsMap.values()) {
                    out.name(boundField.name);
                    final Object fieldValue = map.get(boundField.name);
                    boundField.adapter().write(out, fieldValue);
                }
                out.endObject();
            }

            @Override
            public T read(final JsonReader in) throws IOException {
                in.beginObject();
                final T proxy = (T) createDefault(rawType);
                final Map<String, Object> map = MapProxy.getInternalMap(proxy);
                while (in.hasNext()) {
                    final String name = in.nextName();
                    final BoundField field = fieldsMap.get(name);
                    if (field == null) {
                        // Unknown keys are rejected earlier, against the raw YAML tree; skip them here.
                        in.skipValue();
                    } else {
                        final Object readValue = field.adapter.read(in);
                        map.put(field.name, readValue);
                    }
                }
                in.endObject();
                return proxy;
            }
        };
    }

    /** Binds each of {@code rawType}'s own (non-proxy-handled) properties to a Gson adapter for its declared type. */
    private static Map<String, BoundField> fieldsOf(final Gson gson, final Class<?> rawType) {
        final SpecClass impl = Specs.from(rawType);
        final Map<String, BoundField> fieldsMap = new LinkedHashMap<>();
        for (final SpecProperty value : impl.properties().values()) {
            if (value.isHandledByProxy()) continue;
            final Method getter = value.getter();

            // Reject @JsonAdapter loudly here rather than silently ignoring it.
            if (getter.isAnnotationPresent(JsonAdapter.class)) {
                throw new IllegalArgumentException("@JsonAdapter on " + rawType.getName() + "#" + getter.getName()
                        + " is not supported by jcore's vendored Spec. Register the "
                        + "TypeAdapter on the GsonBuilder passed to the config loader instead.");
            }

            final TypeToken<?> fieldType = TypeToken.get(getter.getGenericReturnType());
            final BoundField field = new BoundField(value.key(), gson.getAdapter(fieldType));
            fieldsMap.put(value.key(), field);
        }
        return fieldsMap;
    }

    /**
     * The single {@code @ConfigSpec} interface {@code rawType} implements, or {@code null} if it is not a proxy.
     */
    private static @Nullable Class<?> specInterfaceOf(final Class<?> rawType) {
        if (!Proxy.isProxyClass(rawType)) {
            return null;
        }
        @Nullable Class<?> found = null;
        for (final Class<?> candidate : rawType.getInterfaces()) {
            if (isConfigSpec(candidate)) {
                if (found != null) {
                    // Two spec interfaces on one proxy would make the choice arbitrary.
                    throw new IllegalArgumentException("Proxy implements more than one @ConfigSpec interface: "
                            + found.getName() + " and " + candidate.getName());
                }
                found = candidate;
            }
        }
        return found;
    }

    private static class BoundField {
        private final String name;
        private final TypeAdapter<?> adapter;

        BoundField(final String name, final TypeAdapter<?> adapter) {
            this.name = name;
            this.adapter = adapter;
        }

        <T> TypeAdapter<T> adapter() {
            return (TypeAdapter<T>) adapter;
        }
    }
}
