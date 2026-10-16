package eu.nordtal.season.messages.spec.layout;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.Format;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.spec.Takes;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.value.Money;

/** The texts the layout tests and the shared vectors place, shown as an embed's unless a key says otherwise. */
@MessageSpec("layouts")
@Shown(Display.DISCORD_EMBED)
@Format(TextFormat.DISCORD_MARKDOWN)
public interface LayoutTexts {

    @Name("Title")
    @Shown(Display.DISCORD_EMBED_HEADING)
    MessageRef title();

    @Name("Prices")
    @Shown(Display.DISCORD_EMBED_HEADING)
    MessageRef prices();

    @Name("Tier")
    MessageRef tier(@Arg("days") int days, @Arg("price") Money price);

    @Name("Donation")
    MessageRef donation(@Arg("amount") Money amount);

    @Name("Buy")
    @Shown(Display.DISCORD_BUTTON)
    MessageRef buy();

    @Name("Choose")
    @Shown(Display.DISCORD_SELECT)
    MessageRef choose();

    @Name("Language")
    @Shown(Display.DISCORD_SELECT)
    @Format(TextFormat.PLAIN)
    MessageRef language(@Arg("language") String language);

    @Name("Steps")
    MessageRef steps(@Arg("button") @Takes("buy") MessageRef button);

    @Name("Untaken")
    MessageRef untaken(@Arg("what") MessageRef what);

    @Name("Count")
    MessageRef count(@Arg("amount") int amount);
}
