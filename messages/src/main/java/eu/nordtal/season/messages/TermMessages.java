package eu.nordtal.season.messages;

import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Describe;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.value.Case;
import eu.nordtal.season.messages.value.Example;
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

        @Name("Block")
        @Describe("The unit a distance on a Minecraft map is counted in.")
        MessageRef block(@Arg("n") @Example("2") long n);

        @Name("Contribution channel")
        @Describe("The Discord channel where a member pays for access.")
        MessageRef contributionChannel();

        @Name("Extra spin")
        @Describe("A spin of the wheel that a finished objective or an admin gives beyond the daily ones.")
        MessageRef extraSpin(@Arg("n") @Example("2") long n);

        @Name("Hunger Games")
        @Describe("The season's start event, played in teams.")
        MessageRef hungerGames();

        @Name("Kill")
        @Describe("A player of another team who dies by a player's hand in the Hunger Games.")
        MessageRef kill(@Arg("n") @Example("2") long n);

        @Name("Milestone")
        @Describe("A step on the SMP's milestone track.")
        MessageRef milestone(@Arg("n") @Example("2") long n, @Arg("case") @Example("nominative") Case grammaticalCase);

        @Name("Network")
        @Describe("All Nordtal servers behind the proxy.")
        MessageRef network();

        @Name("Objective")
        @Describe("A task on the SMP that the players finish together.")
        MessageRef objective(@Arg("n") @Example("2") long n, @Arg("case") @Example("nominative") Case grammaticalCase);

        @Name("Participant")
        @Describe("A player who takes part in the Hunger Games.")
        MessageRef participant(@Arg("n") @Example("2") long n);

        @Name("Period")
        @Describe("A span of days of access bought at once.")
        MessageRef period();

        @Name("Phase")
        @Describe("The season's current stage, which decides which server is open.")
        MessageRef phase();

        @Name("Player")
        @Describe("Someone who plays on the servers.")
        MessageRef player(@Arg("n") @Example("2") long n, @Arg("case") @Example("nominative") Case grammaticalCase);

        @Name("Season")
        @Describe("One year of Nordtal, set up from scratch.")
        MessageRef season();

        @Name("SMP")
        @Describe("The survival server.")
        MessageRef smp();

        @Name("Spin")
        @Describe("One turn of the SMP's wheel.")
        MessageRef spin(@Arg("n") @Example("2") long n);

        @Name("Team")
        @Describe("The players who play the Hunger Games together.")
        MessageRef team(@Arg("n") @Example("2") long n);

        @Name("Waiting room")
        @Describe("The server every player joins first and waits on.")
        MessageRef waitingRoom();
    }
}
