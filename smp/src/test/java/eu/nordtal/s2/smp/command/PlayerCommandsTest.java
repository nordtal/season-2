package eu.nordtal.s2.smp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.context.MilestoneContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@code /aura} and {@code /smp status} say, without a server - the decisions that moved here
 * from {@code :commands} when the two became native Brigadier.
 */
class PlayerCommandsTest {

    private static final UUID SELF = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final FakeStanding standing = new FakeStanding();
    private final Recorder user = new Recorder();
    private final PlayerCommands commands = new PlayerCommands(standing, new Inline(), sender -> user);

    // ------------------------------------------------------------------ /aura

    @Test
    @DisplayName("/aura says where you stand and then the board, in that order")
    void auraIsYourOwnLineThenTheBoard() {
        // Your own line first, deliberately: the question somebody types /aura to answer is "where
        // am I", and a list of ten with the answer somewhere inside it is not that.
        standing.aura = new Standing.AuraStanding(
                120,
                3,
                37,
                List.of(
                        new Standing.AuraLine(1, "Anna", 400, false),
                        new Standing.AuraLine(2, "Bert", 200, false),
                        new Standing.AuraLine(3, "tester", 120, true)));

        commands.showAura(user, SELF);

        assertEquals(
                List.of("smp.aura.own", "smp.aura.top", "smp.aura.line", "smp.aura.line", "smp.aura.line"),
                user.keys());
        assertEquals(120, user.replies.getFirst().args().get("aura"));
        assertEquals(3, user.replies.getFirst().args().get("rank"));
        assertEquals(37, user.replies.getFirst().args().get("total"));
        assertEquals(3, user.replies.get(1).args().get("count"));
        assertEquals(SELF, standing.asked);
    }

    @Test
    @DisplayName("your own line on the board is coloured differently from the rest")
    void yourOwnLineIsMarked() {
        // One key, two tones. A second key with the same words in another colour is two strings to
        // translate, and one of them eventually says something else.
        standing.aura = new Standing.AuraStanding(
                120,
                2,
                2,
                List.of(new Standing.AuraLine(1, "Anna", 400, false), new Standing.AuraLine(2, "tester", 120, true)));

        commands.showAura(user, SELF);

        assertEquals(Tone.MUTED, user.replies.get(2).tone());
        assertEquals(Tone.GOOD, user.replies.get(3).tone());
    }

    @Test
    @DisplayName("an empty board says so instead of printing a heading over nothing")
    void anEmptyBoardIsNamed() {
        standing.aura = new Standing.AuraStanding(0, 1, 0, List.of());
        commands.showAura(user, SELF);
        assertEquals(List.of("smp.aura.own", "smp.aura.empty"), user.keys());
    }

    @Test
    @DisplayName("an account with no Discord link is told, not shown a zero")
    void anUnlinkedAccountIsNamed() {
        // The login gate makes this impossible, and this layer must not assume it: the gate is
        // another process's rule.
        standing.aura = null;
        commands.showAura(user, SELF);
        assertEquals(List.of("smp.aura.unlinked"), user.keys());
    }

    @Test
    @DisplayName("a database that does not answer says so and prints no board")
    void aFailedAuraReadIsNamed() {
        standing.failure = new IllegalStateException("no answer");
        commands.showAura(user, SELF);
        assertEquals(List.of("smp.aura.failed"), user.keys());
    }

    // ------------------------------------------------------------------ /smp status

    @Test
    @DisplayName("/smp status answers the phase, the milestone with its progress, and who is on")
    void statusIsThreeLines() {
        commands.showStatus(user);
        assertEquals(List.of("phase.current", "smp.status.milestone", "smp.status.online"), user.keys());
        assertEquals(
                new MilestoneContext("Aufbruch"), user.replies.get(1).args().get("milestone"));
        assertEquals(42, user.replies.get(1).args().get("percent"));
        assertEquals(3, user.replies.get(2).args().get("online"));
    }

    @Test
    @DisplayName("the third line is a sentence, and it picks a key rather than a bracketed plural")
    void statusCountsPeopleInSentences() {
        for (final int[] counts : new int[][] {{0, 0}, {1, 1}, {2, 2}, {57, 2}}) {
            standing.status = new Standing.Status("SMP", true, Optional.of("Aufbruch"), 42, counts[0]);
            user.replies.clear();
            commands.showStatus(user);

            final String expected = List.of("smp.status.online.none", "smp.status.online.one", "smp.status.online")
                    .get(counts[1]);
            assertEquals(expected, user.keys().get(2), counts[0] + " online should read as " + expected);
        }
    }

