package eu.nordtal.season.smp.npc;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.Example;
import java.util.Locale;

/** The words smp's NPCs show: the plate above each figure and the hint of the objectives menu. */
public final class NpcTexts {

    record Plate(@Example("Ragnar") String name) {}

    static final DocumentSpec<Plate> NAME_PLATE = DocumentSpec.game(
                    "smp", "npc-name-plate", "NPC dialogue", DocumentSpec.Shape.HOLOGRAM, Plate.class)
            .titled("Name above an NPC");

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
