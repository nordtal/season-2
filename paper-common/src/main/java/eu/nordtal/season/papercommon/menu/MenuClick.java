package eu.nordtal.season.papercommon.menu;

import eu.nordtal.season.messages.feedback.Feedback;
import org.jspecify.annotations.Nullable;

/**
 * What a click in a {@link Menu} asks {@link Menus} to do: a sound, and then either a next menu or a close.
 *
 * @param open the menu to show next, which wins over {@code close}
 */
public record MenuClick(
        @Nullable Feedback sound, boolean close, @Nullable Menu open) {

    /** A click on nothing in particular. */
    public static MenuClick nothing() {
        return new MenuClick(null, false, null);
    }

    /** A button that cannot be pressed right now: a sound, not silence. */
    public static MenuClick refused() {
        return new MenuClick(Feedback.REFUSED, false, null);
    }

    /** A choice that is done with the window. */
    public static MenuClick closing() {
        return new MenuClick(Feedback.SELECT, true, null);
    }

    /** A choice that shows another menu in this one's place. */
    public static MenuClick opening(final Menu next) {
        return new MenuClick(Feedback.SELECT, false, next);
    }
}
