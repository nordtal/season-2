package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** The Texts page: every text of every bundle, grouped by where it appears, with its filter and its search. */
@Name("Texts")
public interface TextsPage {

    @Name("Group")
    MessageRef group(@Arg("group") Group group);

    @Name("Service filter")
    MessageRef service();

    @Name("All services")
    MessageRef allServices();

    @Name("Search")
    MessageRef search();

    @Name("Shown in")
    MessageRef shownIn();

    @Name("No texts")
    MessageRef none();

    /** Where a text appears, or the building blocks every other text puts in. */
    enum Group {
        GAME,
        DISCORD,
        STEWARD,
        VALUES
    }
}
