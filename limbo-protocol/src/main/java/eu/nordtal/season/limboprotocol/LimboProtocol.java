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
 * Encodes and decodes the messages of the {@code nordtal:limbo} plugin channel.
 * A message is a version byte, a type byte and a body.
 * The proxy rejects a message that arrives from a player connection.
 */
public final class LimboProtocol {

    /** The name of the channel. */
    public static final String CHANNEL = "nordtal:limbo";

    /** The only version. {@link #decode} drops a message with another version. */
    public static final byte VERSION = 1;

    private static final byte TYPE_WAIT = 1;
    private static final byte TYPE_READY = 2;

    private LimboProtocol() {}

    /** The message types. */
    public enum Type {

        /** The proxy tells {@code limbo} to show the title of a reason. */
        WAIT,

        /** {@code limbo} tells the proxy that the player has arrived and finished loading. */
        READY
    }

    /**
     * A decoded message.
     *
     * @param reason the reason of a {@link Type#WAIT}, {@code null} for {@link Type#READY}
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

    /** Returns a {@link Type#WAIT} message with the reason. */
    public static byte[] wait(final WaitReason reason) {
        Objects.requireNonNull(reason, "reason");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeByte(TYPE_WAIT);
            out.writeUTF(reason.name());
        } catch (final IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    /** Returns a {@link Type#READY} message. */
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
     * Decodes a payload and never throws.
     *
     * @return the message, or empty when the payload is {@code null}, truncated or has an unknown version, type or
     * reason
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
