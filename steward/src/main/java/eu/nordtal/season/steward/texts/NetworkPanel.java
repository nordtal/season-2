package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;

/** The texts of the network panel with one card or row per service and the online players. */
@Name("Network")
public interface NetworkPanel {

    @Name("Title")
    MessageRef title();

    @Name("No service")
    MessageRef noService();

    @Name("Players")
    MessageRef players();

    @Name("Players online")
    MessageRef playersOnline(@Arg("count") @Example("12") int count);

    @Name("Up")
    MessageRef up(@Arg("since") @Example("3 h 12 min") String since);

    @Name("Not running")
    MessageRef notRunning();

    @Name("CPU")
    MessageRef cpu(@Arg("percent") @Example("12 %") String percent);

    @Name("Memory")
    MessageRef memory(@Arg("used") @Example("1.4 GiB") String used);

    @Name("Memory of")
    MessageRef memoryOf(@Arg("used") @Example("1.4 GiB") String used, @Arg("limit") @Example("4 GiB") String limit);

    @Name("Open")
    MessageRef open(@Arg("service") @Example("smp") String service);

    @Name("No image")
    MessageRef noImage();

    @Name("No name")
    MessageRef noName();

    @Name("More")
    MessageRef more(@Arg("count") @Example("4") int count);
}
