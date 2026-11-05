package eu.nordtal.season.steward.texts;

import eu.nordtal.season.common.season.SeasonOpening;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;
import java.time.Duration;
import java.time.Instant;

/** The texts of the proxy's Router tab: the launch date, maintenance, the game mode and their dialogs. */
@Name("Router")
public interface RouterTab {

    @Name("Tab")
    MessageRef tab();

    @Name("Where members land now")
    MessageRef now(@Arg("now") Now now);

    @Name("The gate in a word")
    MessageRef gate(@Arg("gate") Gate gate);

    @Name("No launch date")
    MessageRef noLaunchDate();

    @Name("Opens at")
    MessageRef opensAt(@Arg("at") @Example("2026-11-13T17:00:00Z") Instant at);

    @Name("Maintenance for")
    MessageRef maintenanceFor(@Arg("length") @Example("PT12M") Duration length);

    @Name("Switch failed")
    MessageRef switchFailed(@Arg("server") @Example("SMP") String server);

    @Name("Until the target runs")
    MessageRef untilRuns(@Arg("server") @Example("SMP") String server);

    @Name("Switch waiting")
    MessageRef switchWaiting(@Arg("server") @Example("SMP") String server);

    @Name("Admins go through")
    MessageRef adminsThrough();

    @Name("Admins go to")
    MessageRef adminsTo(@Arg("server") @Example("SMP") String server);

    @Name("Admins wait too")
    MessageRef adminsWait();

    @Name("Server command works")
    MessageRef serverWorks();

    @Name("No access without a period")
    MessageRef noAccess();

    @Name("Online heading")
    MessageRef online();

    @Name("Online in the waiting room")
    MessageRef inWaitingRoom();

    @Name("Online on a server")
    MessageRef onServer(@Arg("server") @Example("SMP") String server);

    @Name("Nobody online")
    MessageRef nobody();

    @Name("Launch card")
    MessageRef launchTitle();

    @Name("Opening badge")
    MessageRef opening(@Arg("opening") SeasonOpening opening);

    @Name("Launch date")
    MessageRef launchDate();

    @Name("Not set")
    MessageRef notSet();

    @Name("Closed screen")
    MessageRef closedScreen();

    @Name("Set launch date")
    MessageRef setLaunchDate();

    @Name("Change")
    MessageRef change();

    @Name("Launch now")
    MessageRef launchNow();

    @Name("Close season")
    MessageRef closeSeason();

    @Name("Key on confirm")
    MessageRef keyTip();

    @Name("Key next")
    MessageRef keyNext();

    @Name("Not in the past")
    MessageRef notInPast(@Arg("earliest") @Example("2026-11-04T13:20:00Z") Instant earliest);

    @Name("Closes until then")
    MessageRef closesUntil();

    @Name("Maintenance card")
    MessageRef maintenance();

    @Name("Maintenance badge")
    MessageRef maintenanceOn(@Arg("on") boolean on);

    @Name("No effect yet")
    MessageRef noEffect();

    @Name("No effect yet tip")
    MessageRef noEffectTip();

    @Name("Since")
    MessageRef since();

    @Name("Waiting")
    MessageRef waiting();

    @Name("Players word")
    MessageRef players(@Arg("count") @Example("8") int count);

    @Name("Last on")
    MessageRef lastOn();

    @Name("Never")
    MessageRef never();

    @Name("Start maintenance")
    MessageRef startMaintenance();

    @Name("End maintenance")
    MessageRef endMaintenance();

    @Name("Game mode card")
    MessageRef gameMode();

    @Name("Active")
    MessageRef active();

    @Name("Access needed")
    MessageRef accessNeeded();

    @Name("Every member")
    MessageRef everyMember();

    @Name("Server badge")
    MessageRef server(@Arg("state") ServerState state);

    @Name("Started by hand tip")
    MessageRef byHandTip(@Arg("by") @Example("till") String by, @Arg("at") @Example("2026-11-04T13:02:00Z") Instant at);

