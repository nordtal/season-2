package eu.nordtal.s2.steward.texts;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Name;
import java.time.Instant;

/** The frame around every page: the navigation, the search, the account menu and the stuck door. */
@Name("Shell")
public interface Shell {

    @Name("Steward")
    MessageRef steward();

    @Name("Page")
    MessageRef page(@Arg("page") Page page);

    @Name("Page note")
    MessageRef note(@Arg("page") Page page);

    @Name("Service note")
    MessageRef serviceNote(@Arg("name") String name);

    @Name("Navigation")
    MessageRef navigation();

    @Name("Search pages")
    MessageRef searchPages();

    @Name("Search")
    MessageRef search();

    @Name("Jump")
    MessageRef jump();

    @Name("Search label")
    MessageRef searchLabel();

    @Name("Search placeholder")
    MessageRef searchPlaceholder();

    @Name("Still reading")
    MessageRef stillReading();

    @Name("Nothing found")
    MessageRef nothingFound();

    @Name("Runs")
    MessageRef runs();

    @Name("Settings")
    MessageRef settings();

    @Name("Run hit")
    MessageRef runHit(@Arg("run") int run, @Arg("kind") String kind);

    @Name("Run state")
    MessageRef runWhen(@Arg("status") String status, @Arg("requested") Instant requested);

    @Name("Stuck")
    MessageRef stuck();

    @Name("Account")
    MessageRef account();

    @Name("Account of")
    MessageRef accountOf(@Arg("name") String name);

    @Name("Unknown")
    MessageRef unknown();

    @Name("Discord id")
    MessageRef discord(@Arg("id") String id);

    @Name("Sign out")
    MessageRef signOut();

    @Name("Pages")
    MessageRef pages();

    /** A page of the navigation, which names it and says in one line what it is for. */
    enum Page {
        OVERVIEW,
        SERVICES,
        OPERATIONS,
        UPDATES,
        BACKUPS,
        ALERTS,
        SEASON,
        ANNOUNCEMENTS,
        ACCESS,
        PAYMENTS,
        JOURNAL
    }
}
