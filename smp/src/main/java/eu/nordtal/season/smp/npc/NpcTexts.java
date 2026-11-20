package eu.nordtal.season.smp.npc;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import java.util.Locale;

/** The words smp's NPCs show: the hints of the menus a dialogue opens. */
public final class NpcTexts {

    static final DocumentSpec<DocumentSpec.None> OBJECTIVES = DocumentSpec.game(
                    "smp", "npc-objectives", "NPC dialogue", DocumentSpec.Shape.ACTION_BAR)
            .titled("Hint to open the objectives from an NPC dialogue");

    static final DocumentSpec<DocumentSpec.None> WHEEL = DocumentSpec.game(
                    "smp", "npc-wheel", "NPC dialogue", DocumentSpec.Shape.ACTION_BAR)
            .titled("Hint to open the wheel from an NPC dialogue");

    private NpcTexts() {}

    /** Returns the hint of the objectives menu in {@code locale}. */
    public static String objectivesHint(final MessageRenderer renderer, final Locale locale) {
        return renderer.plainLine(locale, Sent.of(OBJECTIVES));
    }

    /** Returns the hint of the wheel menu in {@code locale}. */
    public static String wheelHint(final MessageRenderer renderer, final Locale locale) {
        return renderer.plainLine(locale, Sent.of(WHEEL));
    }
}
