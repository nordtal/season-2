package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;

/** The words of the announcements tab: the drafts and the sent ones beside the one open. */
@Name("Announcements")
public interface AnnouncementsPage {

    @Name("Tab")
    MessageRef tab();

    @Name("Compose")
    MessageRef compose();

    @Name("Drafts")
    MessageRef drafts();

    @Name("No drafts")
    MessageRef noDrafts();

    @Name("Sent")
    MessageRef sent();

    @Name("Search")
    MessageRef search();

    @Name("No match")
    MessageRef noMatch();

    @Name("Empty")
    MessageRef empty();

    @Name("Empty note")
    MessageRef emptyNote();

    @Name("None open")
    MessageRef noneOpen();

    @Name("None open note")
    MessageRef noneOpenNote();

    @Name("Draft")
    MessageRef draft();

    @Name("Untitled")
    MessageRef untitled();

    @Name("Changed")
    MessageRef changed(@Arg("time") @Example("5 minutes ago") String time);

    @Name("Sent at")
    MessageRef sentAt(@Arg("time") @Example("Nov 1, 18:00") String time);

    @Name("Channel")
    MessageRef channel(@Arg("language") @Example("Deutsch") String language);

    @Name("Not everywhere")
    MessageRef notEverywhere(@Arg("count") @Example("1") int count);

    @Name("Copy")
    MessageRef copy();

    @Name("Retry")
    MessageRef retry(@Arg("languages") @Example("Deutsch") String languages);

    @Name("Delete")
    MessageRef delete();

    @Name("Deleted")
    MessageRef deleted();

    @Name("Saved")
    MessageRef saved();

    @Name("Send")
    MessageRef send();

    @Name("Send short")
    MessageRef sendShort();

    @Name("Ask")
    MessageRef ask();

    @Name("Where")
    MessageRef where(@Arg("channels") @Example("#news, #updates") String channels);

    @Name("No channel")
    MessageRef noChannel();

    @Name("Posted")
    MessageRef posted(@Arg("language") @Example("Deutsch") String language);

    @Name("Not posted")
    MessageRef notPosted(@Arg("language") @Example("Deutsch") String language);

    @Name("Posting")
    MessageRef posting(@Arg("language") @Example("Deutsch") String language);
}
