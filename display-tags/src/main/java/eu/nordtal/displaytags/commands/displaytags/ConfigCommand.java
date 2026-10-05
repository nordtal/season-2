package eu.nordtal.displaytags.commands.displaytags;

import eu.nordtal.displaytags.commands.MessageUtil;
import eu.nordtal.displaytags.commands.framework.CommandGroup;
import eu.nordtal.displaytags.commands.framework.SubCommand;
import eu.nordtal.displaytags.config.NameTagConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.bukkit.util.Vector;

public class ConfigCommand extends SubCommand {
    public ConfigCommand(final CommandGroup group) {
        super(group);
        super.setName("config");
        super.setDescription("View the plugin configuration.");
        super.setPermission("displaytags.admin");
    }

    @Override
    public boolean execute(final CommandSender sender, final String[] args) {
        final NameTagConfiguration config = this.getPlugin().config().nametag();
        final String background = config.getBackground();

        final List<String> messages = new ArrayList<>();
        messages.add("<dark_gray>• <white>Name Tags");
        messages.add("  <white>Enabled <dark_gray>→ " + booleanToString(config.isEnabled()));
        messages.add("  <white>Show To Self <dark_gray>→ " + booleanToString(config.showToSelf()));
        messages.add("  <white>Update Interval <dark_gray>→ <gray>" + config.getUpdateInterval() + " seconds");
        messages.add("  <white>Visibility Distance <dark_gray>→ <gray>" + config.getVisibilityDistance() + " blocks");
        messages.add("<dark_gray>• <white>Display");
        messages.add("  <white>Lines <dark_gray>→ " + hover(String.join("\n", config.getLines())));
        messages.add("  <white>Text Shadow <dark_gray>→ " + booleanToString(config.hasTextShadow()));
        messages.add("  <white>See Through <dark_gray>→ <gray>"
                + config.getSeeThrough().configValue());
        messages.add("  <white>Sneak Text Opacity <dark_gray>→ " + opacity(config.getSneakTextOpacity()));
        messages.add("  <white>Text Alignment <dark_gray>→ <gray>"
                + config.getTextAlignment().name());
        messages.add("  <white>Background <dark_gray>→ " + color(background) + background(background));
        messages.add(
                "  <white>Billboard <dark_gray>→ <gray>" + config.getBillboard().name());
        messages.add("  <white>Offset <dark_gray>→ " + hover(vector(config.getOffset())));
        messages.add("  <white>Scale <dark_gray>→ " + hover(vector(config.getScale())));

        sender.sendMessage("");
        MessageUtil.send(sender, "<dark_gray>[<#00BFFF>&lᴘʟᴜɢɪɴ ᴄᴏɴꜰɪɢᴜʀᴀᴛɪᴏɴ<dark_gray>]");
        MessageUtil.send(sender, messages);
        sender.sendMessage("");

        return true;
    }

    private static String vector(final Vector vector) {
        return String.join(
                "\n",
                List.of(
                        "<white>X <dark_gray>→ <gray>" + vector.getX(),
                        "<white>Y <dark_gray>→ <gray>" + vector.getY(),
                        "<white>Z <dark_gray>→ <gray>" + vector.getZ()));
    }

    private static String hover(final String text) {
        return "<hover:show_text:'" + escapeArgument(text) + "'><gray><u>Hover";
    }

    /**
     * Escapes a single-quoted MiniMessage tag argument.
     *
     * Without it, a configured line containing an apostrophe could close the argument early and
     * spill the rest of the line as literal text.
     */
    private static String escapeArgument(final String text) {
        return text.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String opacity(final int opacity) {
        if (opacity < 0) {
            return "<red>Disabled";
        }
        return "<gray>" + opacity + " <dark_gray>(0-255)";
    }

    private static String booleanToString(final boolean value) {
        return value ? "<green>Yes" : "<red>No";
    }

    private static String background(final String background) {
        if (Objects.equals(background, "default")) {
            return "Default";
        }
        if (Objects.equals(background, "transparent")) {
            return "Transparent";
        }
        return background;
    }

    private static String color(final String hex) {
        if (Objects.equals(hex, "default")) {
            return "<gray>";
        }
        if (Objects.equals(hex, "transparent")) {
            return "<white>";
        }
        return "<" + hex + ">";
    }
}
