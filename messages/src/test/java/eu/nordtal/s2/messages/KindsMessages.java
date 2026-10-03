package eu.nordtal.s2.messages;

import eu.nordtal.s2.messages.context.PlayerContext;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.Name;
import eu.nordtal.s2.messages.spec.TextFormat;
import eu.nordtal.s2.messages.value.GameContent;
import eu.nordtal.s2.messages.value.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** One message per value kind, for {@link ValueKindsTest}. */
@MessageSpec(value = "kinds", format = TextFormat.PLAIN)
public interface KindsMessages {

    @Name("Days")
    MessageRef days(@Arg("n") int n);

    @Name("Time left")
    MessageRef left(@Arg("left") Duration left);

    @Name("Time left, short")
    MessageRef leftShort(@Arg("left") Duration left);

    @Name("Time left, clock")
    MessageRef leftClock(@Arg("left") Duration left);

    @Name("Price")
    MessageRef price(@Arg("price") Money price);

    @Name("Start")
    MessageRef starts(@Arg("at") Instant at);

    @Name("Players")
    MessageRef players(@Arg("names") List<String> names);

    @Name("Open")
    MessageRef open(@Arg("open") boolean open);

    @Name("Winner")
    MessageRef won(@Arg("winner") PlayerContext winner);

    @Name("Found")
    MessageRef found(@Arg("item") GameContent item);
}
