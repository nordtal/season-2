package eu.nordtal.season.messages;

import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.messages.value.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@MessageSpec(value = "kinds", format = TextFormat.PLAIN)
@Shown(Display.CHAT)
public interface KindsMessages {

    @Name("Days")
    MessageRef days(@Arg("n") @Example("3") int n);

    @Name("Time left")
    MessageRef left(@Arg("left") @Example("PT2H5M") Duration left);

    @Name("Time left, short")
    MessageRef leftShort(@Arg("left") @Example("PT2H5M") Duration left);

    @Name("Time left, clock")
    MessageRef leftClock(@Arg("left") @Example("PT2H5M") Duration left);

    @Name("Price")
    MessageRef price(@Arg("price") @Example("300") Money price);

    @Name("Start")
    MessageRef starts(@Arg("at") @Example("2026-10-03T18:40:00Z") Instant at);

    @Name("Deadline")
    MessageRef deadline(@Arg("at") @Example("2026-10-03T18:40:00Z") Instant at);

    @Name("Players")
    MessageRef players(@Arg("names") @Example("Alex,Sam,Kim") List<String> names);

    @Name("Open")
    MessageRef open(@Arg("open") boolean open);

    @Name("Winner")
    MessageRef won(@Arg("winner") PlayerContext winner);

    @Name("Found")
    MessageRef found(@Arg("item") @Example("item.minecraft.diamond") GameContent item);

    @Name("Next")
    MessageRef next(@Arg("what") @Example("values/values.missing.text") MessageRef what);
}
