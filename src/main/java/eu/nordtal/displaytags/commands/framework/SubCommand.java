package eu.nordtal.displaytags.commands.framework;

import eu.nordtal.displaytags.DisplayTags;
import java.util.List;
import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

public abstract class SubCommand {
    private final CommandGroup commandGroup;

    private @Nullable String name;
    private @Nullable String description;
    private @Nullable String permission;

    public SubCommand(final CommandGroup commandGroup) {
        this.commandGroup = commandGroup;
    }

    public String getName() {
        return Objects.requireNonNull(this.name, "setName() has not run");
    }

    public String getDescription() {
        return Objects.requireNonNull(this.description, "setDescription() has not run");
    }

    public @Nullable String getPermission() {
        return this.permission;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public void setPermission(final String permission) {
        this.permission = permission;
    }

    public CommandGroup getCommandGroup() {
        return this.commandGroup;
    }

    public DisplayTags getPlugin() {
        return this.commandGroup.plugin;
    }

    public abstract boolean execute(CommandSender sender, String[] args);

    public List<String> tabComplete(final CommandSender sender, final String[] args) {
        return List.of();
    }
}
