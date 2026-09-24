package eu.nordtal.s2.proxy.command;

import com.mojang.brigadier.tree.CommandNode;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.MemberState;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.proxy.config.NetworkSpec;
import eu.nordtal.s2.proxy.gate.LoginRoster;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three commands that came out of {@code :commands} in season-2-ops/155.
 *
 * <p>What is asserted here is what survived the move: the shape of the tree a client receives, the
 * two lines a message is drawn as, and the reply partner. The delivery itself is not - it needs two
 * Velocity {@code Player}s on a running proxy, which is the same limit {@code RestartGateTest}
 * states. The seams {@link PrivateMessages#line}, {@link PrivateMessages#remember} and
 * {@link PrivateMessages#forget} exist so that the part with a decision in it does not need one.</p>
 */
class PrivateMessagesTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrivateMessagesTest.class);

    private static final UUID ONE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID TWO = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID THREE = UUID.fromString("00000000-0000-0000-0000-0000000000b3");

    private final Messages messages =
            Messages.load("messages/proxy", Locale.ENGLISH, Locale.GERMAN);
    private final LoginRoster roster = new LoginRoster();
    private final PrivateMessages commands = new PrivateMessages(
            noProxy(), roster, messages, () -> ToneColours.DEFAULTS, LOGGER);

    // ---------------------------------------------------------------- the tree

    @Test
    @DisplayName("the tree is /msg <player> <message>, twice, and /r <message>")
    void theShapeIsWhatTheDeclarationsSaid() {
        assertEquals(List.of("msg", "whisper", "r"), literals());

        for (final String literal : List.of("msg", "whisper")) {
            final CommandNode<CommandSource> root = node(literal);
            assertEquals(List.of(PrivateMessages.PLAYER), names(root),
                    literal + " no longer takes a recipient first");
            assertEquals(List.of(PrivateMessages.MESSAGE),
                    names(root.getChild(PrivateMessages.PLAYER)));
        }
        assertEquals(List.of(PrivateMessages.MESSAGE), names(node("r")));
    }

    @Test
    @DisplayName("every step of every command runs something rather than failing to parse")
    void nothingInTheTreeIsADeadEnd() {
        // The declaration used to guarantee this: VelocityCommands put a usage line on every node
        // that could not run yet. Written out by hand, a forgotten `executes` is a command that
        // answers "Unknown command" to somebody who typed it correctly but stopped one word short.
        final List<String> dead = new ArrayList<>();
        for (final BrigadierCommand command : commands.commands()) {
            walk(command.getNode(), command.getNode().getName(), dead);
        }
        assertEquals(List.of(), dead, "a node in the tree runs nothing");
    }

    @Test
    @DisplayName("a player may actually type all three, out of the box")
    void theDefaultAllowlistCarriesThem() {
        // THE FAILURE THIS CATCHES: a native command is registered with Velocity and refused by
        // CommandGate, so it exists, tab-completes for an admin, and tells everybody else they may
        // not use it. The declaration never protected against that either - but the list and the
        // registration now sit in two different modules, so nothing else holds them together.
        final List<String> allowed = new NetworkSpec() { }.commandAllowlist();
        for (final String literal : literals()) {
            assertTrue(allowed.contains(literal),
                    "/" + literal + " is registered and network.yml's default list omits it");
        }
    }

    // ---------------------------------------------------------------- the two lines

    @Test
    @DisplayName("each side reads their own language, and the flag is the other side's")
    void theLineIsDrawnForItsReaderAndAboutTheOther() {
        roster.remember(ONE, session(ONE, Locale.ENGLISH, false));
        roster.remember(TWO, session(TWO, Locale.GERMAN, false));

        final String sent = flat(commands.line(PrivateMessages.Half.SENT, Locale.ENGLISH,
                player(TWO, "zwei"), "hello"));
        assertTrue(sent.startsWith("to "), sent);
        assertTrue(sent.contains(Glyphs.flagFor(Locale.GERMAN)),
                "the flag belongs to the person the line is about, not to the person reading it");

        final String received = flat(commands.line(PrivateMessages.Half.RECEIVED, Locale.GERMAN,
                player(ONE, "eins"), "hello"));
        assertTrue(received.startsWith("von "), received);
        assertTrue(received.contains(Glyphs.flagFor(Locale.ENGLISH)), received);
    }

    @Test
    @DisplayName("an admin carries the tag and nobody else does")
    void theAdminTagIsOnlyOnAdmins() {
        roster.remember(ONE, session(ONE, Locale.ENGLISH, true));
        roster.remember(TWO, session(TWO, Locale.ENGLISH, false));

        assertTrue(flat(commands.line(PrivateMessages.Half.RECEIVED, Locale.ENGLISH,
                player(ONE, "eins"), "hi")).contains(Glyphs.TAG_ADMIN));
        assertFalse(flat(commands.line(PrivateMessages.Half.RECEIVED, Locale.ENGLISH,
                player(TWO, "zwei"), "hi")).contains(Glyphs.TAG_ADMIN));
    }

    @Test
    @DisplayName("what somebody types is never parsed as markup")
    void aPlayerCannotColourSomebodyElsesChat() {
        // The component slot, and the whole reason it exists. A substituted string would be
        // escaped by MessageRenderer, but escaping is a rule somebody has to remember; a component
        // never reaches the parser at all.
        roster.remember(TWO, session(TWO, Locale.ENGLISH, false));
        final Component line = commands.line(PrivateMessages.Half.SENT, Locale.ENGLISH,
                player(TWO, "zwei"), "<red>look at me</red>");

        assertTrue(flat(line).contains("<red>look at me</red>"),
                "the tags reached the reader as the characters they typed: " + flat(line));
    }

    // ---------------------------------------------------------------- the reply partner

    @Test
    @DisplayName("one message points both of them at each other")
    void theReplyPartnerIsSetOnBothSides() {
        commands.remember(ONE, TWO);

        assertEquals(Optional.of(TWO), commands.partnerOf(ONE));
        assertEquals(Optional.of(ONE), commands.partnerOf(TWO),
                "a reply has to work without the recipient ever having typed a name");
    }

    @Test
    @DisplayName("a leaving player is forgotten in both directions")
    void leavingClearsBothEnds() {
        commands.remember(ONE, TWO);
        commands.forget(TWO);

        assertEquals(Optional.empty(), commands.partnerOf(TWO));
        assertEquals(Optional.empty(), commands.partnerOf(ONE),
                "the one who stayed was left pointing at a UUID that has gone, so every /r they "
                        + "type from now on says 'they are not here' rather than 'nobody has "
                        + "written to you'");
    }

    @Test
    @DisplayName("a third conversation is untouched by somebody else leaving")
    void forgettingIsNotAReset() {
        commands.remember(ONE, TWO);
        commands.remember(THREE, ONE);
        commands.forget(TWO);

        assertEquals(Optional.of(THREE), commands.partnerOf(ONE));
        assertEquals(Optional.of(ONE), commands.partnerOf(THREE));
    }

    // ---------------------------------------------------------------- the sentences

    @Test
    @DisplayName("every key these three name is in this module's bundle, in both languages")
    void theMovedSentencesArrived() {
        // They stood in messages/commands until season-2-ops/155. Messages answers a missing key
        // with the key, so a half-finished move reaches a player as the literal chat.no-partner.
        for (final String key : List.of("chat.msg.sent", "chat.msg.received",
                "chat.no-partner", "chat.msg.self", "chat.failed",
                "command.describe.msg", "command.describe.whisper", "command.describe.r")) {
            assertTrue(messages.hasTranslation(Locale.ENGLISH, key), key + " is missing in en");
            assertTrue(messages.hasTranslation(Locale.GERMAN, key), key + " is missing in de");
        }
    }

    // ---------------------------------------------------------------- helpers

    private List<String> literals() {
        return commands.commands().stream().map(command -> command.getNode().getName()).toList();
    }

    private CommandNode<CommandSource> node(final String literal) {
        return commands.commands().stream()
                .map(BrigadierCommand::getNode)
                .filter(node -> node.getName().equals(literal))
                .findFirst()
                .orElseThrow(() -> new AssertionError("/" + literal + " is not registered"));
    }

    private static List<String> names(final CommandNode<CommandSource> node) {
        return node.getChildren().stream().map(CommandNode::getName).toList();
    }

    private static void walk(final CommandNode<CommandSource> node, final String path,
                             final List<String> dead) {
        if (node.getCommand() == null) {
            dead.add(path);
        }
        node.getChildren().forEach(child -> walk(child, path + " " + child.getName(), dead));
    }

    private static String flat(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** What a login query would have said about somebody, which is all the roster keeps. */
    private static AccessState session(final UUID mcUuid, final Locale locale,
                                       final boolean admin) {
        return new AccessState(mcUuid, "1", MemberState.MEMBER, true,
                Instant.now().plus(Duration.ofDays(1)), false, admin, locale, SeasonPhase.SMP, null);
    }

    /** A name and a UUID, which is all {@link PrivateMessages#line} asks a player for. */
    private static Player player(final UUID uuid, final String name) {
        return (Player) Proxy.newProxyInstance(PrivateMessagesTest.class.getClassLoader(),
                new Class<?>[] {Player.class}, (InvocationHandler) (self, method, arguments) ->
                        switch (method.getName()) {
                            case "getUniqueId" -> uuid;
                            case "getUsername" -> name;
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }

    /** Never asked anything: every test here is below the point where a player is looked up. */
    private static ProxyServer noProxy() {
        return (ProxyServer) Proxy.newProxyInstance(PrivateMessagesTest.class.getClassLoader(),
                new Class<?>[] {ProxyServer.class}, (InvocationHandler) (self, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
