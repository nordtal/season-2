package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;

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
    MessageRef checked(@Arg("when") @Example("2 min ago") String when);

    @Name("Stack unreadable")
    MessageRef unreadable();

    @Name("Meter")
    MessageRef meter(
            @Arg("subject") @Example("database dump") String subject,
            @Arg("value") @Example("26") long value,
            @Arg("limit") @Example("30") long limit);

    @Name("Search field")
    MessageRef search();

    @Name("Search hint")
    MessageRef searchHint();

    @Name("Recognised words")
    MessageRef readAs();

    @Name("Least days")
    MessageRef days(@Arg("days") @Example("3") int days);

    @Name("Any other word")
    MessageRef quoted(@Arg("word") @Example("chunky") String word);

    @Name("Today")
    MessageRef today();

    @Name("Yesterday")
    MessageRef yesterday();

    @Name("Older entries")
    MessageRef older();

    @Name("Open for minutes")
    MessageRef afterMinutes(@Arg("minutes") @Example("5") long minutes);

    @Name("Open for hours")
    MessageRef afterHours(@Arg("hours") @Example("2") long hours, @Arg("minutes") @Example("15") long minutes);
}
