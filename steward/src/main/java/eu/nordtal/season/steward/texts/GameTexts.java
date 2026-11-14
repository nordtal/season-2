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

    @Name("Reset track")
    MessageRef resetTrack();

    @Name("Reset question")
    MessageRef resetAsk();

    @Name("Reset note")
    MessageRef resetNote();

    @Name("Reset pending")
    MessageRef resetPending();

    @Name("Reason")
    MessageRef reason();

    @Name("Reason placeholder")
    MessageRef reasonPlaceholder();

    @Name("Complete")
    MessageRef complete();

    @Name("Objective")
    MessageRef objective();

    @Name("Milestone")
    MessageRef milestone();

    @Name("Season sums")
    MessageRef seasonSum();

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

    @Name("Total weight")
    MessageRef totalWeight(@Arg("total") @Example("100") int total);

    @Name("Chance")
    MessageRef chance();

    @Name("How chance is read")
    MessageRef chanceIs(@Arg("total") @Example("1000") int total);

    @Name("Prizes")
    MessageRef prizes(@Arg("count") @Example("12") int count);

    @Name("No prizes")
    MessageRef noPrizes();

    @Name("Take in from game")
    MessageRef takeIn();

    @Name("Cancel take-in")
    MessageRef cancelTakeIn();

    @Name("Take-in failed")
    MessageRef takeInFailed();

    @Name("Resource pack")
    MessageRef pack();

    @Name("Pack building")
    MessageRef packBuilding(@Arg("at") @Example("14:02") String at);

    @Name("Active pack")
    MessageRef packActive(
            @Arg("size") @Example("2.4 MiB") String size, @Arg("at") @Example("14 Nov 2026, 14:03") String at);

    @Name("Pack build failed")
    MessageRef packFailed(@Arg("reason") @Example("an item has no texture") String reason);

    @Name("No pack build")
    MessageRef packNone();

    @Name("Build the pack again")
    MessageRef packAgain();

    @Name("Pack build asked for")
    MessageRef packAgainSent();

    @Name("Pack build not asked for")
    MessageRef packAgainFailed();

    @Name("Swap the pack live")
    MessageRef packSwap();

    @Name("Ask before a live swap")
    MessageRef packSwapAsk();

    @Name("What a live swap does")
    MessageRef packSwapNote();

    @Name("Swap waits for the build")
    MessageRef packSwapWait();

    @Name("Enchantment with its level")
    MessageRef enchantment(@Arg("name") @Example("Sharpness") String name, @Arg("level") @Example("V") String level);

    @Name("Attribute")
    MessageRef attribute(
            @Arg("amount") @Example("+7") String amount, @Arg("attribute") @Example("Attack damage") String attribute);

    @Name("Attribute in a slot")
    MessageRef attributeWorn(
            @Arg("amount") @Example("+7") String amount,
            @Arg("attribute") @Example("Attack damage") String attribute,
            @Arg("slot") ItemSlot slot);

    @Name("Unbreakable")
    MessageRef unbreakable();

    @Name("Damaged")
    MessageRef damaged(@Arg("damage") @Example("12") int damage);

    @Name("Dyed")
    MessageRef dyed(@Arg("colour") @Example("#3c44aa") String colour);

    @Name("Item model")
    MessageRef itemModel(@Arg("model") @Example("nordtal:station_rail") String model);

    @Name("Custom model data")
    MessageRef customModelData(@Arg("values") @Example("1, lit") String values);

    @Name("Item component")
    MessageRef component(@Arg("name") @Example("Repair cost") String name, @Arg("value") @Example("3") String value);

    @Name("More enchantments")
    MessageRef moreEnchantments(@Arg("count") @Example("2") int count);

    @Name("Advancements with an award")
    MessageRef awarded(@Arg("count") @Example("192") int count);

    @Name("Aura awarded")
    MessageRef auraAwarded(@Arg("total") @Example("1400") int total);

    @Name("Spins awarded")
    MessageRef spinsAwarded(@Arg("total") @Example("40") int total);

    @Name("No awards")
    MessageRef noAwards();

    @Name("Aura of")
    MessageRef auraOf(@Arg("name") @Example("The Nether") String name);

    @Name("Spins of")
    MessageRef spinsOf(@Arg("name") @Example("The Nether") String name);

    @Name("Kit")
    MessageRef kit();

    @Name("Numbered kit")
    MessageRef kitNumbered(@Arg("index") @Example("2") int index);

    @Name("No kits")
    MessageRef noKits();

    @Name("Kits full")
    MessageRef kitsFull(@Arg("most") @Example("5") int most);

    @Name("No loadout")
    MessageRef noLoadout();

    @Name("More items")
    MessageRef moreItems(@Arg("count") @Example("2") int count);

    @Name("Loadout line")
    MessageRef loadoutLine(@Arg("amount") @Example("16") int amount, @Arg("name") @Example("Arrow") String name);

    @Name("Area")
    MessageRef area();

    @Name("Numbered area")
    MessageRef areaNumbered(@Arg("index") @Example("2") int index);

    @Name("Block")
    MessageRef block();

    @Name("Numbered block")
    MessageRef blockNumbered(@Arg("index") @Example("2") int index);

    @Name("No areas")
    MessageRef noAreas();

    @Name("No blocks")
    MessageRef noBlocks();

    @Name("No world")
    MessageRef noWorld();

    @Name("Corner")
    MessageRef corner(@Arg("index") @Example("1") int index);

    @Name("Height")
    MessageRef height();

    @Name("Span")
    MessageRef span(@Arg("from") @Example("64") String from, @Arg("to") @Example("80") String to);

    /** The slots an item's attribute applies in, as the game names them. */
    enum ItemSlot {
        MAINHAND,
        OFFHAND,
        HAND,
        HEAD,
        CHEST,
        LEGS,
        FEET,
        ARMOR,
        BODY,
        SADDLE
    }
}
