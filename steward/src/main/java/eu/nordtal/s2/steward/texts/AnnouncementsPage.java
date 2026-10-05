package eu.nordtal.s2.steward.texts;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Name;

/** The announcement form under the bot's console: one text per language. */
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

    @Name("Host channel")
    MessageRef hostChannel();

    @Name("No channel")
    MessageRef noChannel();
}
