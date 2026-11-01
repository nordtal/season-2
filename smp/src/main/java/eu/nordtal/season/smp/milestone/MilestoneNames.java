package eu.nordtal.season.smp.milestone;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
import java.util.Locale;

/**
 * Resolves the name a player sees for a milestone or an objective, by its id.
 * A document holds one line per id, so an admin names a milestone or an objective they add on Translations.
 */
public final class MilestoneNames {

    record Name(@Example("foothold") String milestone) {}

    record ObjectiveName(
            @Example("foothold") String milestone,
            @Example("logs") String objective) {}

    static final DocumentSpec<Name> NAME = DocumentSpec.game(
                    "smp", "milestone-name", "Milestones", DocumentSpec.Shape.BOSS_BAR, Name.class)
            .titled("Milestone names");

    static final DocumentSpec<ObjectiveName> OBJECTIVE_NAME = DocumentSpec.game(
                    "smp", "objective-name", "Milestones", DocumentSpec.Shape.BOSS_BAR, ObjectiveName.class)
            .titled("Objective names");

    private MilestoneNames() {}

    /** Returns the name of the milestone in {@code locale}, or {@code key} when no line names it. */
    public static String of(final MessageRenderer renderer, final Locale locale, final String key) {
        return orKey(renderer.plainLine(locale, NAME.with(new Name(key))), key);
    }

    /** Returns the name of the objective of the milestone in {@code locale}, or {@code key} when no line names it. */
    public static String objective(
            final MessageRenderer renderer, final Locale locale, final String milestone, final String key) {
        return orKey(renderer.plainLine(locale, OBJECTIVE_NAME.with(new ObjectiveName(milestone, key))), key);
    }

    private static String orKey(final String name, final String key) {
        return name.isBlank() ? key : name;
    }
}
