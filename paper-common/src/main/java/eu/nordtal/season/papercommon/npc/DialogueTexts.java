package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.Example;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.kyori.adventure.text.Component;

/** The words of a dialogue's hints and its switch, as documents an admin edits on Translations. */
public final class DialogueTexts {

    /** @param npc the NPC's name in the viewer's language */
    record SkipNotice(@Example("Runa") String npc) {}

    static final DocumentSpec<DocumentSpec.None> NEXT = DocumentSpec.game(
                    "paper-common", "npc-next", "NPC dialogue", DocumentSpec.Shape.ACTION_BAR)
            .titled("Hint to go on in an NPC dialogue");

    static final DocumentSpec<DocumentSpec.None> MORE = DocumentSpec.game(
                    "paper-common", "npc-more", "NPC dialogue", DocumentSpec.Shape.ACTION_BAR)
            .titled("Hint to turn to the next page of a long text in an NPC dialogue");

    static final DocumentSpec<DocumentSpec.None> CHOOSE = DocumentSpec.game(
                    "paper-common", "npc-choose", "NPC dialogue", DocumentSpec.Shape.ACTION_BAR)
            .titled("Hint to pick an answer in an NPC dialogue");

    static final DocumentSpec<DocumentSpec.None> ANSWER = DocumentSpec.game(
                    "paper-common", "npc-answer", "NPC dialogue", DocumentSpec.Shape.ACTION_BAR)
            .titled("Hint to give the picked answer in an NPC dialogue");

    static final DocumentSpec<DocumentSpec.None> DONT_SHOW_AGAIN = DocumentSpec.game(
                    "paper-common", "npc-dont-show-again", "NPC dialogue", DocumentSpec.Shape.ACTION_BAR)
            .titled("Switch to skip an NPC dialogue from now on");

    static final DocumentSpec<SkipNotice> SKIP_ENABLED = DocumentSpec.game(
                    "paper-common", "npc-skip-enabled", "NPC dialogue", DocumentSpec.Shape.CHAT, SkipNotice.class)
            .titled("Notice after skipping an NPC dialogue");

    private DialogueTexts() {}

    /** Returns the labels of a dialogue in each reader's language, with the hint of every menu in {@code menus}. */
    public static Function<Locale, Labels> labels(final MessageRenderer renderer, final NpcMenus menus) {
        return locale -> new Labels(
                renderer.plainLine(locale, Sent.of(NEXT)),
                renderer.plainLine(locale, Sent.of(MORE)),
                renderer.plainLine(locale, Sent.of(CHOOSE)),
                renderer.plainLine(locale, Sent.of(ANSWER)),
                renderer.plainLine(locale, Sent.of(DONT_SHOW_AGAIN)),
                menus.hints(locale));
    }

    /** Formats the chat notice sent after an NPC-opened menu closes when the player newly enabled skip. */
    public static BiFunction<Locale, String, Component> skipNotice(final MessageRenderer renderer) {
        return (locale, npc) -> renderer.format(locale, SKIP_ENABLED.with(new SkipNotice(npc)));
    }
}
