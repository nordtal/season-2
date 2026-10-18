package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** The texts of the Translations page, which groups every text by place and has a filter and a search. */
@Name("Translations")
public interface TranslationsPage {

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

    /** The place where a text appears, or the building blocks. */
    enum Group {
        GAME,
        DISCORD,
        STEWARD,
        VALUES
    }
}
