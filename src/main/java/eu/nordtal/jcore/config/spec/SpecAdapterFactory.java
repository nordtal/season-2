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
import org.jetbrains.annotations.NotNull;

@SuppressWarnings({"unchecked"})
public final class SpecAdapterFactory implements TypeAdapterFactory {

    public static final SpecAdapterFactory INSTANCE = new SpecAdapterFactory();

    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        Class<?> rawType = type.getRawType();
        if (!isConfigSpec(rawType)) {
            // Gson writes a collection element by its runtime type. For a nested spec inside a
            // List that is the generated Proxy class, not the interface, so without this the
            // element falls through to Gson's reflective adapter and fails on Proxy.h.
            Class<?> specInterface = specInterfaceOf(rawType);
            if (specInterface == null) {
                return null;
            }
            return (TypeAdapter<T>) gson.getAdapter(specInterface);
        }
        SpecClass impl = Specs.from(rawType);
        Map<String, BoundField> fieldsMap = new LinkedHashMap<>();
        for (SpecProperty value : impl.properties().values()) {
            if (value.isHandledByProxy()) continue;
            Method getter = value.getter();

            // Upstream Spec honoured @JsonAdapter here by reflecting into Gson's private
            // 'constructorConstructor' field. That is removed - see the header. Reject the
            // annotation loudly rather than ignoring it, so nobody assumes it took effect.
            if (getter.isAnnotationPresent(JsonAdapter.class)) {
                throw new IllegalArgumentException("@JsonAdapter on " + rawType.getName() + "#" + getter.getName()
                        + " is not supported by jcore's vendored Spec. Register the "
                        + "TypeAdapter on the GsonBuilder passed to the config loader instead.");
            }

            TypeToken<?> fieldType = TypeToken.get(getter.getGenericReturnType());
            BoundField field = new BoundField(value.key(), gson.getAdapter(fieldType));
            fieldsMap.put(value.key(), field);
        }

        return new TypeAdapter<T>() {
            @Override
            public void write(JsonWriter out, T value) throws IOException {
                out.beginObject();
                Map<String, Object> map = MapProxy.getInternalMap(value);
                for (BoundField boundField : fieldsMap.values()) {
                    out.name(boundField.name);
                    Object fieldValue = map.get(boundField.name);
                    boundField.adapter().write(out, fieldValue);
                }
                out.endObject();
            }

            @Override
            public T read(JsonReader in) throws IOException {
                in.beginObject();
                T proxy = (T) createDefault(rawType);
                Map<String, Object> map = MapProxy.getInternalMap(proxy);
                while (in.hasNext()) {
                    String name = in.nextName();
                    BoundField field = fieldsMap.get(name);
                    if (field == null) {
                        // Unknown keys are skipped here on purpose. jcore rejects them earlier,
                        // against the raw YAML tree, where the full key path is still known -
                        // see UnknownKeyDetector.
                        in.skipValue();
                    } else {
                        Object readValue = field.adapter.read(in);
                        map.put(field.name, readValue);
                    }
                }
                in.endObject();
                return proxy;
            }
        };
    }

    /**
     * The single {@code @ConfigSpec} interface a generated proxy class implements, or
     * {@code null} if this is not such a proxy.
     */
    private static Class<?> specInterfaceOf(Class<?> rawType) {
        if (!Proxy.isProxyClass(rawType)) {
            return null;
        }
        Class<?> found = null;
        for (Class<?> candidate : rawType.getInterfaces()) {
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
        private final @NotNull String name;
        private final @NotNull TypeAdapter<?> adapter;

        BoundField(@NotNull String name, @NotNull TypeAdapter<?> adapter) {
            this.name = name;
            this.adapter = adapter;
        }

        public @NotNull <T> TypeAdapter<T> adapter() {
            return (TypeAdapter<T>) adapter;
        }
    }
}
