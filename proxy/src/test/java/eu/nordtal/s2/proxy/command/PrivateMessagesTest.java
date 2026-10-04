package eu.nordtal.s2.proxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.access.AccessState;
import eu.nordtal.s2.database.access.MemberState;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messagerendering.ToneColours;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.packrendering.Glyphs;
import eu.nordtal.s2.packrendering.LanguageFlags;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import eu.nordtal.s2.settings.network.PlayersSpec;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The private message commands: the tree a client receives, the two lines of a message, and the reply partner.
 *
 * Delivery needs two Velocity players on a running proxy and is not tested.
 */
class PrivateMessagesTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrivateMessagesTest.class);

    private static final UUID ONE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID TWO = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID THREE = UUID.fromString("00000000-0000-0000-0000-0000000000b3");

    private final Messages messages = Messages.load("messages/proxy", Locale.ENGLISH, Locale.GERMAN);
    private final LoginRoster roster = new LoginRoster();
    private final PrivateMessages commands =
            new PrivateMessages(noProxy(), roster, MessageRenderer.of(messages), () -> ToneColours.DEFAULTS, LOGGER);

    // the tree

    @Test
    void theShapeIsWhatTheDeclarationsSaid() {
        assertEquals(List.of("msg", "whisper", "r"), literals());

        for (final String literal : List.of("msg", "whisper")) {
            final CommandNode<CommandSource> root = node(literal);
            assertEquals(List.of(PrivateMessages.PLAYER), names(root), literal + " no longer takes a recipient first");
            assertEquals(List.of(PrivateMessages.MESSAGE), names(root.getChild(PrivateMessages.PLAYER)));
        }
        assertEquals(List.of(PrivateMessages.MESSAGE), names(node("r")));
    }

    @Test
    void nothingInTheTreeIsADeadEnd() {
        // Written out by hand, a forgotten `executes` answers "Unknown command" for a word typed correctly.
        final List<String> dead = new ArrayList<>();
        for (final BrigadierCommand command : commands.commands()) {
            walk(command.getNode(), command.getNode().getName(), dead);
        }
        assertEquals(List.of(), dead, "a node in the tree runs nothing");
    }

    @Test
    void theDefaultAllowlistCarriesThem() {
        // The failure this catches: a command registered but refused by CommandGate, tab-completing yet unusable.
        final List<String> allowed = new PlayersSpec() {}.commandAllowlist();
        for (final String literal : literals()) {
            assertTrue(allowed.contains(literal), "/" + literal + " is registered and the default allowlist omits it");
        }
    }

    // the two lines

    @Test
    void theLineIsDrawnForItsReaderAndAboutTheOther() {
        roster.remember(ONE, session(ONE, Locale.ENGLISH, false));
        roster.remember(TWO, session(TWO, Locale.GERMAN, false));

        final String sent =
                flat(commands.line(PrivateMessages.Half.SENT, Locale.ENGLISH, player(TWO, "zwei"), "hello"));
        assertTrue(sent.startsWith("to "), sent);
        assertTrue(
                sent.contains(LanguageFlags.of(Locale.GERMAN)),
                "the flag belongs to the person the line is about, not to the person reading it");

        final String received =
                flat(commands.line(PrivateMessages.Half.RECEIVED, Locale.GERMAN, player(ONE, "eins"), "hello"));
        assertTrue(received.startsWith("von "), received);
        assertTrue(received.contains(LanguageFlags.of(Locale.ENGLISH)), received);
    }

    @Test
    void theAdminTagIsOnlyOnAdmins() {
        roster.remember(ONE, session(ONE, Locale.ENGLISH, true));
        roster.remember(TWO, session(TWO, Locale.ENGLISH, false));

        assertTrue(flat(commands.line(PrivateMessages.Half.RECEIVED, Locale.ENGLISH, player(ONE, "eins"), "hi"))
                .contains(Glyphs.TAG_ADMIN));
        assertFalse(flat(commands.line(PrivateMessages.Half.RECEIVED, Locale.ENGLISH, player(TWO, "zwei"), "hi"))
                .contains(Glyphs.TAG_ADMIN));
    }

    @Test
    void aPlayerCannotColourSomebodyElsesChat() {
        // The component slot: a substituted string would rely on escaping, but a component never reaches the parser.
        roster.remember(TWO, session(TWO, Locale.ENGLISH, false));
        final Component line =
                commands.line(PrivateMessages.Half.SENT, Locale.ENGLISH, player(TWO, "zwei"), "<red>look at me</red>");

        assertTrue(
                flat(line).contains("<red>look at me</red>"),
                "the tags reached the reader as the characters they typed: " + flat(line));
    }

    // the reply partner

    @Test
    void theReplyPartnerIsSetOnBothSides() {
        commands.remember(ONE, TWO);

        assertEquals(Optional.of(TWO), commands.partnerOf(ONE));
        assertEquals(
                Optional.of(ONE),
                commands.partnerOf(TWO),
                "a reply has to work without the recipient ever having typed a name");
    }

    @Test
    void leavingClearsBothEnds() {
        commands.remember(ONE, TWO);
        commands.forget(TWO);

        assertEquals(Optional.empty(), commands.partnerOf(TWO));
        assertEquals(
                Optional.empty(),
                commands.partnerOf(ONE),
                "the one who stayed was left pointing at a UUID that has gone, so every /r they "
                        + "type from now on says 'they are not here' rather than 'nobody has "
                        + "written to you'");
    }

    @Test
    void forgettingIsNotAReset() {
        commands.remember(ONE, TWO);
        commands.remember(THREE, ONE);
        commands.forget(TWO);

        assertEquals(Optional.of(THREE), commands.partnerOf(ONE));
        assertEquals(Optional.of(ONE), commands.partnerOf(THREE));
    }

    // the sentences

    @Test
    void theMovedSentencesArrived() {
        // Messages answers a missing key with the key itself, not a fallback.
        for (final String key : List.of(
                "chat.msg.sent",
                "chat.msg.received",
                "chat.no-partner",
                "chat.msg.self",
                "chat.failed",
                "command.describe.msg",
                "command.describe.whisper",
                "command.describe.r")) {
            assertTrue(messages.hasTranslation(Locale.ENGLISH, key), key + " is missing in en");
            assertTrue(messages.hasTranslation(Locale.GERMAN, key), key + " is missing in de");
        }
    }

    // helpers

    private List<String> literals() {
        return commands.commands().stream()
                .map(command -> command.getNode().getName())
                .toList();
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

    private static void walk(final CommandNode<CommandSource> node, final String path, final List<String> dead) {
        if (node.getCommand() == null) {
            dead.add(path);
        }
        node.getChildren().forEach(child -> walk(child, path + " " + child.getName(), dead));
    }

    private static String flat(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** What a login query would have said about somebody, which is all the roster keeps. */
    private static AccessState session(final UUID mcUuid, final Locale locale, final boolean admin) {
        return new AccessState(
                mcUuid,
                DiscordId.of("1"),
                MemberState.MEMBER,
                true,
                Instant.now().plus(Duration.ofDays(1)),
                false,
                admin,
                false,
                0L,
                locale,
                SeasonPhase.SMP,
                null);
    }

    /** A name and a UUID, which is all {@link PrivateMessages#line} asks a player for. */
    private static Player player(final UUID uuid, final String name) {
        return (Player) Proxy.newProxyInstance(
                PrivateMessagesTest.class.getClassLoader(), new Class<?>[] {Player.class}, (InvocationHandler)
                        (self, method, arguments) -> switch (method.getName()) {
                            case "getUniqueId" -> uuid;
                            case "getUsername" -> name;
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }

    /** Never asked anything: every test here is below the point where a player is looked up. */
    private static ProxyServer noProxy() {
        return (ProxyServer) Proxy.newProxyInstance(
                PrivateMessagesTest.class.getClassLoader(), new Class<?>[] {ProxyServer.class}, (InvocationHandler)
                        (self, method, arguments) -> {
                            throw new UnsupportedOperationException(method.getName());
                        });
    }
}
