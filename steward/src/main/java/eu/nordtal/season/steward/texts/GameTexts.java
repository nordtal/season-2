package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;

/** The texts of the game panels: the Hunger Games round, the milestones and the game settings. */
@Name("Games")
public interface GameTexts {

    @Name("Round")
    MessageRef round();

    @Name("No round")
    MessageRef noRound();

    @Name("Registered")
    MessageRef registered(@Arg("count") @Example("14") int count);

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

    @Name("Track")
    MessageRef track();

    @Name("No track")
    MessageRef noTrack();

    @Name("Milestones")
    MessageRef milestones();

    @Name("All milestones")
    MessageRef allMilestones();

    @Name("Unlock question")
    MessageRef unlockAsk(@Arg("name") @Example("The Nether") String name);

    @Name("Unlock")
    MessageRef unlock();

    @Name("Unlocked")
    MessageRef unlocked(@Arg("at") @Example("23 Oct 2026") String at);

    @Name("Active")
    MessageRef active();

    @Name("Locked")
    MessageRef locked();

    @Name("Tasks")
    MessageRef tasks(@Arg("finished") @Example("2") int finished, @Arg("total") @Example("5") int total);

    @Name("Progress")
    MessageRef progress(@Arg("name") @Example("The Nether") String name);

    @Name("Done at")
    MessageRef doneAt(@Arg("at") @Example("23 Oct 2026") String at);

    @Name("Complete question")
    MessageRef completeAsk(@Arg("name") @Example("Mine 64 diamonds") String name);

    @Name("Complete note")
    MessageRef completeNote(@Arg("amount") @Example("40") int amount, @Arg("target") @Example("64") int target);

    @Name("Complete")
    MessageRef complete();

    @Name("Objective")
    MessageRef objective();

    @Name("Milestone")
    MessageRef milestone();

    @Name("Remove question")
    MessageRef removeAsk(@Arg("name") @Example("Mine 64 diamonds") String name);

    @Name("Keep")
    MessageRef keep();

    @Name("Remove it")
    MessageRef removeIt();

    @Name("Move up")
    MessageRef moveUp(@Arg("name") @Example("Mine 64 diamonds") String name);

    @Name("Move down")
    MessageRef moveDown(@Arg("name") @Example("Mine 64 diamonds") String name);

    @Name("Advancement")
    MessageRef advancement();

    @Name("Item")
    MessageRef item();

    @Name("Prize")
    MessageRef prize();

    @Name("Region")
    MessageRef region();

    @Name("Total weight")
    MessageRef totalWeight(@Arg("total") @Example("100") int total);

    @Name("Chance")
    MessageRef chance();

    @Name("Aura of")
    MessageRef auraOf(@Arg("name") @Example("The Nether") String name);

    @Name("Spins of")
    MessageRef spinsOf(@Arg("name") @Example("The Nether") String name);

    @Name("Arena")
    MessageRef arena();

    @Name("Duel types")
    MessageRef duelTypes();

    @Name("Platform")
    MessageRef platform();

    @Name("Loadout")
    MessageRef loadout();

    @Name("Min")
    MessageRef min();

    @Name("Max")
    MessageRef max();
}
