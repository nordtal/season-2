package eu.nordtal.season.steward.texts;

import eu.nordtal.season.database.npc.Npc;
import eu.nordtal.season.database.npc.NpcSkin;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;

/** The words of the NPCs page: the list, and the editor of one NPC with its dialogue, settings and versions. */
@Name("NPCs page")
public interface NpcsPage {

    @Name("Title")
    MessageRef title();

    @Name("Create")
    MessageRef create();

    @Name("No NPC")
    MessageRef none();

    @Name("Back to the list")
    MessageRef back();

    @Name("Dialogue settings")
    MessageRef dialogueSettings();

    @Name("New NPC")
    MessageRef unnamed();

    @Name("Name")
    MessageRef name();

    @Name("Position")
    MessageRef place();

    @Name("Steps")
    MessageRef steps();

    @Name("Step count")
    MessageRef stepCount(@Arg("count") @Example("7") int count);

    @Name("Live version")
    MessageRef live(@Arg("version") @Example("3") int version);

    @Name("Not published")
    MessageRef notPublished();

    @Name("Draft changed")
    MessageRef changed();

    @Name("Versions")
    MessageRef versions();

    @Name("Versions of an NPC")
    MessageRef versionsOf(@Arg("name") @Example("Warden Runa") String name);

    @Name("Version")
    MessageRef version(@Arg("number") @Example("3") int number);

    @Name("Live")
    MessageRef isLive();

    @Name("Deleted in this version")
    MessageRef removed();

    @Name("Load a version")
    MessageRef load();

    @Name("Version loaded")
    MessageRef loaded(@Arg("number") @Example("2") int number);

    @Name("Play on me")
    MessageRef play();

    @Name("Cancel playing")
    MessageRef cancelPlay();

    @Name("Not played")
    MessageRef notPlayed();

    @Name("Publish")
    MessageRef publish();

    @Name("Publishing")
    MessageRef publishing();

    @Name("Published")
    MessageRef published(@Arg("name") @Example("Warden Runa") String name, @Arg("version") @Example("4") int version);

    @Name("Not published, as a heading")
    MessageRef notPublishedYet();

    @Name("What keeps a draft from playing")
    MessageRef problem(@Arg("problem") Problem problem, @Arg("language") Language language);

    @Name("Discard")
    MessageRef discard();

    @Name("Discard, asked")
    MessageRef discardAsk(@Arg("name") @Example("Warden Runa") String name);

    @Name("Discard, said")
    MessageRef discardNote(@Arg("version") @Example("3") int version);

    @Name("Newer version, as a heading")
    MessageRef conflict(@Arg("name") @Example("Warden Runa") String name);

    @Name("Newer version, said")
    MessageRef conflictNote(@Arg("live") @Example("4") int live, @Arg("read") @Example("3") int read);

    @Name("Load the live version")
    MessageRef loadLive(@Arg("live") @Example("4") int live);

    @Name("Publish over the live version")
    MessageRef overwrite(@Arg("live") @Example("4") int live);

    @Name("Tab")
    MessageRef tab(@Arg("tab") Tab tab);

    @Name("Language")
    MessageRef language(@Arg("language") Language language);

    @Name("Step kind")
    MessageRef kind(@Arg("kind") Kind kind);

    @Name("Start")
    MessageRef start();

    @Name("Not linked")
    MessageRef unlinked();

    @Name("No text")
    MessageRef noText();

    @Name("Text missing")
    MessageRef missing(@Arg("language") Language language);

    @Name("Text too long")
    MessageRef tooLong(@Arg("language") Language language);

    @Name("Add a step")
    MessageRef addStep();

    @Name("Arrange the steps")
    MessageRef arrange();

    @Name("Dialogue steps")
    MessageRef graph();

    @Name("Step")
    MessageRef step();

    @Name("Lines")
    MessageRef lines(@Arg("lines") @Example("3") int lines, @Arg("limit") @Example("2") int limit);

    @Name("Lines cut")
    MessageRef cutLines(@Arg("limit") @Example("2") int limit, @Arg("answers") boolean answers);

    @Name("Width cut")
    MessageRef cutWidth();

    @Name("Then")
    MessageRef then();

    @Name("Then for an answer")
    MessageRef answerThen(@Arg("index") @Example("2") int index);

    @Name("End the dialogue")
    MessageRef thenEnd();

    @Name("Go to a step")
    MessageRef thenStep(@Arg("step") @Example("There is work enough.") String step);

    @Name("Go to a new step")
    MessageRef thenNew();

    @Name("End, on an answer")
    MessageRef end();

    @Name("Answers")
    MessageRef answers();

