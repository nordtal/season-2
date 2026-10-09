package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** The settings and translations editor: the files, a file's fields and texts, and the list and reference controls. */
@Name("Settings")
public interface SettingsEditor {

    @Name("Files")
    MessageRef files();

    @Name("No files")
    MessageRef noFiles();

    @Name("Back to the files")
    MessageRef backToFiles();

    @Name("No such file")
    MessageRef noSuchFile();

    @Name("Not readable")
    MessageRef notReadable();

    @Name("Read-only")
    MessageRef readOnly();

    @Name("Changed")
    MessageRef changed();

    @Name("Search this file")
    MessageRef searchFile();

    @Name("No match")
    MessageRef noMatch();

    @Name("Empty file")
    MessageRef emptyFile();

    @Name("Back to the top")
    MessageRef backToTop();

    @Name("Texts saved")
    MessageRef textsSaved(@Arg("count") int count);

    @Name("Settings saved")
    MessageRef settingsSaved(@Arg("count") int count);

    @Name("Not in bundle")
    MessageRef notInBundle();

    @Name("Undo")
    MessageRef undo();

    @Name("Reset to packaged")
    MessageRef resetToPackaged();

    @Name("Overridden")
    MessageRef overridden();

    @Name("Refused")
    MessageRef refused(@Arg("problem") String problem);

    @Name("Restart needed")
    MessageRef restartNeeded();

    @Name("Environment override")
    MessageRef envOverride();

    @Name("Environment override tip")
    MessageRef envOverrideTip();

    @Name("Entry")
    MessageRef entry(@Arg("index") int index);

    @Name("Choose one")
    MessageRef chooseOne();

    @Name("Choose a suggestion")
    MessageRef chooseSuggestion();

    @Name("Free text")
    MessageRef freeText();

    @Name("Set")
    MessageRef set();

    @Name("Not set")
    MessageRef notSet();

    @Name("Empty list")
    MessageRef emptyList();

    @Name("Remove entry")
    MessageRef removeEntry(@Arg("index") int index);

    @Name("Add entry")
    MessageRef addEntry();

    @Name("Pick a colour")
    MessageRef pickColour();

    @Name("Preview")
    MessageRef preview(@Arg("shown") boolean shown);

    @Name("Preview on")
    MessageRef previewOn();

    @Name("No colour")
    MessageRef noColour();

    @Name("Raw list")
    MessageRef rawList();

    @Name("No entries")
    MessageRef noEntries();

    @Name("Cannot remove")
    MessageRef cannotRemove();

    @Name("Entry cannot be removed")
    MessageRef entryCannotRemove(@Arg("index") int index);

    @Name("Remove")
    MessageRef remove(@Arg("what") String what);

    @Name("Incomplete")
    MessageRef incomplete(@Arg("missing") String missing);

    @Name("Add to")
    MessageRef addTo(@Arg("what") String what);

    @Name("Remove question")
    MessageRef removeAsk(@Arg("title") String title);

    @Name("Remove entry question")
    MessageRef removeEntryAsk();

    @Name("None")
    MessageRef none();

    @Name("Search in")
    MessageRef searchIn(@Arg("what") String what);

    @Name("Search")
    MessageRef search();

    @Name("All")
    MessageRef all();

    @Name("Every tag")
    MessageRef everyTag();

    @Name("Tag")
    MessageRef tag();

    @Name("Add")
    MessageRef add();

    @Name("Undo an entry")
    MessageRef undoEntry(@Arg("what") String what);
}
