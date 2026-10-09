package eu.nordtal.season.steward.texts;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import java.time.Instant;

/** The season page: the phase, its two dates and the network's settings. */
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
    MessageRef landsOn(@Arg("where") String where);

    @Name("Current")
    MessageRef current();

    @Name("Switch")
    MessageRef switchPhase();

    @Name("Switching")
    MessageRef switching();

    @Name("Switch title")
    MessageRef switchTitle(@Arg("phase") String phase);

    @Name("Phase is now")
    MessageRef phaseIsNow(@Arg("phase") String phase);

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
    MessageRef dateSaved(@Arg("date") String date);

    @Name("Date removed")
    MessageRef dateRemoved(@Arg("date") String date);

    @Name("Remove title")
    MessageRef removeTitle(@Arg("date") String date);

    @Name("Saved at")
    MessageRef savedAt(@Arg("at") Instant at);

    @Name("No date")
    MessageRef noDate();
}
