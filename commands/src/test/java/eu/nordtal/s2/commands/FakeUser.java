package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.Tone;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Somebody asking for a command, recorded rather than rendered. */
public final class FakeUser implements NordtalUser {

    /**
     * Everything this user was told, in order.
     *
     * Concurrent, since {@code Outbox} answers from its own threads while the test thread reads.
     */
    public final List<Reply> replies = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final Origin origin;
    private final String discordId;
    private final UUID mcUuid;

    /**
     * @param tone how the surface was asked to colour it. {@link Tone#NEUTRAL} for every reply sent
     *             without one, which is the same thing a surface that cannot colour sees
     */
    public record Reply(String key, Map<String, ?> placeholders, Tone tone) {

        public Object of(final String placeholder) {
            return placeholders.get(placeholder);
        }
    }

    private FakeUser(final Origin origin, final String discordId, final UUID mcUuid) {
        this.origin = origin;
        this.discordId = discordId;
        this.mcUuid = mcUuid;
    }

    public static FakeUser inGame() {
        return new FakeUser(Origin.GAME, "100000000000000001", UUID.fromString("11111111-2222-3333-4444-555555555555"));
    }

    public static FakeUser inDiscord() {
        return new FakeUser(Origin.DISCORD, "100000000000000002", null);
    }

    public static FakeUser console() {
        return new FakeUser(Origin.CONSOLE, null, null);
    }

    /** The keys only, which is what most assertions are actually about. */
    public List<String> keys() {
        return replies.stream().map(Reply::key).toList();
    }

    public Reply only() {
        if (replies.size() != 1) {
            throw new AssertionError("expected exactly one reply, got " + keys());
        }
        return replies.getFirst();
    }

    @Override
    public Optional<String> discordId() {
        return Optional.ofNullable(discordId);
    }

    @Override
    public Optional<UUID> minecraftUuid() {
        return Optional.ofNullable(mcUuid);
    }

    @Override
    public String name() {
        return "tester";
    }

    @Override
    public Locale locale() {
        return Locale.ENGLISH;
    }

    @Override
    public boolean admin() {
        return true;
    }

    @Override
    public Origin origin() {
        return origin;
    }

    @Override
    public void reply(final MessageRef message) {
        replies.add(new Reply(message.key(), Map.copyOf(message.args()), Tone.NEUTRAL));
    }

    @Override
    public void reply(final MessageRef message, final Tone tone) {
        replies.add(new Reply(message.key(), Map.copyOf(message.args()), tone == null ? Tone.NEUTRAL : tone));
    }

    /** Answers the key itself, marked, so an assertion failure shows which key a command asked for. */
    @Override
    public String phrase(final MessageRef message) {
        return "<" + message.key() + ">";
    }

    @Override
    public void replyLiteral(final String text) {
        replies.add(new Reply("<literal>", Map.of("text", text), Tone.NEUTRAL));
    }
}
