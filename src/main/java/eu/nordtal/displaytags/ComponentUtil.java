package eu.nordtal.displaytags;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;

public class ComponentUtil {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final JoinConfiguration JOIN_CONFIG = JoinConfiguration.separator(Component.newline());

    public static Component render(final String text) {
        return MINI_MESSAGE.deserialize(text);
    }

    public static Component render(final List<String> lines) {
        final List<Component> components =
                lines.stream().map(ComponentUtil::render).toList();
        return join(components);
    }

    public static Component join(final List<Component> components) {
        return Component.join(JOIN_CONFIG, components);
    }
}
