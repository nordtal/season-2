package eu.nordtal.season.papercommon.chat;

import eu.nordtal.season.common.PlayerText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/** Applies the player text filter without changing a component's style or structure. */
public final class PlayerTextComponents {

    private PlayerTextComponents() {}

    /** Returns {@code text} without private-use code points in its content or children. */
    public static Component withoutPrivateUse(final Component text) {
        final Component own = text instanceof final TextComponent literal
                ? literal.content(PlayerText.withoutPrivateUse(literal.content()))
                : text;
        return own.children(own.children().stream()
                .map(PlayerTextComponents::withoutPrivateUse)
                .toList());
    }
}
