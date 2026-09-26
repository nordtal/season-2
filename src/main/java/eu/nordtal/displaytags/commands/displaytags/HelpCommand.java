package eu.nordtal.displaytags.commands.displaytags;

import eu.nordtal.displaytags.ComponentUtil;
import eu.nordtal.displaytags.commands.MessageUtil;
import eu.nordtal.displaytags.commands.framework.CommandGroup;
import eu.nordtal.displaytags.commands.framework.SubCommand;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

public class HelpCommand extends SubCommand {
    private @Nullable Component helpMessage;

    public HelpCommand(final CommandGroup group) {
        super(group);
        this.setName("help");
        this.setDescription("List the available commands under <gray>/" + group.getName() + "<white>.");
    }

    @Override
    public boolean execute(final CommandSender sender, final String[] args) {
        Component message = this.helpMessage;
        if (message == null) {
            final Collection<SubCommand> commands = this.getCommandGroup().getCommands();

            final List<String> lines = new ArrayList<>();
            lines.add(MessageUtil.prefixed(
                    String.format("Commands <dark_gray>(<white>%s<dark_gray>)<white>:", commands.size())));

            commands.forEach((cmd) ->
                    lines.add(String.format("<gray>/%s <dark_gray>→ <white>%s", cmd.getName(), cmd.getDescription())));

            message = ComponentUtil.render(lines);
            this.helpMessage = message;
        }

        sender.sendMessage(message);
        return true;
    }
}
