package eu.nordtal.season.database;

import eu.nordtal.season.database.access.PlayerCard;
import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Glyph;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Every message of the database bundle: refusals of a write and the card of a player. */
@MessageSpec("database")
public interface DatabaseMessages {

    DatabaseMessages MESSAGES = MessageSpecs.create(DatabaseMessages.class);

    UpdateRefusals update();

    SeasonRefusals season();

    ServerRefusals server();

    PlayerTexts player();

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

    @Name("Season launch")
    @Shown(Display.STEWARD)
    interface SeasonRefusals {

        @Name("Launch date in the past")
        MessageRef launchInThePast(
                @Arg("at") @Example("2026-11-01T17:00:00Z") Instant at,
                @Arg("now") @Example("2026-11-04T13:20:00Z") Instant now);

        @Name("Already launched")
        MessageRef alreadyLaunched(@Arg("at") @Example("2026-11-13T17:00:00Z") Instant at);

        @Name("No launch date")
        MessageRef notSet();

        @Name("Not launched")
        MessageRef notLaunched();
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
                @Arg("setting") @Example("duel-kits") String setting,
                @Arg("rule") @Example("most-entries") SettingRule rule,
                @Arg("value") @Example("5") @Nullable String value);

        @Name("Stack changed")
        @Shown(Display.STEWARD)
        MessageRef stackChanged();

        @Name("Not editable")
        @Shown(Display.STEWARD)
        MessageRef notEditable();
    }
}
