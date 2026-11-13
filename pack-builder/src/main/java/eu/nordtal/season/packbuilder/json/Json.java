package eu.nordtal.season.packbuilder.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON as plain Java values, read and written so a number keeps the form it was read in.
 * An object is a {@code LinkedHashMap} in its key order, an array an {@code ArrayList}, a whole number an
 * {@code Integer}, {@code Long} or {@code BigInteger} and any other number a {@code BigDecimal}.
 */
public final class Json {

    /** JSON's {@code null}, which a map or a list cannot hold as Java's. */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    private Json() {}

    /** JSON written once and spliced in as it is, so a picture that many turns share is written once. */
    public record Raw(String text) {}

    /** Parses {@code bytes}, UTF-8. */
    public static Object parse(final byte[] bytes) {
        return parse(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
    }

    /** Parses everything {@code reader} holds. */
    public static Object parse(final Reader reader) {
        return value(JsonParser.parseReader(reader));
    }

    private static Object value(final JsonElement element) {
        if (element.isJsonNull()) {
            return NULL;
        }
        if (element instanceof final JsonObject object) {
            final Map<String, Object> out = new LinkedHashMap<>();
            object.entrySet().forEach(entry -> out.put(entry.getKey(), value(entry.getValue())));
            return out;
        }
        if (element instanceof final JsonArray array) {
            final List<Object> out = new ArrayList<>(array.size());
            array.forEach(item -> out.add(value(item)));
            return out;
        }
        final JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isString()) {
            return primitive.getAsString();
        }
        return number(primitive.getAsNumber().toString());
    }

    /** A number as its text reads: a whole number as the smallest type that holds it, any other as a decimal. */
    static Number number(final String text) {
        if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
            return new BigDecimal(text);
        }
        final BigInteger whole = new BigInteger(text);
        if (whole.bitLength() < Integer.SIZE) {
            return whole.intValue();
        }
        if (whole.bitLength() < Long.SIZE) {
            return whole.longValue();
        }
        return whole;
    }

    /** A computed number as the files write it: to 4 places, a whole one without a point, zero as 0. */
    public static Number rounded(final double value) {
        final BigDecimal d =
                BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        if (d.signum() == 0) {
            return 0;
        }
        return d.scale() <= 0 ? (Number) d.toBigInteger().intValue() : d;
    }

    /** Compact JSON of {@code value}, keys in their map's order. */
    public static String write(final Object value) {
        final StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /** {@link #write(Object)} as UTF-8 bytes. */
    public static byte[] bytes(final Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    private static void write(final Object value, final StringBuilder out) {
        switch (value) {
            case final String s -> string(s, out);
            case final Raw raw -> out.append(raw.text());
            case final BigDecimal d -> out.append(d.toPlainString());
            case final Double d -> {
                final Number n = rounded(d);
                out.append(n instanceof final BigDecimal b ? b.toPlainString() : n.toString());
            }
            case final Number n -> out.append(n);
            case final Boolean b -> out.append(b);
            case final Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (final Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string(String.valueOf(entry.getKey()), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            }
            case final List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(list.get(i), out);
                }
                out.append(']');
            }
            default -> {
                if (value != NULL) {
                    throw new IllegalArgumentException("no JSON for " + value.getClass());
                }
                out.append("null");
            }
        }
    }

    private static void string(final String s, final StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char ch = s.charAt(i);
            if (ch == '"') {
                out.append("\\\"");
            } else if (ch == '\\') {
                out.append("\\\\");
            } else if (ch < ' ') {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
            } else {
                out.append(ch);
            }
        }
        out.append('"');
    }
}
