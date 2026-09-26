package eu.nordtal.displaytags.commands.framework;

import eu.nordtal.displaytags.DisplayTags;
import eu.nordtal.displaytags.commands.MessageUtil;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

public abstract class CommandGroup extends Command {
    protected DisplayTags plugin;
    private final Map<String, SubCommand> commands = new HashMap<>();

    protected CommandGroup(final String name, final DisplayTags plugin) {
        super(name);
        this.plugin = plugin;
    }

    public DisplayTags getPlugin() {
        return this.plugin;
    }

    public void addCommand(final SubCommand command) {
        this.commands.put(command.getName(), command);
    }

    public Collection<SubCommand> getCommands() {
        return this.commands.values();
    }

    @Override
    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
        if (args.length == 0) {
            MessageUtil.error(sender, "Unknown sub-command.");
            return true;
        }

        final String commandName = args[0].toLowerCase(Locale.ROOT);
        final SubCommand command = this.commands.get(commandName);
        if (command == null) {
            MessageUtil.error(sender, "Unknown sub-command.");
            return true;
        }

        if (command.getPermission() != null && !sender.hasPermission(command.getPermission())) {
            MessageUtil.error(sender, "You do not have permission to execute this command.");
            return true;
        }

        return command.execute(sender, sliceArgs(args));
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args)
            throws IllegalArgumentException {
        final String name = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return this.commands.values().stream()
                    .filter((cmd) -> {
                        if (!cmd.getName().startsWith(name)) {
                            return false;
                        }
                        if (cmd.getPermission() != null) {
                            return sender.hasPermission(cmd.getPermission());
                        }

                        return true;
                    })
                    .map(SubCommand::getName)
                    .toList();
        }

        final SubCommand command = this.commands.get(name);
        if (command != null) {
            return command.tabComplete(sender, sliceArgs(args));
        }

        return List.of();
    }

    private static String[] sliceArgs(final String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        final String[] sliced = new String[args.length - 1];
        System.arraycopy(args, 1, sliced, 0, sliced.length);
        return sliced;
    }
}
