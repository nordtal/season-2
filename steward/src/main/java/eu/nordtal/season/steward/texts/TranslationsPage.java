package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;

/** The texts of the Translations page, which lists every text by area and section and has a filter and a search. */
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

    @Name("Areas")
    MessageRef areas();

    @Name("Back")
    MessageRef back();

    @Name("Not translated")
    MessageRef untranslated();

    @Name("Not translated count")
    MessageRef untranslatedCount(@Arg("count") @Example("4") int count);

    /** The place where a text appears, or the building blocks. */
    enum Group {
        GAME,
        DISCORD,
        STEWARD,
        VALUES
    }
}