    @Name("Add an answer")
    MessageRef addAnswer();

    @Name("Answer count")
    MessageRef answerCount(@Arg("count") @Example("3") int count, @Arg("most") @Example("3") int most);

    @Name("Answer text")
    MessageRef answerText(@Arg("index") @Example("2") int index, @Arg("language") Language language);

    @Name("Remove an answer")
    MessageRef removeAnswer(@Arg("index") @Example("2") int index);

    @Name("Start here")
    MessageRef startHere();

    @Name("Delete a step")
    MessageRef deleteStep();

    @Name("Preview")
    MessageRef preview();

    @Name("Name in a language")
    MessageRef nameIn(@Arg("language") Language language);

    @Name("Pose")
    MessageRef pose();

    @Name("A pose")
    MessageRef poseOf(@Arg("pose") Npc.Pose pose);

    @Name("Typing sound pitch")
    MessageRef pitch();

    @Name("World")
    MessageRef world();

    @Name("Facing")
    MessageRef facing();

    @Name("Items")
    MessageRef items();

    @Name("Slot")
    MessageRef slot(@Arg("slot") Npc.Slot slot);

    @Name("Empty slot")
    MessageRef emptySlot();

    @Name("Remove an item")
    MessageRef removeItem(@Arg("slot") Npc.Slot slot);

    @Name("Skin")
    MessageRef skin();

    @Name("Default skin")
    MessageRef defaultSkin();

    @Name("Arms of a skin")
    MessageRef skinModel(@Arg("model") NpcSkin.SkinModel model);

    @Name("Upload a skin")
    MessageRef uploadSkin();

    @Name("Copy a skin")
    MessageRef copySkin();

    @Name("Player to copy from")
    MessageRef copyFrom();

    @Name("Use the default skin")
    MessageRef useDefaultSkin();

    @Name("Skin not saved, as a heading")
    MessageRef skinNotSaved();

    @Name("Skin of the wrong size")
    MessageRef skinSize(@Arg("width") @Example("64") int width, @Arg("height") @Example("32") int height);

    @Name("Skin not an image")
    MessageRef skinUnreadable();

    @Name("Skin waits for the pack")
    MessageRef skinPending();

    @Name("Name plate")
    MessageRef plate();

    @Name("Plate style")
    MessageRef plateStyle();

    @Name("A plate style")
    MessageRef plateStyleOf(@Arg("style") Npc.PlateStyle style);

    @Name("Plate colour")
    MessageRef plateColour();

    @Name("A plate colour")
    MessageRef plateColourOf(@Arg("colour") Npc.PlateColour colour);

    @Name("Plate symbol")
    MessageRef plateIcon();

    @Name("A plate symbol")
    MessageRef plateIconOf(@Arg("icon") Npc.PlateIcon icon);

    @Name("No plate symbol")
    MessageRef noIcon();

    @Name("What the plate shows")
    MessageRef plateNote();

    @Name("Portrait")
    MessageRef portrait();

    @Name("Portrait from the skin")
    MessageRef skinPortrait();

    @Name("Portrait from a picture")
    MessageRef ownPortrait();

    @Name("Upload a portrait picture")
    MessageRef uploadPortrait();

    @Name("Use the skin's face")
    MessageRef useSkinPortrait();

    @Name("Portrait not saved, as a heading")
    MessageRef portraitNotSaved();

    @Name("Portrait of the wrong size")
    MessageRef portraitSize(
            @Arg("width") @Example("64") int width,
            @Arg("height") @Example("32") int height,
            @Arg("smallest") @Example("40") int smallest,
            @Arg("largest") @Example("1024") int largest);

    @Name("Portrait file too big")
    MessageRef portraitTooBig();

    @Name("Portrait waits for the pack")
    MessageRef portraitPending();

    @Name("Delete")
    MessageRef delete();

    @Name("Delete, asked")
    MessageRef deleteAsk(@Arg("name") @Example("Warden Runa") String name);

    @Name("Delete, said")
    MessageRef deleteNote();

    @Name("Deleted")
    MessageRef deleted(@Arg("name") @Example("Warden Runa") String name);

    /** A tab of the editor. */
    enum Tab {
        DIALOGUE,
        SETTINGS
    }

    /** A language an NPC speaks. */
    enum Language {
        DE,
        EN
    }

    /** What a step is drawn as: a text, a text with answers, the menu it opens or the end after it. */
    enum Kind {
        TEXT,
        ANSWERS,
        MENU,
        END
    }

    /** What the page finds in a draft before it sends it. */
    enum Problem {
        NAME,
        PLACE,
        STEP_TEXT,
        ANSWER_TEXT
    }
}
