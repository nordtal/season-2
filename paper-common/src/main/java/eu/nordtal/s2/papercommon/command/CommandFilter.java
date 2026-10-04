package eu.nordtal.s2.papercommon.command;

import static eu.nordtal.s2.papercommon.PaperCommonMessages.MESSAGES;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messagerendering.ToneColours;
import eu.nordtal.s2.messagerendering.Tones;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.settings.network.CommandAllowlist;
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
 * The Paper half of the command allowlist: it hides what the proxy refuses from each player's command tree.
 *
 * The list is the network's players group as of its last reload, so a change in Steward reaches the next command.
 */
public final class CommandFilter implements Listener {

    private final Supplier<CommandAllowlist> allowlist;
    private final Predicate<UUID> admin;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final PaperUser.Chime chime;
    private final Supplier<ToneColours> colours;

    /**
     * @param allowlist the network's list as of its last reload
     * @param colours   the current tone palette, a supplier so a reload reaches the next refusal
     * @param chime     how the refusal sounds; {@link PaperUser.Chime#silent()} for a module with no sounds
     */
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

    /** Refuses a command the list does not carry, first and by cancelling rather than rewriting. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        if (admin.test(event.getPlayer().getUniqueId()) || allowlist.get().allows(event.getMessage())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(refusal(event.getPlayer().getUniqueId()));
        chime.play(event.getPlayer(), Feedback.REFUSED);
    }

    /**
     * Answers an unknown command with the same line a refused one gets, so a player cannot tell which exists.
     *
     * The console keeps vanilla's text, since the parse position is diagnosis.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUnknownCommand(final UnknownCommandEvent event) {
        if (!(event.getSender() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        event.message(refusal(player.getUniqueId()));
        chime.play(player, Feedback.REFUSED);
    }

    /** Returns the one refusal line, in that player's {@code discord_user.locale}. */
    private net.kyori.adventure.text.Component refusal(final UUID player) {
        return Tones.paint(
                renderer.format(
                        identities.languageOf(player), MESSAGES.command().unknown()),
                Tone.BAD,
                colours.get());
    }

    /**
     * Takes every command the list does not carry out of the tree this player is sent.
     *
     * It hides the command from completion; {@link #onCommand} is what stops it being typed.
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
