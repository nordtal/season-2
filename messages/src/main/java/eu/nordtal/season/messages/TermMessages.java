package eu.nordtal.season.messages;

import eu.nordtal.season.messages.spec.Describe;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.spec.TextFormat;
import java.util.List;
import java.util.Map;

/**
 * The one name of each game term in every language. A text uses no other word for a term.
 * {@code MessageCheck} refuses the words in {@link #SYNONYMS}.
 */
@MessageSpec(value = "terms", format = TextFormat.PLAIN)
@Shown({
    Display.CHAT,
    Display.ACTION_BAR,
    Display.TITLE,
    Display.SUBTITLE,
    Display.BOSS_BAR,
    Display.TAB_LIST,
    Display.GUI,
    Display.HOLOGRAM,
    Display.KICK_SCREEN,
    Display.SERVER_LIST,
    Display.DISCORD_MESSAGE,
    Display.DISCORD_EMBED,
    Display.DISCORD_EMBED_HEADING,
    Display.DISCORD_TEXT,
    Display.DISCORD_BUTTON,
    Display.DISCORD_MODAL,
    Display.DISCORD_SELECT,
    Display.DISCORD_CHANNEL,
    Display.DISCORD_COMMAND,
    Display.STEWARD,
    Display.PUSH
})
public interface TermMessages {

    /** The words that a text never writes, by the key of the term it writes instead. */
    Map<String, List<String>> SYNONYMS = Map.of(
            "access", List.of("membership", "Mitgliedschaft", "Abo"),
            "contribution-channel", List.of("payment channel", "Zahlungskanal"),
            "milestone", List.of("Etappe"),
            "period", List.of("Laufzeit"),
            "season", List.of("Staffel"),
            "smp", List.of("survival server", "Survival-Server"),
            "waiting-room", List.of("lobby", "Wartebereich"));

    Terms terms();

    @Name("Game terms")
    interface Terms {

        @Name("Access")
        @Describe("The right to join the servers for a paid or granted period.")
        MessageRef access();

        @Name("Contribution channel")
        @Describe("The Discord channel where a member pays for access.")
        MessageRef contributionChannel();

        @Name("Hunger Games")
        @Describe("The season's start event, played in teams.")
        MessageRef hungerGames();

        @Name("Milestone")
        @Describe("A step on the SMP's milestone track.")
        MessageRef milestone();

        @Name("Network")
        @Describe("All Nordtal servers behind the proxy.")
        MessageRef network();

        @Name("Period")
        @Describe("A span of days of access bought at once.")
        MessageRef period();

        @Name("Phase")
        @Describe("The season's current stage, which decides which server is open.")
        MessageRef phase();

        @Name("Season")
        @Describe("One year of Nordtal, set up from scratch.")
        MessageRef season();

        @Name("SMP")
        @Describe("The survival server.")
        MessageRef smp();

        @Name("Waiting room")
        @Describe("The server every player joins first and waits on.")
        MessageRef waitingRoom();
    }
}
