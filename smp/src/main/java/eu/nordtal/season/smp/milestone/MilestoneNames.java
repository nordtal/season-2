package eu.nordtal.season.smp.milestone;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import java.util.Arrays;
import java.util.Locale;

/** Resolves the name a player sees for a milestone. */
public final class MilestoneNames {

    /** The milestones of the default track, the ones with a name of their own. */
    enum Named {
        WAITING,
        DEPARTURE,
        FOOTHOLD,
        SETTLEMENT,
        NETHER,
        END,
        EXPANSE,
        FRONTIER
    }

    record Name(Named milestone) {}

    static final DocumentSpec<Name> NAME = DocumentSpec.game(
                    "smp", "milestone-name", "Milestones", DocumentSpec.Shape.BOSS_BAR, Name.class)
            .titled("Milestone names");

    private MilestoneNames() {}

    /** Returns the name of the milestone in {@code locale}, or {@code key} when no name exists. */
    public static String of(final MessageRenderer renderer, final Locale locale, final String key) {
        return Arrays.stream(Named.values())
                .filter(named -> named.name().toLowerCase(Locale.ROOT).equals(key))
                .findFirst()
                .map(named -> renderer.plainLine(locale, NAME.with(new Name(named))))
                .orElse(key);
    }
}