    @Test
    @DisplayName("a finished season says so instead of naming a milestone")
    void statusAfterTheLastMilestone() {
        standing.status = new Standing.Status("SMP", true, Optional.empty(), 0, 12);
        commands.showStatus(user);
        assertEquals(List.of("phase.current", "smp.status.finished", "smp.status.online"), user.keys());
    }

    @Test
    @DisplayName("a status asked before the first season refresh does not claim the season is finished")
    void statusBeforeTheFirstRefresh() {
        standing.status = new Standing.Status("SMP", false, Optional.empty(), 0, 0);
        commands.showStatus(user);
        assertEquals(List.of("phase.current", "smp.status.unread", "smp.status.online.none"), user.keys());
    }

    @Test
    @DisplayName("a status that cannot be read says so")
    void aFailedStatusReadIsNamed() {
        standing.failure = new IllegalStateException("no answer");
        commands.showStatus(user);
        assertEquals(List.of("smp.status.failed"), user.keys());
    }

    // ------------------------------------------------------------------ the bundle

    @Test
    @DisplayName("every line either command can say resolves, in both languages, in the bundle this plugin loads")
    void everyKeyResolves() {
        // The keys moved from :commands' bundle to this one. A half-moved key reaches a player as
        // the literal key string, which is the one failure nothing else here would catch.
        final Messages messages = Messages.load(
                PlayerCommandsTest.class.getClassLoader(),
                List.of("messages/paper-common", "messages/commands", "messages/smp"),
                null,
                Locale.ENGLISH,
                Locale.GERMAN);
        standing.aura = new Standing.AuraStanding(1, 1, 1, List.of(new Standing.AuraLine(1, "a", 1, true)));
        commands.showAura(user, SELF);
        standing.aura = new Standing.AuraStanding(0, 1, 0, List.of());
        commands.showAura(user, SELF);
        standing.aura = null;
        commands.showAura(user, SELF);
        for (final int online : new int[] {0, 1, 2}) {
            standing.status = new Standing.Status("SMP", true, Optional.of("x"), 1, online);
            commands.showStatus(user);
        }
        standing.status = new Standing.Status("SMP", true, Optional.empty(), 0, 0);
        commands.showStatus(user);
        standing.status = new Standing.Status("SMP", false, Optional.empty(), 0, 0);
        commands.showStatus(user);
        standing.failure = new IllegalStateException("no answer");
        commands.showAura(user, SELF);
        commands.showStatus(user);

        for (final String key : user.keys().stream().distinct().toList()) {
            for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                assertTrue(messages.hasTranslation(locale, key), key + " has no " + locale + " text");
            }
        }
    }

    // ------------------------------------------------------------------ stand-ins

    private static final class FakeStanding implements Standing {

        Standing.AuraStanding aura;
        Standing.Status status = new Standing.Status("SMP", true, Optional.of("Aufbruch"), 42, 3);
        RuntimeException failure;
        UUID asked;

        @Override
        public Status status(final Locale locale) {
            if (failure != null) {
                throw failure;
            }
            return status;
        }

        @Override
        public Optional<AuraStanding> auraStanding(final UUID player) {
            asked = player;
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(aura);
        }
    }

    /** Runs the read where it is asked for, so a test sees the replies on return. */
    private static final class Inline implements CommandEffects {

        @Override
        public void async(final Runnable work) {
            work.run();
        }

        @Override
        public void warn(final String what, final Throwable failure) {}
    }

    private record Reply(String key, Map<String, ?> args, Tone tone) {}

    private static final class Recorder implements NordtalUser {

        final List<Reply> replies = new ArrayList<>();

        List<String> keys() {
            return replies.stream().map(Reply::key).toList();
        }

        @Override
        public Optional<String> discordId() {
            return Optional.empty();
        }

        @Override
        public Optional<UUID> minecraftUuid() {
            return Optional.of(SELF);
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
            return false;
        }

        @Override
        public Origin origin() {
            return Origin.GAME;
        }

        @Override
        public void reply(final MessageRef message) {
            reply(message, Tone.NEUTRAL);
        }

        @Override
        public void reply(final MessageRef message, final Tone tone) {
            replies.add(new Reply(message.key(), Map.copyOf(message.args()), tone));
        }

        @Override
        public String phrase(final MessageRef message) {
            return message.key();
        }

        @Override
        public void replyLiteral(final String text) {}
    }
}
