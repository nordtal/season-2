package eu.nordtal.season.smp.eye;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads one chunk's NBT into maps, lists, strings, numbers and arrays, as the game stores it in a region file. */
final class ChunkNbt {

    private static final int END = 0;
    private static final int BYTE = 1;
    private static final int SHORT = 2;
    private static final int INT = 3;
    private static final int LONG = 4;
    private static final int FLOAT = 5;
    private static final int DOUBLE = 6;
    private static final int BYTE_ARRAY = 7;
    private static final int STRING = 8;
    private static final int LIST = 9;
    private static final int COMPOUND = 10;
    private static final int INT_ARRAY = 11;
    private static final int LONG_ARRAY = 12;
    private static final int MAX_DEPTH = 512;

    private ChunkNbt() {}

    /** Returns the root compound of the first {@code length} bytes of {@code data}. */
    static Map<String, Object> read(final byte[] data, final int length) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 0, length));
        if (in.readUnsignedByte() != COMPOUND) {
            throw new IOException("The chunk's root is no compound.");
        }
        in.readUTF();
        return compound(in, 0);
    }

    private static Map<String, Object> compound(final DataInputStream in, final int depth) throws IOException {
        final Map<String, Object> values = new HashMap<>();
        while (true) {
            final int type = in.readUnsignedByte();
            if (type == END) {
                return values;
            }
            final String name = in.readUTF();
            values.put(name, value(in, type, depth + 1));
        }
    }

    private static Object value(final DataInputStream in, final int type, final int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("The chunk's NBT nests deeper than " + MAX_DEPTH + ".");
        }
        return switch (type) {
            case BYTE -> in.readByte();
            case SHORT -> in.readShort();
            case INT -> in.readInt();
            case LONG -> in.readLong();
            case FLOAT -> in.readFloat();
            case DOUBLE -> in.readDouble();
            case BYTE_ARRAY -> {
                final byte[] bytes = new byte[length(in)];
                in.readFully(bytes);
                yield bytes;
            }
            case STRING -> in.readUTF();
            case LIST -> {
                final int element = in.readUnsignedByte();
                final int size = length(in);
                final List<Object> list = new ArrayList<>(Math.min(size, 1024));
                for (int i = 0; i < size; i++) {
                    list.add(value(in, element, depth + 1));
                }
                yield list;
            }
            case COMPOUND -> compound(in, depth);
            case INT_ARRAY -> {
                final int[] ints = new int[length(in)];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = in.readInt();
                }
                yield ints;
            }
            case LONG_ARRAY -> {
                final long[] longs = new long[length(in)];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = in.readLong();
                }
                yield longs;
            }
            default -> throw new IOException("Unknown NBT tag " + type + ".");
        };
    }

    private static int length(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length < 0 || length > in.available()) {
            throw new IOException("An NBT length of " + length + " runs past the chunk.");
        }
        return length;
    }
}