    @Name("Start")
    MessageRef start();

    @Name("Start tip")
    MessageRef startTip(
            @Arg("server") @Example("SMP") String server, @Arg("mode") @Example("Hunger Games") String mode);

    @Name("Stop")
    MessageRef stop();

    @Name("Stop tip")
    MessageRef stopTip(@Arg("server") @Example("SMP") String server, @Arg("mode") @Example("Hunger Games") String mode);

    @Name("Switch")
    MessageRef switchMode();

    @Name("Switch disabled")
    MessageRef switchBusy();

    @Name("Admins on it")
    MessageRef adminsOnIt(@Arg("count") @Example("2") int count);

    @Name("Players online on a mode")
    MessageRef playersOn(@Arg("count") @Example("15") int count);

    @Name("Mode switch")
    MessageRef modeSwitch();

    @Name("Last switch")
    MessageRef lastSwitch();

    @Name("Run badge")
    MessageRef run(@Arg("state") RunState state);

    @Name("Running for")
    MessageRef runningFor(@Arg("length") @Example("PT1M48S") Duration length);

    @Name("Failed after")
    MessageRef failedAfter(@Arg("length") @Example("PT5M12S") Duration length);

    @Name("Took")
    MessageRef took(@Arg("length") @Example("PT2M41S") Duration length);

    @Name("Step to the waiting room")
    MessageRef stepToWaitingRoom();

    @Name("Step stop")
    MessageRef stepStop(@Arg("server") @Example("Hunger Games") String server);

    @Name("Step start")
    MessageRef stepStart(@Arg("server") @Example("SMP") String server);

    @Name("Step to the server")
    MessageRef stepToServer(@Arg("server") @Example("SMP") String server);

    @Name("Arrived")
    MessageRef arrived(@Arg("arrived") @Example("31") int arrived, @Arg("total") @Example("31") int total);

    @Name("Released")
    MessageRef released(@Arg("released") @Example("0") int released, @Arg("total") @Example("24") int total);

    @Name("Without access, counted")
    MessageRef withoutAccessCount(@Arg("count") @Example("7") int count);

    @Name("Stay for maintenance")
    MessageRef stayMaintenance(@Arg("count") @Example("29") int count);

    @Name("Finished")
    MessageRef finished();

    @Name("Moved")
    MessageRef moved();

    @Name("Moved to")
    MessageRef movedTo(@Arg("count") @Example("24") int count, @Arg("server") @Example("SMP") String server);

    @Name("Mode")
    MessageRef mode();

    @Name("Requested by")
    MessageRef requestedBy();

    @Name("At a time")
    MessageRef atTime(@Arg("at") @Example("2026-11-04T13:28:00Z") Instant at);

    @Name("Try again")
    MessageRef tryAgain();

    @Name("Back to")
    MessageRef backTo(@Arg("mode") @Example("Hunger Games") String mode);

    @Name("Open the run log")
    MessageRef openLog();

    @Name("Reason")
    MessageRef reason();

    @Name("Reason placeholder")
    MessageRef reasonPlaceholder();

    @Name("Step of two")
    MessageRef step(@Arg("step") @Example("1") int step);

    @Name("Back")
    MessageRef back();

    @Name("Continue")
    MessageRef next();

    @Name("Launch now title")
    MessageRef launchNowTitle();

    @Name("Now, at")
    MessageRef nowAt(@Arg("at") @Example("2026-11-04T13:20:00Z") Instant at);

    @Name("Access periods")
    MessageRef periods();

    @Name("Periods start now")
    MessageRef periodsStartNow(@Arg("count") @Example("14") int count);

    @Name("Bought in advance")
    MessageRef boughtInAdvance();

    @Name("Members land in")
    MessageRef landIn();

    @Name("Close title")
    MessageRef closeTitle();

