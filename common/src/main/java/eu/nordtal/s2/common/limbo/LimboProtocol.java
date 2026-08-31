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
 * and decoder either side uses.
 *
 * <h2>Why this lives in {@code :common}</h2>
 * Both ends are in this repository and neither can be right on its own. A byte format written twice
 * - once in {@code network-control}, once in {@code limbo} - is a format that drifts on the first
 * change and fails silently, because a plugin message that does not parse looks exactly like a
 * plugin message that was never sent. {@code :common} is compiled against no platform, and a
 * {@link DataOutputStream} is a JDK type, so the protocol costs this module nothing.
 *
 * <h2>The channel</h2>
 * <b>{@code nordtal:limbo}</b>, decided 2026-09-01. docs/season-phases.md#routing asked for "a
 * plugin message on a {@code nordtal:} channel" without naming one. The name says which
 * conversation it is rather than which direction it runs in ({@code nordtal:route} would have been
 * wrong the moment the proxy started talking back), and it is a legal Minecraft identifier:
 * namespace {@code [a-z0-9_.-]}, value {@code [a-z0-9/._-]}.
 *
 * <h2>The format</h2>
 * <pre>
 * byte  version   always 1
 * byte  type      1 = WAIT (proxy -> limbo), 2 = READY (limbo -> proxy)
 * ...   body
 *
 * WAIT   body: UTF  the {@link WaitReason} constant's name
 * READY  body: empty
 * </pre>
 * Two messages, one byte of version, and no room for a field nobody reads. It is
 * {@link DataOutputStream}'s format because that is what both platforms' plugin-message APIs hand
 * you a stream for, not because anything here needs to be compact - the whole conversation is two
 * packets per login.
 *
 * <h2>Both directions, and why READY is not redundant</h2>
 * The proxy knows the pack status; {@code limbo} knows the player has actually arrived and finished
 * loading. Neither fact implies the other, and releasing a player on the pack status alone would
 * mean connecting them onward while their client is still joining the waiting room. So:
 * {@code limbo} sends {@link #ready()} once per join, the proxy sends {@link #wait(WaitReason)}
 * whenever the reason changes, and the release happens when both halves agree.
 *
 * <h2>What a decoder must not trust</h2>
 * On the proxy, a plugin message on this channel can arrive from a <b>client</b> as easily as from
 * a backend - registering a channel makes the proxy advertise it to the client, and a modded client
 * can write any bytes it likes onto it. A forged {@code READY} would be a player releasing
 * themselves from the waiting room, which is to say skipping the resource pack. This class cannot
 * defend against that (the sender is not in the message), so <b>the caller must reject any message
 * whose source is a player rather than a server connection</b>; {@code PackStation} does.
 * {@link #decode(byte[])} does the other half: it never throws, and returns empty for anything it
 * does not recognise, because a malformed message on a network boundary is data, not a bug.
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
            // ByteArrayOutputStream does not do I/O. Rethrown rather than swallowed so that a
            // future change to this method cannot quietly start returning half a message.
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
     * Reads a message off the wire.
     * <p>
     * Never throws, whatever the bytes are. Everything that reaches this method came off a socket
     * somebody else controls; the only correct response to nonsense is to ignore it, and the only
     * way to guarantee that at every call site is for the failure to be a value.
     * </p>
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
