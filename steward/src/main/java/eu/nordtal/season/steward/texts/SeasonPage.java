package eu.nordtal.season.steward.texts;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;
import java.time.Instant;

/** The texts of the season page for the phase, its two dates and the network settings. */
@Name("Season")
public interface SeasonPage {

    @Name("Title")
    MessageRef title();

    @Name("Network")
    MessageRef network();

    @Name("Phase heading")
    MessageRef phaseHeading();

    @Name("Phase")
    MessageRef phase(@Arg("phase") SeasonPhase phase);

    @Name("Now")
    MessageRef now();

    @Name("Lands on")
    MessageRef landsOn(@Arg("where") @Example("limbo") String where);

    @Name("Current")
    MessageRef current();

    @Name("Switch")
    MessageRef switchPhase();

    @Name("Switching")
    MessageRef switching();

    @Name("Switch title")
    MessageRef switchTitle(@Arg("phase") @Example("SMP") String phase);

    @Name("Phase is now")
    MessageRef phaseIsNow(@Arg("phase") @Example("SMP") String phase);

    @Name("Reason")
    MessageRef reason();

    @Name("Reason placeholder")
    MessageRef reasonPlaceholder();

    @Name("Dates")
    MessageRef dates();

    @Name("Launch")
    MessageRef launch();

    @Name("SMP start")
    MessageRef smpStart();

    @Name("SMP start removal")
    MessageRef smpStartRemoval();

    @Name("Date saved")
    MessageRef dateSaved(@Arg("date") @Example("Network opening") String date);

    @Name("Date removed")
    MessageRef dateRemoved(@Arg("date") @Example("Network opening") String date);

    @Name("Remove title")
    MessageRef removeTitle(@Arg("date") @Example("Network opening") String date);

    @Name("Saved at")
    MessageRef savedAt(@Arg("at") @Example("2026-10-23T08:40:00Z") Instant at);

    @Name("No date")
    MessageRef noDate();
}
