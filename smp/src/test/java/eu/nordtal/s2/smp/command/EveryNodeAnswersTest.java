package eu.nordtal.s2.smp.command;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import eu.nordtal.s2.common.message.Messages;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That every node of this plugin's two hand-built command trees can be typed on its own.
 *
 * <h2>The failure it exists for</h2>
 * A Brigadier node with children and no {@code executes} parses nothing by itself. Paper answers
 * that with {@code UnknownCommandEvent}, which {@code CommandFilter} turns into <b>"That command
 * does not exist"</b> - so a player who typed {@code /poi} was told, about a command that does
 * exist and that their own tab completion had just offered them, that it does not. It shipped that
 * way because {@code PaperCommands} gives every declared command that answer automatically and
 * these two trees are built by hand, outside it (see {@code SmpPlugin#registerCommands} for why).
 *
 * <p>That is the whole shape of the bug: the gap is invisible from the code that carries the guard,
 * and the symptom reads like a typo rather than like a missing branch. So the check is on the shape
 * of the tree itself, and not on the wording of one message.</p>
 *
 * <p>Nothing is executed and no server is involved - {@code Commands.literal} and
 * {@code Commands.argument} are plain Brigadier builders, and the handlers are only referenced.
 * None of the constructor's arguments is read while the tree is built, which is why they are null
 * here.</p>
 */
class EveryNodeAnswersTest {

    private final NavigateCommand commands =
            new NavigateCommand(null, null, null, null, null, null, null);

    @Test
    @DisplayName("/poi answers at every depth, bare root and bare subcommand included")
    void poi() {
        assertEveryNodeAnswers(commands.poi());
    }

    @Test
    @DisplayName("/navigate answers")
    void navigate() {
        assertEveryNodeAnswers(commands.navigate());
    }

    @Test
    @DisplayName("the tree still carries the two subcommands the help promises")
    void theHelpAndTheTreeAgree() {
        // The enum drives both, so this only has to catch a subcommand hung on the tree by hand
        // next to the pair rather than through it - which would be a branch the help never names.
        final List<String> literals = new ArrayList<>();
        commands.poi().getChildren().forEach(child -> literals.add(child.getName()));
        assertTrue(literals.size() == 2 && literals.contains("add") && literals.contains("remove"),
                "/poi's subcommands are " + literals + ", and its help is built from an enum that"
                        + " does not know about that. Add it to NavigateCommand.Sub instead.");
    }

    @Test
    @DisplayName("everything /poi's help says is a message key that resolves, in both languages")
    void theHelpSaysWordsAndNotKeys() {
        // The same three roots in the same order SmpPlugin loads them: the format keys come from
        // :commands and the two explanations from this module's own bundle, so an answer that is
        // half raw keys is exactly what a test on one bundle alone would miss. Messages degrades
        // to the key rather than throwing, which is the right runtime behaviour and the reason
        // this has to fail here instead.
        final Messages messages = Messages.load(EveryNodeAnswersTest.class.getClassLoader(),
                List.of("messages/paper-common", "messages/commands", "messages/smp"),
                null, Locale.ENGLISH, Locale.GERMAN);
        final List<String> keys = List.of("command.help.header", "command.help.line",
                "command.help.usage", "command.help.what",
                "command.describe.poi.add", "command.describe.poi.remove");
        for (final String key : keys) {
            for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                assertTrue(messages.hasTranslation(locale, key),
                        key + " is missing in " + locale.getLanguage() + ", so /poi's help would"
                                + " print that key at somebody who has just mistyped the command");
            }
        }
    }

    private static void assertEveryNodeAnswers(final LiteralCommandNode<CommandSourceStack> root) {
        walk(root, "/" + root.getName());
    }

    private static void walk(final CommandNode<CommandSourceStack> node, final String path) {
        assertTrue(node.getCommand() != null,
                path + " runs nothing when it is typed on its own, so Brigadier refuses to parse it"
                        + " and the player is told \"That command does not exist\". Give it an"
                        + " executes() that answers with its usage, or with what it takes.");
        for (final CommandNode<CommandSourceStack> child : node.getChildren()) {
            walk(child, path + " " + child.getName());
        }
    }
}
