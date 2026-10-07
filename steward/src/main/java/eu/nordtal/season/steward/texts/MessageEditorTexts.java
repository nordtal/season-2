package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.Name;

/** The message editor: its two views, the tools above the field, and a fallen-back override. */
@Name("Message editor")
public interface MessageEditorTexts {

    @Name("As it looks")
    MessageRef asItLooks();

    @Name("Source")
    MessageRef source();

    @Name("Variant")
    MessageRef variant();

    @Name("Add variant")
    MessageRef addVariant();

    @Name("Remove variant")
    MessageRef removeVariant();

    @Name("Empty")
    MessageRef empty();

    @Name("Placeholder")
    MessageRef placeholder();

    @Name("Glyph")
    MessageRef glyph();

    @Name("Line break")
    MessageRef lineBreak();

    @Name("Value style")
    MessageRef valueStyle();

    @Name("Default style")
    MessageRef defaultStyle();

    @Name("Colour")
    MessageRef colour();

    @Name("Tone")
    MessageRef tone();

    @Name("Gradient")
    MessageRef gradient();

    @Name("No colour")
    MessageRef noColour();

    @Name("Add colour")
    MessageRef addColour();

    @Name("Remove colour")
    MessageRef removeColour();

    @Name("Hex colour")
    MessageRef hexColour();

    @Name("Bold")
    MessageRef bold();

    @Name("Italic")
    MessageRef italic();

    @Name("Underlined")
    MessageRef underlined();

    @Name("Strikethrough")
    MessageRef strikethrough();

    @Name("Obfuscated")
    MessageRef obfuscated();

    @Name("Code")
    MessageRef code();

    @Name("Clear formatting")
    MessageRef clearFormatting();

    @Name("Hover")
    MessageRef hover();

    @Name("Click")
    MessageRef click();

    @Name("Click value")
    MessageRef clickValue();

    @Name("Open URL")
    MessageRef openUrl();

    @Name("Run command")
    MessageRef runCommand();

    @Name("Suggest command")
    MessageRef suggestCommand();

    @Name("Copy to clipboard")
    MessageRef copyToClipboard();

    @Name("Link")
    MessageRef link();

    @Name("Action")
    MessageRef action();

    @Name("Apply")
    MessageRef apply();

    @Name("Remove")
    MessageRef remove();

    @Name("Fallen back")
    MessageRef fallenBack();

    @Name("Packaged then")
    MessageRef packagedThen();

    @Name("Packaged now")
    MessageRef packagedNow();

    @Name("Override")
    MessageRef override();

    @Name("Take over")
    MessageRef takeOver();

    @Name("Show in game")
    MessageRef showInGame();

    @Name("Send in Discord")
    MessageRef sendInDiscord();

    @Name("Place")
    MessageRef place(@Arg("place") Display place);
}
