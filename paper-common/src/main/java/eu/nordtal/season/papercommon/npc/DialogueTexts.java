package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import java.util.Locale;
import java.util.function.Function;

/** The words of a dialogue's hints and its switch, as documents an admin edits on Translations. */
public final class DialogueTexts {

    static final DocumentSpec<DocumentSpec.None> NEXT = DocumentSpec.game(
                    "paper-common", "npc-next", "NPC dialogue", DocumentSpec.Shape.ACTION_BAR)
            .titled("Hint to go on in an NPC dialogue");

    static final DocumentSpec<DocumentSpec.None> CHOOSE = DocumentSpec.game(
                    "paper-common", "npc-choose", "NPC dialogue", DocumentSpec.Shape.ACTION_BAR)
            .titled("Hint to pick an answer in an NPC dialogue");

    static final DocumentSpec<DocumentSpec.None> ANSWER = DocumentSpec.game(
                    "paper-common", "npc-answer", "NPC dialogue", DocumentSpec.Shape.ACTION_BAR)
            .titled("Hint to give the picked answer in an NPC dialogue");

    static final DocumentSpec<DocumentSpec.None> DONT_SHOW_AGAIN = DocumentSpec.game(
                    "paper-common", "npc-dont-show-again", "NPC dialogue", DocumentSpec.Shape.ACTION_BAR)
            .titled("Switch to skip an NPC dialogue from now on");

    private DialogueTexts() {}

    /** Returns the labels of a dialogue in each reader's language, with the hint of every menu in {@code menus}. */
    public static Function<Locale, Labels> labels(final MessageRenderer renderer, final NpcMenus menus) {
        return locale -> new Labels(
                renderer.plainLine(locale, Sent.of(NEXT)),
                renderer.plainLine(locale, Sent.of(CHOOSE)),
                renderer.plainLine(locale, Sent.of(ANSWER)),
                renderer.plainLine(locale, Sent.of(DONT_SHOW_AGAIN)),
                menus.hints(locale));
    }
}
