package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;
import java.time.Instant;

/** The texts of the frame around every page: the navigation, the search, the account menu and the stuck door. */
@Name("Shell")
public interface Shell {

    @Name("Steward")
    MessageRef steward();

    @Name("Version")
    MessageRef version(@Arg("version") @Example("0.57.0") String version);

    @Name("Page")
    MessageRef page(@Arg("page") Page page);

    @Name("Page note")
    MessageRef note(@Arg("page") Page page);

    @Name("Service note")
    MessageRef serviceNote(@Arg("name") @Example("smp") String name);

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
    MessageRef runHit(@Arg("run") @Example("42") int run, @Arg("kind") @Example("Update") String kind);

    @Name("Run state")
    MessageRef runWhen(
            @Arg("status") @Example("finished") String status,
            @Arg("requested") @Example("2026-10-23T18:40:00Z") Instant requested);

    @Name("Stuck")
    MessageRef stuck();

    @Name("Account")
    MessageRef account();

    @Name("Account of")
    MessageRef accountOf(@Arg("name") @Example("Alex") String name);

    @Name("Unknown")
    MessageRef unknown();

    @Name("Discord id")
    MessageRef discord(@Arg("id") @Example("112233445566778899") String id);

    @Name("Sign out")
    MessageRef signOut();

    @Name("Pages")
    MessageRef pages();

    /** A page of the navigation, or a group of pages that Steward's texts are listed under. */
    enum Page {
        OVERVIEW,
        SERVICES,
        OPERATIONS,
        UPDATES,
        BACKUPS,
        ALERTS,
        SEASON,
        TRANSLATIONS,
        ANNOUNCEMENTS,
        ACCESS,
        PAYMENTS,
        JOURNAL,
        DESIGNS,
        EVERY,
        SEVERAL
    }
}
