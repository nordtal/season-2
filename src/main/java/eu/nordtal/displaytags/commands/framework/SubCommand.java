package eu.nordtal.displaytags.commands.framework;

import eu.nordtal.displaytags.DisplayTags;
import java.util.List;
import org.bukkit.command.CommandSender;

public abstract class SubCommand {
    private final CommandGroup commandGroup;

    private String name;
    private String description;
    private String permission;

    public SubCommand(CommandGroup commandGroup) {
        this.commandGroup = commandGroup;
    }

    public String getName() {
        return this.name;
    }

    public String getDescription() {
        return this.description;
    }

    public String getPermission() {
        return this.permission;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public CommandGroup getCommandGroup() {
        return this.commandGroup;
    }

    public DisplayTags getPlugin() {
        return this.commandGroup.plugin;
    }

    public abstract boolean execute(CommandSender sender, String[] args);

    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
