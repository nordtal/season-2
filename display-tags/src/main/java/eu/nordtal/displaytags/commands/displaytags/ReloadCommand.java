package eu.nordtal.displaytags.commands.displaytags;

import eu.nordtal.displaytags.commands.MessageUtil;
import eu.nordtal.displaytags.commands.framework.CommandGroup;
import eu.nordtal.displaytags.commands.framework.SubCommand;
import org.bukkit.command.CommandSender;

public class ReloadCommand extends SubCommand {
    public ReloadCommand(final CommandGroup group) {
        super(group);
        super.setName("reload");
        super.setDescription("Reload the plugin.");
        super.setPermission("displaytags.admin");
    }

    @Override
    public boolean execute(final CommandSender sender, final String[] args) {
        final boolean reloaded = this.getPlugin().reloadPlugin();

        if (reloaded) {
            MessageUtil.success(sender, "Successfully reloaded the plugin!");
        } else {
            MessageUtil.error(sender, "Failed to reload the plugin, please check the server logs!");
        }

        return true;
    }
}
