package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** The network panel: one card or row per service, and who is online. */
@Name("Network")
public interface NetworkPanel {

    @Name("Title")
    MessageRef title();

    @Name("No service")
    MessageRef noService();

    @Name("Players")
    MessageRef players();

    @Name("Up")
    MessageRef up(@Arg("since") String since);

    @Name("Not running")
    MessageRef notRunning();

    @Name("CPU")
    MessageRef cpu(@Arg("percent") String percent);

    @Name("Memory")
    MessageRef memory(@Arg("used") String used);

    @Name("Memory of")
    MessageRef memoryOf(@Arg("used") String used, @Arg("limit") String limit);

    @Name("Open")
    MessageRef open(@Arg("service") String service);

    @Name("No image")
    MessageRef noImage();

    @Name("No name")
    MessageRef noName();

    @Name("More")
    MessageRef more(@Arg("count") int count);
}
