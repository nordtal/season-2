package eu.nordtal.season.smp.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every node of the two hand-built command trees of this plugin can be typed on its own.
 *
 * A node with children and no {@code executes} answers like a typo. The test executes nothing, so arguments can be
 * null.
 */
class EveryNodeAnswersTest {

    private final NavigateCommand commands = new NavigateCommand(null, null, null, null, null, null, null, null);

    @Test
    void poi() {
        assertEveryNodeAnswers(commands.poi());
    }

    @Test
    void navigate() {
        assertEveryNodeAnswers(commands.navigate());
    }

    @Test
    void theHelpAndTheTreeAgree() {
        // The enum drives both trees, so this catches a subcommand added by hand that help never names.
        final List<String> literals = new ArrayList<>();
        commands.poi().getChildren().forEach(child -> literals.add(child.getName()));
        assertTrue(
                literals.size() == 2 && literals.contains("add") && literals.contains("remove"),
                "/poi's subcommands are " + literals + ", and its help is built from an enum that"
                        + " does not know about that. Add it to NavigateCommand.Sub instead.");
    }

    @Test
    void theHelpSaysWordsForEverySubcommand() {
        final MessageRenderer renderer = MessageRenderer.of(
                        Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN))
                .reading(new AppliedDocuments(EveryNodeAnswersTest.class.getClassLoader(), Set.of("smp")));
        // The help's frame is paper-common's documents, which the build checks.
        for (final NavigateCommand.Sub sub : NavigateCommand.Sub.values()) {
            for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                assertFalse(
                        renderer.plainLine(locale, sub.describe()).isBlank(),
                        "poi-describe has no line for " + sub + " in " + locale.getLanguage()
                                + ", so /poi's help would show an empty line to somebody who has just"
                                + " mistyped the command");
            }
        }
    }

    private static void assertEveryNodeAnswers(final LiteralCommandNode<CommandSourceStack> root) {
        walk(root, "/" + root.getName());
    }

    private static void walk(final CommandNode<CommandSourceStack> node, final String path) {
        assertTrue(
                node.getCommand() != null,
                path + " runs nothing when it is typed on its own, so Brigadier refuses to parse it"
                        + " and the player is told \"That command does not exist\". Give it an"
                        + " executes() that answers with its usage, or with what it takes.");
        for (final CommandNode<CommandSourceStack> child : node.getChildren()) {
            walk(child, path + " " + child.getName());
        }
    }
}
