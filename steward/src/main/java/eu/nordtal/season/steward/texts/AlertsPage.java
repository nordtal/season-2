package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** The words of the alerts page. The admin bundle holds the alert texts. */
@Name("Alerts page")
public interface AlertsPage {

    @Name("Title")
    MessageRef title();

    @Name("Now")
    MessageRef now();

    @Name("Recent")
    MessageRef recent();

    @Name("Nothing wrong")
    MessageRef allClear();

    @Name("Nothing raised")
    MessageRef noneRaised();

    @Name("Stack unreadable")
    MessageRef unreadable();

    @Name("Raised by")
    MessageRef raisedBy(@Arg("who") String who);
}
