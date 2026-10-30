package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.document.DocumentMerge;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;

/** The texts of the editor that opens beside a Discord document's card on the Translations page. */
@Name("Document editor")
public interface DocumentEditorTexts {

    @Name("Part")
    MessageRef part(@Arg("part") Part part);

    @Name("Add a part")
    MessageRef add();

    @Name("A slot the code fills")
    MessageRef slot(@Arg("slot") @Example("welcome") String slot);

    @Name("Code mark")
    MessageRef code();

    @Name("Required by the code")
    MessageRef required();

    @Name("Move up")
    MessageRef moveUp();

    @Name("Move down")
    MessageRef moveDown();

    @Name("Remove")
    MessageRef remove();

    @Name("Accent")
    MessageRef accent();

    @Name("Line style")
    MessageRef style(@Arg("style") Style style);

    @Name("Add a line")
    MessageRef addLine();

    @Name("Add a text block")
    MessageRef addText();

    @Name("Shown when")
    MessageRef when(@Arg("of") Of of);

    @Name("Condition")
    MessageRef condition(@Arg("condition") Condition condition);

    @Name("Constant")
    MessageRef constant();

    @Name("Image")
    MessageRef image();

    @Name("Upload")
    MessageRef upload();

    @Name("Add an image")
    MessageRef addImage();

    @Name("Label")
    MessageRef label();

    @Name("Placeholder")
    MessageRef placeholder();

    @Name("Option line")
    MessageRef option();

    @Name("Link")
    MessageRef link();

    @Name("Add a link button")
    MessageRef addLink();

    @Name("Beside")
    MessageRef beside(@Arg("part") Beside beside);

    @Name("Second language")
    MessageRef language();

    @Name("Component count")
    MessageRef components(@Arg("count") @Example("12") int count, @Arg("most") @Example("40") int most);

    @Name("Changed by an admin")
    MessageRef changed();

    @Name("English only")
    MessageRef englishOnly();

    @Name("Why English only")
    MessageRef englishOnlyNote();

    @Name("Release changed")
    MessageRef outdated();

    @Name("Packaged and saved")
    MessageRef version(@Arg("version") Version version);

    @Name("Adapted to the release")
    MessageRef adaptations();

    @Name("One adaptation to the release")
    MessageRef adapted(@Arg("change") DocumentMerge.Change change);

    @Name("Save")
    MessageRef save();

    @Name("Discard")
    MessageRef discard();

    @Name("Saved")
    MessageRef saved(@Arg("document") @Example("rules") String document);

    @Name("Versions")
    MessageRef versions();

    @Name("Restore")
    MessageRef restore();

    @Name("Live version")
    MessageRef live();

    @Name("Shared part mark")
    MessageRef sharedPart(@Arg("name") @Example("footer") String name);

    @Name("Shared part menu")
    MessageRef sharedPartMenu();

    @Name("Save as a shared part")
    MessageRef saveSharedPart();

    @Name("Save a named shared part")
    MessageRef saveSharedPartNamed(@Arg("name") @Example("footer") String name);

    @Name("Detach from a shared part")
    MessageRef detachSharedPart();

    @Name("Shared part drawn in place")
    MessageRef sharedPartDrawn(@Arg("name") @Example("footer") String name);

    @Name("Insert a shared part")
    MessageRef insertSharedPart(@Arg("name") @Example("footer") String name);

    @Name("Shared part name")
    MessageRef sharedPartName();

    @Name("Shared part name rule")
    MessageRef sharedPartNameRule();

    @Name("Shared part exists")
    MessageRef sharedPartExists(@Arg("name") @Example("footer") String name);

    @Name("Shared part unused")
    MessageRef sharedPartUnused();

    @Name("Before and after")
    MessageRef beforeAfter(@Arg("state") State state);

    @Name("Shared part saved")
    MessageRef sharedPartSaved(@Arg("name") @Example("footer") String name, @Arg("deleted") boolean deleted);

    @Name("Delete a shared part")
    MessageRef deleteSharedPart();

    /** A kind of part, as {@code Part.Type} names it. */
    enum Part {
        CONTAINER,
        TEXT,
        SECTION,
        SEPARATOR,
        GALLERY,
        FILE,
        ROW,
        LANGUAGES
    }

    /** How a line stands out, {@code PLAIN} for none. */
    enum Style {
        PLAIN,
        TITLE,
        HEADING
    }

    /** What a condition shows or hides. */
    enum Of {
        PART,
        LINE,
        ITEM
    }

    /** When a part, a line or a button shows. */
    enum Condition {
        ALWAYS,
        SET,
        UNSET,
        EQUALS
    }

    /** What stands beside a section's text blocks. */
    enum Beside {
        BUTTON,
        THUMBNAIL
    }

    enum Version {
        PACKAGED,
        SAVED
    }

    /** Which side of a shared part change a drawing shows. */
    enum State {
        BEFORE,
        AFTER
    }
}
