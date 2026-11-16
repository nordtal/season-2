package eu.nordtal.season.messagerendering.text;

/** The room an NPC's dialogue panel gives its texts, shared by the panel and Steward's length check. */
public final class DialogueRoom {

    /** The columns a line of the text takes, so it keeps clear of the hold mark. */
    public static final int BODY = 210;

    /** The columns the speaker's name takes. */
    public static final int NAME = 219;

    /** The columns an answer's label takes, so it keeps clear of its number. */
    public static final int LABEL = 228;

    /** The lines of text on the panel while the NPC speaks. */
    public static final int SPEAK_LINES = 3;

    /** The lines of text above the answers. */
    public static final int QUESTION_LINES = 2;

    private DialogueRoom() {}
}