    @Name("Members to the closed screen")
    MessageRef toClosedScreen(@Arg("count") @Example("21") int count);

    @Name("None")
    MessageRef none();

    @Name("Admins")
    MessageRef admins();

    @Name("Admins stay")
    MessageRef adminsStay(@Arg("count") @Example("2") int count);

    @Name("Lost")
    MessageRef lost();

    @Name("Nothing")
    MessageRef nothing();

    @Name("Open again")
    MessageRef openAgain();

    @Name("Switch title")
    MessageRef switchTitle(@Arg("mode") @Example("SMP") String mode);

    @Name("Round ends")
    MessageRef roundEnds();

    @Name("Players")
    MessageRef playersHeading();

    @Name("Without access")
    MessageRef withoutAccess();

    @Name("No access after")
    MessageRef noAccessAfter();

    @Name("Start maintenance title")
    MessageRef maintenanceTitle();

    @Name("End maintenance title")
    MessageRef endMaintenanceTitle();

    @Name("Members to the waiting room")
    MessageRef toWaitingRoom(@Arg("count") @Example("21") int count);

    @Name("Members let in")
    MessageRef letIn(@Arg("count") @Example("8") int count);

    @Name("Now heading")
    MessageRef nowHeading();

    @Name("Waiting for the switch")
    MessageRef waitingSwitch(@Arg("count") @Example("31") int count);

    @Name("After the switch")
    MessageRef afterSwitch();

    @Name("The waiting room")
    MessageRef waitingRoom();

    @Name("Change the date title")
    MessageRef dateTitle();

    @Name("Set the date title")
    MessageRef setDateTitle();

    @Name("Periods move")
    MessageRef periodsMove(@Arg("count") @Example("14") int count, @Arg("days") @Example("7") int days);

    @Name("Periods start then")
    MessageRef periodsStartThen(@Arg("count") @Example("14") int count);

    @Name("Opens by itself")
    MessageRef opensTitle();

    @Name("Opens")
    MessageRef opens();

    @Name("Closed")
    MessageRef closed();

    @Name("From now until then")
    MessageRef untilThen();

    @Name("Change to")
    MessageRef changeTo(@Arg("at") @Example("2026-11-20T17:00:00Z") Instant at);

    @Name("Open on")
    MessageRef openOn(@Arg("at") @Example("2026-11-20T17:00:00Z") Instant at);

    @Name("Close until title")
    MessageRef closeUntilTitle(@Arg("at") @Example("2026-11-06T17:00:00Z") Instant at);

    @Name("Members to the countdown")
    MessageRef toCountdown(@Arg("count") @Example("21") int count);

    @Name("Closed for")
    MessageRef closedFor();

    @Name("Closed for how long")
    MessageRef closedForLength(@Arg("length") @Example("PT48H") Duration length);

    @Name("From now")
    MessageRef fromNow();

    @Name("Close until")
    MessageRef closeUntil(@Arg("at") @Example("2026-11-06T17:00:00Z") Instant at);

    @Name("Done")
    MessageRef done(@Arg("change") Change change);

    /** The large word of the status line. */
    enum Now {
        CLOSED,
        COUNTDOWN,
        WAITING,
        SMP,
        HUNGER_GAMES
    }

    /** The gate for members in a few words. */
    enum Gate {
        CLOSED,
        WAIT,
        OPEN
    }

    /** A mode's server as the game mode card shows it. */
    enum ServerState {
        RUNNING,
        RESTING,
        BY_HAND,
        STOPPED,
        STARTING,
        FAILED
    }

    /** A switch run as its badge shows it. */
    enum RunState {
        WAITING,
        RUNNING,
        DONE,
        FAILED
    }

    /** A change the tab made, for its toast. */
    enum Change {
        LAUNCH,
        LAUNCH_NOW,
        CLOSE,
        MAINTENANCE_ON,
        MAINTENANCE_OFF,
        SWITCH
    }
}
