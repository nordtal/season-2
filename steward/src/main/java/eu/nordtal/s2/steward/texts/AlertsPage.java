package eu.nordtal.s2.steward.texts;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Name;

/** The alerts page's own words; an alert itself is told in the admin bundle. */
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
