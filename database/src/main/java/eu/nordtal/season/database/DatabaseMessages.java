package eu.nordtal.season.database;

import eu.nordtal.season.database.access.PlayerCard;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.MilestoneContext;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.Format;
import eu.nordtal.season.messages.spec.Key;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Glyph;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Every message of the database bundle: refusals of a write, a player's card, and the announcements the bot posts.
 * A server and Steward write an announcement into the bot's inbox, and the bot renders it in each language.
 */
@MessageSpec("database")
public interface DatabaseMessages {

    /** The messages; stateless, so one instance serves every caller. */
    DatabaseMessages MESSAGES = MessageSpecs.create(DatabaseMessages.class);

    UpdateRefusals update();

    SeasonRefusals season();

    ServerRefusals server();

    PlayerTexts player();

    Announcements announcement();

    /** What the bot posts into each language's announcement channel; a glyph would be a box there, so none is used. */
    @Name("Announcements")
    @Shown(Display.DISCORD_MESSAGE)
    @Format(TextFormat.DISCORD_MARKDOWN)
    interface Announcements {

        /** An admin's own words, posted as written: the markdown in them is theirs. */
        @Name("Words an admin wrote")
        @Format(TextFormat.PLAIN)
        MessageRef words(@Arg("text") @Example("The End opens tonight at eight.") String text);

        @Name("Milestone")
        MessageRef milestone(@Arg("milestone") MilestoneContext milestone);

        @Key("milestone")
        Milestone milestoneSection();

        @Name("Milestone")
        interface Milestone {

            @Name("Border")
            MessageRef border(@Arg("milestone") MilestoneContext milestone);

            @Name("Nether")
            MessageRef nether(@Arg("milestone") MilestoneContext milestone);

            @Name("End")
            MessageRef end(@Arg("milestone") MilestoneContext milestone);
        }
    }

    @Name("Players")
    interface PlayerTexts {

        /** What a player's name shows on hover in game, wherever a message names them. */
        @Name("Card")
        @Shown(Display.GUI)
        MessageRef card(
                @Arg("player") PlayerContext player,
                @Arg("role") @Example("admin") PlayerCard.Role role,
                @Arg("crest") @Example("crest-4") Glyph crest,
                @Arg("tier") @Example("4") int tier,
                @Arg("playtime") @Example("PT12H5M") Duration playtime);
    }

    @Name("Update runs")
    interface UpdateRefusals {

        @Name("A run is still open")
        MessageRef runOpen(
                @Arg("id") long id,
                @Arg("kind") @Example("update") UpdateKind kind,
                @Arg("status") @Example("pending") UpdateStatus status);

        @Name("Already down")
        MessageRef alreadyHeld(@Arg("services") List<String> services);
    }

    @Name("Season dates")
    interface SeasonRefusals {

        @Name("Opening in the past")
        MessageRef launchInThePast(@Arg("at") Instant at, @Arg("clear") String clear);

        @Name("Paid time in the past")
        MessageRef smpStartInThePast(@Arg("at") Instant at, @Arg("clear") String clear);

        @Name("Opening after paid time")
        MessageRef launchAfterSmpStart(@Arg("at") Instant at, @Arg("smp") Instant smpStart);

        @Name("Paid time already running")
        MessageRef smpRunning();

        @Name("Paid time before the opening")
        MessageRef smpStartBeforeLaunch(@Arg("at") Instant at, @Arg("launch") Instant launch);
    }

    @Name("Server actions")
    interface ServerRefusals {

        @Name("No active milestone")
        MessageRef noActiveMilestone();

        @Name("No such objective")
        MessageRef noSuchObjective(@Arg("key") String key);

        @Name("Milestone not active")
        MessageRef milestoneNotActive(@Arg("key") String key, @Arg("active") String active);

        @Name("Wrong phase")
        MessageRef wrongPhase(@Arg("phase") String phase);

        @Name("No game")
        MessageRef noGame();

        @Name("Wrong state")
        MessageRef wrongState(@Arg("state") String state);

        @Name("Below the hard minimum")
        MessageRef belowHardMinimum(@Arg("minimum") long minimum, @Arg("count") long count);

        @Name("Below the recommended minimum")
        MessageRef belowSoftMinimum(@Arg("count") long count, @Arg("minimum") long minimum);

        @Name("Player not here")
        MessageRef notHere();

        @Name("Direct message not delivered")
        MessageRef notDelivered();
    }
}
