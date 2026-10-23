package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** The words of the journal page. The admin bundle holds the journal line and alert texts. */
@Name("Journal page")
public interface JournalPage {

    @Name("Title")
    MessageRef title();

    @Name("Nothing matches")
    MessageRef noEntry();

    @Name("Open now")
    MessageRef openNow();

    @Name("Nothing open")
    MessageRef nothingOpen();

    @Name("Checked")
    MessageRef checked(@Arg("when") String when);

    @Name("Stack unreadable")
    MessageRef unreadable();

    @Name("Meter")
    MessageRef meter(@Arg("subject") String subject, @Arg("value") long value, @Arg("limit") long limit);

    @Name("Search field")
    MessageRef search();

    @Name("Search hint")
    MessageRef searchHint();

    @Name("Recognised words")
    MessageRef readAs();

    @Name("Least days")
    MessageRef days(@Arg("days") int days);

    @Name("Any other word")
    MessageRef quoted(@Arg("word") String word);

    @Name("Today")
    MessageRef today();

    @Name("Yesterday")
    MessageRef yesterday();

    @Name("Older entries")
    MessageRef older();

    @Name("Open for minutes")
    MessageRef afterMinutes(@Arg("minutes") long minutes);

    @Name("Open for hours")
    MessageRef afterHours(@Arg("hours") long hours, @Arg("minutes") long minutes);
}
