package eu.nordtal.s2.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Somebody asking for a command, recorded rather than rendered.
 *
 * <p>This class is the point of {@code :commands} in one file. Before it, "what does {@code /phase}
 * say back when the phase was already the one asked for?" could only be answered by a real Velocity
 * proxy with a real client, or a real Discord guild with a real admin - which is why the answer had
 * drifted into two different sentences on the two surfaces without anybody noticing.</p>
 */
public final class FakeUser implements NordtalUser {

    /** Every reply, as {@code key} plus its placeholders, in the order they were sent. */
    public final List<Reply> replies = new ArrayList<>();

    private final Origin origin;
    private final String discordId;
    private final UUID mcUuid;

    public record Reply(String key, Map<String, ?> placeholders) {

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
        return new FakeUser(Origin.GAME, "100000000000000001",
                UUID.fromString("11111111-2222-3333-4444-555555555555"));
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
    public void reply(final String messageKey, final Map<String, ?> placeholders) {
        replies.add(new Reply(messageKey, Map.copyOf(placeholders)));
    }

    /**
     * Answers the key itself, marked.
     *
     * <p>A real adapter renders it against a bundle; what a test wants to know is <em>which</em> key
     * a command asked for, which the marker makes visible in an assertion failure.</p>
     */
    @Override
    public String phrase(final String messageKey) {
        return "<" + messageKey + ">";
    }

    @Override
    public void replyLiteral(final String text) {
        replies.add(new Reply("<literal>", Map.of("text", text)));
    }
}
