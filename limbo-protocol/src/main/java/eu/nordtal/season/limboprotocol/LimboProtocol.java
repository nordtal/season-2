package eu.nordtal.season.limboprotocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The {@code nordtal:limbo} plugin-message channel: its name, wire format, encoder and decoder.
 * A message is a version byte, a type byte and a body; the proxy must reject one arriving from a player connection.
 */
public final class LimboProtocol {

    /** The channel both sides register. */
    public static final String CHANNEL = "nordtal:limbo";

    /** The only version that exists. A message carrying anything else is dropped. */
    public static final byte VERSION = 1;

    private static final byte TYPE_WAIT = 1;
    private static final byte TYPE_READY = 2;

    private LimboProtocol() {}

    /** The two things either side can say. */
    public enum Type {

        /** Proxy to {@code limbo}: show this reason's title until told otherwise. */
        WAIT,

        /** {@code limbo} to the proxy: this player has arrived and finished loading. */
        READY
    }

    /**
     * One decoded message.
     *
     * @param type   which message it is
     * @param reason the reason a {@link Type#WAIT} carries; always {@code null} for
     *               {@link Type#READY}
     */
    public record Message(Type type, @Nullable WaitReason reason) {

        public Message {
            Objects.requireNonNull(type, "type");
            if ((type == Type.WAIT) != (reason != null)) {
                throw new IllegalArgumentException(
                        "WAIT is the only message with a reason, got " + type + " / " + reason);
            }
        }
    }

    /** Returns the bytes telling the backend holding the player what the waiting room should say. */
    public static byte[] wait(final WaitReason reason) {
        Objects.requireNonNull(reason, "reason");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeByte(TYPE_WAIT);
            out.writeUTF(reason.name());
        } catch (final IOException impossible) {
            // ByteArrayOutputStream does no I/O; rethrown so a change cannot return half a message.
            throw new UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    /** Returns the bytes {@code limbo} sends once the player is in the waiting room and loaded. */
    public static byte[] ready() {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(2);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeByte(TYPE_READY);
        } catch (final IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    /**
     * Reads a message off the wire and never throws, since the bytes come from a socket.
     *
     * @param data the payload of the plugin message, may be {@code null}
     * @return the message, or empty when the payload is truncated or names an unknown version, type or reason
     */
    public static Optional<Message> decode(final byte @Nullable [] data) {
        if (data == null || data.length < 2) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readByte() != VERSION) {
                return Optional.empty();
            }
            return switch (in.readByte()) {
                case TYPE_WAIT -> WaitReason.parse(in.readUTF()).map(reason -> new Message(Type.WAIT, reason));
                case TYPE_READY -> Optional.of(new Message(Type.READY, null));
                default -> Optional.empty();
            };
        } catch (final IOException truncated) {
            return Optional.empty();
        }
    }
}
