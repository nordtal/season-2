package eu.nordtal.season.papercommon.menu;

import eu.nordtal.season.messages.feedback.Feedback;
import org.jspecify.annotations.Nullable;

/**
 * What {@link Menus} does after a click in a {@link Menu}: play a sound, then open the next menu or close.
 * {@code open} wins over {@code close}.
 */
public record MenuClick(
        @Nullable Feedback sound, boolean close, @Nullable Menu open) {

    public static MenuClick nothing() {
        return new MenuClick(null, false, null);
    }

    public static MenuClick refused() {
        return new MenuClick(Feedback.REFUSED, false, null);
    }

    public static MenuClick closing() {
        return new MenuClick(Feedback.SELECT, true, null);
    }

    public static MenuClick opening(final Menu next) {
        return new MenuClick(Feedback.SELECT, false, next);
    }
}
