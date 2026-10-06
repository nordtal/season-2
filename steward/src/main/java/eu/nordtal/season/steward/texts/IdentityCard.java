package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** A person's two accounts, closed and in the popover. */
@Name("Identity")
public interface IdentityCard {

    @Name("No Discord name")
    MessageRef noDiscordName();

    @Name("Never observed")
    MessageRef neverObserved();

    @Name("Discord id")
    MessageRef discordId();

    @Name("No Discord")
    MessageRef noDiscord();

    @Name("No name yet")
    MessageRef noName();

    @Name("No Minecraft name")
    MessageRef noMinecraftName();

    @Name("Never joined")
    MessageRef neverJoined();

    @Name("Minecraft UUID")
    MessageRef minecraftUuid();

    @Name("No Minecraft")
    MessageRef noMinecraft();

    @Name("Copy")
    MessageRef copy(@Arg("what") String what);

    @Name("Open person")
    MessageRef openPerson();

    @Name("No avatar")
    MessageRef noAvatar();

    @Name("No head")
    MessageRef noHead();
}
