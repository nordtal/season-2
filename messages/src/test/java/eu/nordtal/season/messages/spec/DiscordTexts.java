package eu.nordtal.season.messages.spec;

import eu.nordtal.season.messages.MessageRef;

/** A message value that takes a key of its own bundle. */
@MessageSpec("discord-texts")
@Shown(Display.DISCORD_TEXT)
@Format(TextFormat.DISCORD_MARKDOWN)
public interface DiscordTexts {

    @Name("Buy")
    @Shown(Display.DISCORD_BUTTON)
    MessageRef buy();

    @Name("Steps")
    MessageRef steps(@Arg("button") @Takes("buy") MessageRef button);
}
