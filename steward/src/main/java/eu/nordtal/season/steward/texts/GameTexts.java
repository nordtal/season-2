package eu.nordtal.season.steward.texts;

import eu.nordtal.season.database.inbox.InboxStatus;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** The game panels: the hunger games' round, a request's outcome, the milestone track and its editor. */
@Name("Games")
public interface GameTexts {

    @Name("Round")
    MessageRef round();

    @Name("No round")
    MessageRef noRound();

    @Name("Registered")
    MessageRef registered(@Arg("count") int count);

    @Name("Counting down")
    MessageRef countingDown();

    @Name("Running")
    MessageRef running();

    @Name("Start round")
    MessageRef startRound();

    @Name("Start question")
    MessageRef startAsk(@Arg("anyway") boolean anyway);

    @Name("No way back")
    MessageRef noWayBack();

    @Name("Start")
    MessageRef start(@Arg("anyway") boolean anyway);

    @Name("Outcome")
    MessageRef outcome(@Arg("status") InboxStatus status);

    @Name("Track")
    MessageRef track();

    @Name("No track")
    MessageRef noTrack();

    @Name("Milestones")
    MessageRef milestones();

    @Name("All milestones")
    MessageRef allMilestones();

    @Name("Unlock question")
    MessageRef unlockAsk(@Arg("name") String name);

    @Name("Unlock")
    MessageRef unlock();

    @Name("Unlocked")
    MessageRef unlocked(@Arg("at") String at);

    @Name("Active")
    MessageRef active();

    @Name("Locked")
    MessageRef locked();

    @Name("Tasks")
    MessageRef tasks(@Arg("finished") int finished, @Arg("total") int total);

    @Name("Progress")
    MessageRef progress(@Arg("name") String name);

    @Name("Done at")
    MessageRef doneAt(@Arg("at") String at);

    @Name("Complete question")
    MessageRef completeAsk(@Arg("name") String name);

    @Name("Complete note")
    MessageRef completeNote(@Arg("amount") int amount, @Arg("target") int target);

    @Name("Complete")
    MessageRef complete();

    @Name("Objective")
    MessageRef objective();

    @Name("Milestone")
    MessageRef milestone();

    @Name("Remove question")
    MessageRef removeAsk(@Arg("name") String name);

    @Name("Keep")
    MessageRef keep();

    @Name("Remove it")
    MessageRef removeIt();

    @Name("Move up")
    MessageRef moveUp(@Arg("name") String name);

    @Name("Move down")
    MessageRef moveDown(@Arg("name") String name);
}
