package eu.nordtal.season.papercommon.npc;

import java.util.Map;

/**
 * The words of a dialogue's hints and its switch in one reader's language, with the hint of each menu.
 * {@code more} is the hint while further pages of the same text follow.
 */
public record Labels(
        String next, String more, String choose, String answer, String dontShowAgain, Map<String, String> menus) {

    public Labels {
        menus = Map.copyOf(menus);
    }

    /** Returns the hint of a hand-off to {@code menu}, or the next hint for a menu without one. */
    public String menu(final String menu) {
        return menus.getOrDefault(menu, next);
    }
}
