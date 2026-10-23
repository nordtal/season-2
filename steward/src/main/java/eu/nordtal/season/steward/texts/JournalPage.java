package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** The words of the journal page. The admin bundle holds the journal line texts. */
@Name("Journal page")
public interface JournalPage {

    @Name("Title")
    MessageRef title();

    @Name("Entries")
    MessageRef entries();

    @Name("Subject field")
    MessageRef subject();

    @Name("Payment reference field")
    MessageRef reference();

    @Name("Least days field")
    MessageRef minDays();

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
