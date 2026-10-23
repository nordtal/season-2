package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;

/** The texts of the settings and translations editor for files, fields, texts, and the list and reference controls. */
@Name("Settings")
public interface SettingsEditor {

    @Name("Applies after a restart")
    MessageRef appliesAfterRestart();

    @Name("Applies on save")
    MessageRef appliesOnSave();

    @Name("Plugin files")
    MessageRef pluginFiles();

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

    @Name("Syntax error")
    MessageRef syntaxError();

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
    MessageRef textsSaved(@Arg("count") @Example("3") int count);

    @Name("Settings saved")
    MessageRef settingsSaved(@Arg("count") @Example("3") int count);

    @Name("Not of the field's type")
    MessageRef notOfType(@Arg("expected") StewardTexts.Expected expected);

    @Name("Fields that hold a wrong value")
    MessageRef invalidFields(@Arg("count") @Example("2") int count);

    @Name("Not saved yet")
    MessageRef notSaved();

    @Name("No worlds reported")
    MessageRef noWorlds(@Arg("service") @Example("smp") String service);

    @Name("No game data published")
    MessageRef noGameData();

    @Name("File saved")
    MessageRef fileSaved(@Arg("file") @Example("server.properties") String file);

    @Name("Not in bundle")
    MessageRef notInBundle();

    @Name("Undo")
    MessageRef undo();

    @Name("Reset to packaged")
    MessageRef resetToPackaged();

    @Name("Overridden")
    MessageRef overridden();

    @Name("Refused")
    MessageRef refused(@Arg("problem") @Example("view-distance must be between 2 and 32") String problem);

    @Name("Environment override")
    MessageRef envOverride();

    @Name("Environment override tip")
    MessageRef envOverrideTip();

    @Name("Entry")
    MessageRef entry(@Arg("index") @Example("2") int index);

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
    MessageRef removeEntry(@Arg("index") @Example("2") int index);

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
    MessageRef entryCannotRemove(@Arg("index") @Example("2") int index);

    @Name("Remove")
    MessageRef remove(@Arg("what") @Example("Diamond") String what);

    @Name("Incomplete")
    MessageRef incomplete(@Arg("missing") @Example("the name") String missing);

    @Name("Add to")
    MessageRef addTo(@Arg("what") @Example("Rewards") String what);

    @Name("Remove question")
    MessageRef removeAsk(@Arg("title") @Example("Diamond") String title);

    @Name("Remove entry question")
    MessageRef removeEntryAsk();

    @Name("Keep the entry")
    MessageRef keepIt();

    @Name("Remove the entry")
    MessageRef removeIt();

    @Name("None")
    MessageRef none();

    @Name("Search in")
    MessageRef searchIn(@Arg("what") @Example("Rewards") String what);

    @Name("Search")
    MessageRef search();

    @Name("Clear the search")
    MessageRef clearSearch();

    @Name("Filters")
    MessageRef filters();

    @Name("All")
    MessageRef all();

    @Name("Every tag")
    MessageRef everyTag();

    @Name("Tag")
    MessageRef tag();

    @Name("Add")
    MessageRef add();

    @Name("Undo an entry")
    MessageRef undoEntry(@Arg("what") @Example("Diamond") String what);
}
