package eu.nordtal.s2.common.limbo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code nordtal:limbo} plugin-message channel: its name, its wire format, and the only encoder
 * and decoder either side uses. Both ends live in this repository, and a byte format written twice
 * would drift silently - a plugin message that does not parse looks exactly like one never sent.
 *
 * <pre>
 * byte  version   always 1
 * byte  type      1 = WAIT (proxy -&gt; limbo), 2 = READY (limbo -&gt; proxy)
 * ...   body
 *
 * WAIT   body: UTF  the {@link WaitReason} constant's name
 * READY  body: empty
 * </pre>
 *
 * <p>Both directions are needed: the proxy knows the pack status, {@code limbo} knows the player has
 * arrived and finished loading, and neither fact implies the other. The release happens when both
 * halves agree.
 *
 * <p><b>A {@code READY} can be lost.</b> Nothing here retries or acknowledges, and Velocity can drop
 * one. The proxy's {@code WaitingBook} therefore accepts one whenever it arrives, in any order, and
 * releases the player after a grace period if it never does - do not make this protocol the only
 * thing between a player and a black screen.
 *
 * <p>On the proxy a message on this channel can come from a <b>client</b> as easily as from a
 * backend, and a forged {@code READY} would be a player skipping the resource pack. The sender is
 * not in the message, so the caller must reject anything whose source is a player rather than a
 * server connection. {@link #decode(byte[])} does the other half: it never throws and returns empty
 * for anything it does not recognise.
 */
public final class LimboProtocol {

    /** The channel both sides register. */
    public static final String CHANNEL = "nordtal:limbo";

    /** The only version that exists. A message carrying anything else is dropped. */
    public static final byte VERSION = 1;

    private static final byte TYPE_WAIT = 1;
    private static final byte TYPE_READY = 2;

    private LimboProtocol() {
    }

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
    public record Message(Type type, WaitReason reason) {

        public Message {
            Objects.requireNonNull(type, "type");
            if ((type == Type.WAIT) != (reason != null)) {
                throw new IllegalArgumentException(
                        "WAIT is the only message with a reason, got " + type + " / " + reason);
            }
        }
    }

    /**
     * @param reason what the waiting room should say
     * @return the bytes to send to the backend holding the player
     */
    public static byte[] wait(final WaitReason reason) {
        Objects.requireNonNull(reason, "reason");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeByte(TYPE_WAIT);
            out.writeUTF(reason.name());
        } catch (final IOException impossible) {
            // ByteArrayOutputStream does not do I/O; rethrown so a future change cannot quietly
            // start returning half a message.
            throw new UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    /**
     * @return the bytes {@code limbo} sends once the player is in the waiting room and loaded
     */
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
     * Reads a message off the wire. Never throws, whatever the bytes are: everything reaching it
     * came off a socket somebody else controls, so the failure has to be a value.
     *
     * @param data the payload of the plugin message, may be {@code null}
     * @return the message, or empty when the payload is truncated, carries another version, names
     *         a message type this build does not have, or names a {@link WaitReason} it does not
     *         have
     */
    public static Optional<Message> decode(final byte[] data) {
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
