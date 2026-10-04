package eu.nordtal.s2.steward.texts;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Name;

/** The announcements page: one text per language, and the latest ones. */
@Name("Announcements")
public interface AnnouncementsPage {

    @Name("Compose")
    MessageRef compose();

    @Name("Send")
    MessageRef send();

    @Name("Ask")
    MessageRef ask();

    @Name("Where")
    MessageRef where(@Arg("languages") int languages);

    @Name("Sending")
    MessageRef sending();

    @Name("Host channel")
    MessageRef hostChannel();

    @Name("No channel")
    MessageRef noChannel();

    @Name("Recent")
    MessageRef recent();

    @Name("None")
    MessageRef none();
}
