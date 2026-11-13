package eu.nordtal.season.underground.anvil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Reads and writes NBT: a compound is a {@link LinkedHashMap}, a list an {@link NbtList}, an array stays an array. */
public final class Nbt {

    public static final byte BYTE = 1;
    public static final byte SHORT = 2;
    public static final byte INT = 3;
    public static final byte LONG = 4;
    public static final byte FLOAT = 5;
    public static final byte DOUBLE = 6;
    public static final byte BYTE_ARRAY = 7;
    public static final byte STRING = 8;
    public static final byte LIST = 9;
    public static final byte COMPOUND = 10;
    public static final byte INT_ARRAY = 11;
    public static final byte LONG_ARRAY = 12;

    private Nbt() {}

    /** Parses one uncompressed NBT document whose root is a compound. */
    public static Map<String, Object> read(final byte[] raw) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        final byte type = in.readByte();
        if (type != COMPOUND) {
            throw new IOException("The NBT root is tag " + type + ", not a compound.");
        }
        in.readUTF();
        return compound(payload(in, type));
    }

    /** Writes a compound as one uncompressed NBT document with an empty root name. */
    public static byte[] write(final Map<String, Object> root) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 16);
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(COMPOUND);
        out.writeUTF("");
        writePayload(out, root);
        out.flush();
        return bytes.toByteArray();
    }

    /** Casts a value that the caller knows is a compound. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(final Object value) {
        return (Map<String, Object>) value;
    }

    /** Returns the compound under a key, or null when the key is absent. */
    public static @Nullable Map<String, Object> child(final Map<String, Object> parent, final String key) {
        final Object value = parent.get(key);
        return value == null ? null : compound(value);
    }

    /** Returns the list under a key, or an empty list that is not part of the parent. */
    public static NbtList list(final Map<String, Object> parent, final String key) {
        final Object value = parent.get(key);
        return value == null ? new NbtList(COMPOUND) : (NbtList) value;
    }

    /** Returns the number under a key as an int, or the fallback when the key is absent. */
    public static int number(final Map<String, Object> parent, final String key, final int fallback) {
        final Object value = parent.get(key);
        return value == null ? fallback : ((Number) value).intValue();
    }

    /** Returns the text under a key, or an empty text when the key is absent. */
    public static String text(final Map<String, Object> parent, final String key) {
        final Object value = parent.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** Returns the value inside the one-entry compound that a list of mixed types wraps each element in. */
    public static Object unwrap(final Object element) {
        if (element instanceof Map<?, ?> map && map.size() == 1) {
            final Object inner = map.get("");
            if (inner != null) {
                return inner;
            }
        }
        return element;
    }

    private static Object payload(final DataInputStream in, final byte type) throws IOException {
        return switch (type) {
            case BYTE -> in.readByte();
            case SHORT -> in.readShort();
            case INT -> in.readInt();
            case LONG -> in.readLong();
            case FLOAT -> in.readFloat();
            case DOUBLE -> in.readDouble();
            case BYTE_ARRAY -> {
                final byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                yield bytes;
            }
            case STRING -> in.readUTF();
            case LIST -> listPayload(in);
            case COMPOUND -> compoundPayload(in);
            case INT_ARRAY -> {
                final int[] ints = new int[in.readInt()];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = in.readInt();
                }
                yield ints;
            }
            case LONG_ARRAY -> {
                final long[] longs = new long[in.readInt()];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = in.readLong();
                }
                yield longs;
            }
            default -> throw new IOException("Unknown NBT tag " + type + ".");
        };
    }

    private static NbtList listPayload(final DataInputStream in) throws IOException {
        final byte elementType = in.readByte();
        final int size = in.readInt();
        final NbtList list = new NbtList(elementType);
        for (int i = 0; i < size; i++) {
            list.add(payload(in, elementType));
        }
        return list;
    }

    private static Map<String, Object> compoundPayload(final DataInputStream in) throws IOException {
        final Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            final byte type = in.readByte();
            if (type == 0) {
                return map;
            }
            final String key = in.readUTF();
            map.put(key, payload(in, type));
        }
    }

    private static byte type(final Object value) {
        return switch (value) {
            case Byte _ -> BYTE;
            case Short _ -> SHORT;
            case Integer _ -> INT;
            case Long _ -> LONG;
            case Float _ -> FLOAT;
            case Double _ -> DOUBLE;
            case byte[] _ -> BYTE_ARRAY;
            case String _ -> STRING;
            case NbtList _ -> LIST;
            case Map<?, ?> _ -> COMPOUND;
            case int[] _ -> INT_ARRAY;
            case long[] _ -> LONG_ARRAY;
            default -> throw new IllegalArgumentException("No NBT tag holds a " + value.getClass() + ".");
        };
    }

    private static void writePayload(final DataOutputStream out, final Object value) throws IOException {
        switch (value) {
            case Byte b -> out.writeByte(b);
            case Short s -> out.writeShort(s);
            case Integer i -> out.writeInt(i);
            case Long l -> out.writeLong(l);
            case Float f -> out.writeFloat(f);
            case Double d -> out.writeDouble(d);
            case byte[] bytes -> {
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            case String s -> out.writeUTF(s);
            case NbtList list -> writeList(out, list);
            case Map<?, ?> map -> writeCompound(out, compound(map));
            case int[] ints -> {
                out.writeInt(ints.length);
                for (final int element : ints) {
                    out.writeInt(element);
                }
            }
            case long[] longs -> {
                out.writeInt(longs.length);
                for (final long element : longs) {
                    out.writeLong(element);
                }
            }
            default -> throw new IllegalArgumentException("No NBT tag holds a " + value.getClass() + ".");
        }
    }

    private static void writeList(final DataOutputStream out, final NbtList list) throws IOException {
        out.writeByte(list.isEmpty() ? list.elementType() : type(list.getFirst()));
        out.writeInt(list.size());
        for (final Object element : list) {
            writePayload(out, element);
        }
    }

    private static void writeCompound(final DataOutputStream out, final Map<String, Object> map) throws IOException {
        for (final Map.Entry<String, Object> entry : map.entrySet()) {
            out.writeByte(type(entry.getValue()));
            out.writeUTF(entry.getKey());
            writePayload(out, entry.getValue());
        }
        out.writeByte(0);
    }
}
