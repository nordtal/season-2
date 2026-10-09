package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** The journal page's own words; a line of it is told in the admin bundle. */
@Name("Journal page")
public interface JournalPage {

    @Name("Title")
    MessageRef title();

    @Name("Entries")
    MessageRef entries();

    @Name("Every action")
    MessageRef all();

    @Name("Subject field")
    MessageRef subject();

    @Name("Subject placeholder")
    MessageRef exactId();

    @Name("Nothing matches")
    MessageRef noEntry();

    @Name("When")
    MessageRef when();

    @Name("Action")
    MessageRef action();

    @Name("Triggered by")
    MessageRef actor();

    @Name("Concerns")
    MessageRef concerns();

    @Name("Detail")
    MessageRef detail();

    @Name("How many")
    MessageRef count(@Arg("count") int count, @Arg("limit") int limit);
}
