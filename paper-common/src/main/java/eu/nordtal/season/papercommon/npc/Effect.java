package eu.nordtal.season.papercommon.npc;

/** Something a dialogue asks of the game besides what it draws. */
public sealed interface Effect {

    /** A sound of the dialogue. */
    record Sound(Cue cue) implements Effect {}

    /** The hotbar is held on {@link Hotbar#CHOICE_SLOT} while answers show. */
    record HoldSlot() implements Effect {}

    /** The hotbar goes back to the slot it was on. */
    record ReleaseSlot() implements Effect {}

    /** The menu opens, and the skip changes as {@code change} says. */
    record HandOff(String menu, SkipChoice.Change change) implements Effect {}

    /** The dialogue is over and nothing of it shows any more. */
    record End() implements Effect {}

    /** The sounds a dialogue plays. */
    enum Cue {
        OPEN,
        NEXT_TEXT,
        TYPE,
        SELECTION,
        ANSWER,
        SWITCH_ON,
        SWITCH_OFF,
        CLOSE
    }
}
