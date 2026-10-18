package eu.nordtal.season.papercommon.command;

import static eu.nordtal.season.papercommon.PaperCommonMessages.MESSAGES;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.ToneColours;
import eu.nordtal.season.messagerendering.Tones;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.settings.network.CommandAllowlist;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.command.UnknownCommandEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;

/**
 * Hides the commands that the proxy refuses from each player's command tree.
 * The list is the network's players group.
 */
public final class CommandFilter implements Listener {

    private final Supplier<CommandAllowlist> allowlist;
    private final Predicate<UUID> admin;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final PaperUser.Chime chime;
    private final Supplier<ToneColours> colours;

    public CommandFilter(
            final Supplier<CommandAllowlist> allowlist,
            final Predicate<UUID> admin,
            final Identities identities,
            final MessageRenderer renderer,
            final Supplier<ToneColours> colours,
            final PaperUser.Chime chime) {
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.colours = Objects.requireNonNull(colours, "colours");
        this.chime = Objects.requireNonNull(chime, "chime");
    }

    /** Cancels a command that the list does not carry. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        if (admin.test(event.getPlayer().getUniqueId()) || allowlist.get().allows(event.getMessage())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(refusal(event.getPlayer().getUniqueId()));
        chime.play(event.getPlayer(), Feedback.REFUSED);
    }

    /** Answers an unknown command with the line of a refused one. The console keeps vanilla's text. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUnknownCommand(final UnknownCommandEvent event) {
        if (!(event.getSender() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        event.message(refusal(player.getUniqueId()));
        chime.play(player, Feedback.REFUSED);
    }

    private net.kyori.adventure.text.Component refusal(final UUID player) {
        return Tones.paint(
                renderer.format(
                        identities.languageOf(player), MESSAGES.command().unknown()),
                Tone.BAD,
                colours.get());
    }

    /**
     * Removes the commands that the list does not carry from the tree sent to the player.
     * {@link #onCommand} stops them being typed.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandSend(final PlayerCommandSendEvent event) {
        if (admin.test(event.getPlayer().getUniqueId())) {
            return;
        }
        final CommandAllowlist current = allowlist.get();
        event.getCommands().removeIf(label -> !current.allowsRoot(label));
    }
}
