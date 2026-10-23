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

/** Every message of the database bundle: refusals of a write, the card of a player and the bot's announcements. */
@MessageSpec("database")
public interface DatabaseMessages {

    DatabaseMessages MESSAGES = MessageSpecs.create(DatabaseMessages.class);

    UpdateRefusals update();

    SeasonRefusals season();

    ServerRefusals server();

    PlayerTexts player();

    Announcements announcement();

    /** What the bot posts into the announcement channel of each language. A glyph would be a box there. */
    @Name("Announcements")
    @Shown(Display.DISCORD_MESSAGE)
    @Format(TextFormat.DISCORD_MARKDOWN)
    interface Announcements {

        /** An admin's words, posted as written. */
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

        /** What the name of a player shows on hover in game. */
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
    @Shown(Display.STEWARD)
    interface UpdateRefusals {

        @Name("A run is still open")
        MessageRef runOpen(
                @Arg("id") @Example("42") long id,
                @Arg("kind") @Example("update") UpdateKind kind,
                @Arg("status") @Example("pending") UpdateStatus status);

        @Name("Already down")
        MessageRef alreadyHeld(@Arg("services") @Example("smp,proxy") List<String> services);
    }

    @Name("Season dates")
    @Shown(Display.STEWARD)
    interface SeasonRefusals {

        @Name("Opening in the past")
        MessageRef launchInThePast(
                @Arg("at") @Example("2026-10-01T18:00:00Z") Instant at,
                @Arg("clear") @Example("/season launch clear") String clear);

        @Name("Paid time in the past")
        MessageRef smpStartInThePast(
                @Arg("at") @Example("2026-10-01T18:00:00Z") Instant at,
                @Arg("clear") @Example("/season smp-start clear") String clear);

        @Name("Opening after paid time")
        MessageRef launchAfterSmpStart(
                @Arg("at") @Example("2026-11-07T18:00:00Z") Instant at,
                @Arg("smp") @Example("2026-10-31T18:00:00Z") Instant smpStart);

        @Name("Paid time already running")
        MessageRef smpRunning();

        @Name("Paid time before the opening")
        MessageRef smpStartBeforeLaunch(
                @Arg("at") @Example("2026-10-24T18:00:00Z") Instant at,
                @Arg("launch") @Example("2026-10-31T18:00:00Z") Instant launch);
    }

    @Name("Server actions")
    @Shown({Display.STEWARD, Display.CHAT})
    interface ServerRefusals {

        @Name("No active milestone")
        MessageRef noActiveMilestone();

        @Name("No such objective")
        MessageRef noSuchObjective(@Arg("key") @Example("mine-diamonds") String key);

        @Name("Milestone not active")
        MessageRef milestoneNotActive(
                @Arg("key") @Example("the-end") String key, @Arg("active") @Example("the-nether") String active);

        @Name("Wrong phase")
        MessageRef wrongPhase(@Arg("phase") @Example("smp") String phase);

        @Name("No game")
        MessageRef noGame();

        @Name("Wrong state")
        MessageRef wrongState(@Arg("state") @Example("running") String state);

        @Name("Below the hard minimum")
        MessageRef belowHardMinimum(@Arg("minimum") @Example("4") long minimum, @Arg("count") @Example("3") long count);

        @Name("Below the recommended minimum")
        MessageRef belowSoftMinimum(
                @Arg("count") @Example("10") long count, @Arg("minimum") @Example("16") long minimum);

        @Name("Player not here")
        @Shown(Display.STEWARD)
        MessageRef notHere();

        @Name("Direct message not delivered")
        @Shown(Display.STEWARD)
        MessageRef notDelivered();

        @Name("Settings refused")
        @Shown(Display.STEWARD)
        MessageRef settingsRefused(
                @Arg("service") @Example("smp") String service,
                @Arg("detail") @Example("view-distance must be between 2 and 32") String detail);

        @Name("Stack changed")
        @Shown(Display.STEWARD)
        MessageRef stackChanged();
    }
}
