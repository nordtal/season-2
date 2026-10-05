package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

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
