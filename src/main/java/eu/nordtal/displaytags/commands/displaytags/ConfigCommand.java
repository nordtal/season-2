package eu.nordtal.displaytags.commands.displaytags;

import eu.nordtal.displaytags.commands.framework.CommandGroup;
import eu.nordtal.displaytags.commands.framework.SubCommand;
import eu.nordtal.displaytags.config.NameTagConfiguration;
import eu.nordtal.displaytags.util.MessageUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ConfigCommand extends SubCommand {
    public ConfigCommand(CommandGroup group) {
        super(group);
        super.setName("config");
        super.setDescription("View the plugin configuration.");
        super.setPermission("displaytags.admin");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        NameTagConfiguration config = this.getPlugin().config().nametag();
        String background = config.getBackground();

        List<String> messages = new ArrayList<>();
        messages.add("<dark_gray>• <white>Name Tags");
        messages.add("  <white>Enabled <dark_gray>→ " + booleanToString(config.isEnabled()));
        messages.add("  <white>Show To Self <dark_gray>→ " + booleanToString(config.showToSelf()));
        messages.add("  <white>Update Interval <dark_gray>→ <gray>" + config.getUpdateInterval() + " seconds");
        messages.add("  <white>Visibility Distance <dark_gray>→ <gray>" + config.getVisibilityDistance() + " blocks");
        messages.add("<dark_gray>• <white>Display");
        messages.add("  <white>Lines <dark_gray>→ " + hover(String.join("\n", config.getLines())));
        messages.add("  <white>Text Shadow <dark_gray>→ " + booleanToString(config.hasTextShadow()));
        messages.add("  <white>See Through <dark_gray>→ " + booleanToString(config.isSeeThrough()));
        messages.add("  <white>Sneak Text Opacity <dark_gray>→ " + opacity(config.getSneakTextOpacity()));
        messages.add("  <white>Text Alignment <dark_gray>→ <gray>" + config.getTextAlignment().name());
        messages.add("  <white>Background <dark_gray>→ " + color(background) + background(background));
        messages.add("  <white>Billboard <dark_gray>→ <gray>" + config.getBillboard().name());
        messages.add("  <white>Offset <dark_gray>→ " + hover(vector(config.getOffset())));
        messages.add("  <white>Scale <dark_gray>→ " + hover(vector(config.getScale())));

        sender.sendMessage("");
        MessageUtil.send(sender, "<dark_gray>[<#00BFFF>&lᴘʟᴜɢɪɴ ᴄᴏɴꜰɪɢᴜʀᴀᴛɪᴏɴ<dark_gray>]");
        MessageUtil.send(sender, messages);
        sender.sendMessage("");

        return true;
    }

    private String vector(Vector vector) {
        return String.join("\n", List.of(
                "<white>X <dark_gray>→ <gray>" + vector.getX(),
                "<white>Y <dark_gray>→ <gray>" + vector.getY(),
                "<white>Z <dark_gray>→ <gray>" + vector.getZ()
        ));
    }

    private String hover(String text) {
        return "<hover:show_text:'" + escapeArgument(text) + "'><gray><u>Hover";
    }

    /**
     * Escapes a single-quoted MiniMessage tag argument.
     * <p>
     * A configured line containing an apostrophe would otherwise close the argument early and leave
     * the rest of the tag as literal text. MiniMessage unescapes a backslash before the surrounding
     * quote character (and before a backslash) inside a quoted argument, so escaping those two is
     * enough - everything else in the line is still parsed as MiniMessage, which is intended.
     */
    private String escapeArgument(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("'", "\\'");
    }

    private String opacity(int opacity) {
        if (opacity < 0) return "<red>Disabled";
        return "<gray>" + opacity + " <dark_gray>(0-255)";
    }

    private String booleanToString(boolean value) {
        return value ? "<green>Yes" : "<red>No";
    }

    private String background(String background) {
        if (Objects.equals(background, "default")) return "Default";
        if (Objects.equals(background, "transparent")) return "Transparent";
        return background;
    }

    private String color(String hex) {
        if (Objects.equals(hex, "default")) return "<gray>";
        if (Objects.equals(hex, "transparent")) return "<white>";
        return "<" + hex + ">";
    }
}
