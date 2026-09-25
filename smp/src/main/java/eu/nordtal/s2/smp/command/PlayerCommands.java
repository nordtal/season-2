package eu.nordtal.s2.smp.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.CommandMessages;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.context.MilestoneContext;
import eu.nordtal.s2.common.message.context.PlayerContext;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

/**
 * {@code /aura} and {@code /smp status} - the two SMP commands a player types, as plain Paper
 * Brigadier.
 *
 * <h2>Why they are not declarations</h2>
 * The same reason the proxy's {@code /msg} and {@code /discord} are not: one server, no
 * confirmation, no admin flag, and no argument that ever travels through a request row. What the
 * command framework bought them was an effects interface, an adapter translation and a catalogue
 * entry no other surface could reach.
 *
 * <h2>{@code /aura} exists on the SMP only</h2>
 * As a declaration it was also registered on limbo and the hunger games, where typing it became a
 * request row answered by this server. It is not any more: the numbers are this server's, and a
 * player asking about them is standing on it.
 *
 * <h2>{@code /smp status} still answers the console</h2>
 * The same three lines in English, through the console's own {@link NordtalUser}. There was never a
 * separate console wording, so there is nothing left for the catalogue to carry.
 */
public final class PlayerCommands {

    private final Standing standing;
    private final CommandEffects effects;
    private final Function<CommandSender, NordtalUser> users;

    /**
     * @param effects where the reads run - the plugin's async scheduler - and where a failed one is
     *                logged
     * @param users   whoever typed it, as a {@link NordtalUser}: a player or the console
     */
    public PlayerCommands(final Standing standing, final CommandEffects effects,
                          final Function<CommandSender, NordtalUser> users) {
        this.standing = Objects.requireNonNull(standing, "standing");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.users = Objects.requireNonNull(users, "users");
    }

    /** {@code /aura}: players only, so the console never has it in its tree. */
    public LiteralCommandNode<CommandSourceStack> aura() {
        return Commands.literal("aura")
                .requires(source -> source.getSender() instanceof Player)
                .executes(context -> {
                    final Player player = (Player) context.getSource().getSender();
                    showAura(users.apply(player), player.getUniqueId());
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    /**
     * {@code status}, to be hung under {@code /smp} as an open subtree. A player or the console;
     * never a command block, which is nobody.
     */
    public LiteralArgumentBuilder<CommandSourceStack> status() {
        return Commands.literal("status")
                .requires(source -> source.getSender() instanceof Player
                        || source.getSender() instanceof ConsoleCommandSender)
                .executes(context -> {
                    showStatus(users.apply(context.getSource().getSender()));
                    return Command.SINGLE_SUCCESS;
                });
    }

    /**
     * Where the asker stands, and the ten highest.
     *
     * <p>Rank, total and the top ten come back in one read so they describe one instant: read
     * separately, somebody's own line could disagree with the line about them in the list below
     * it. The asker's own line is told apart by {@link Tone}, not by a second key with the same
     * words in a different colour.</p>
     */
    void showAura(final NordtalUser user, final UUID self) {
        effects.async(() -> {
            final Optional<Standing.AuraStanding> read;
            try {
                read = standing.auraStanding(self);
            } catch (final RuntimeException failure) {
                effects.warn("/aura failed", failure);
                user.reply(MESSAGES.smp().aura().failed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            if (read.isEmpty()) {
                user.reply(MESSAGES.smp().aura().unlinked(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            final Standing.AuraStanding shown = read.get();
            user.reply(MESSAGES.smp().aura().own(shown.aura(), shown.rank(), shown.total()), Tone.GOOD);
            if (shown.top().isEmpty()) {
                user.reply(MESSAGES.smp().aura().empty(), Tone.MUTED);
                return;
            }
            user.reply(MESSAGES.smp().aura().top(shown.top().size()), Tone.NEUTRAL);
            for (final Standing.AuraLine line : shown.top()) {
                user.reply(MESSAGES.smp().aura().line(line.place(), new PlayerContext(line.player()), line.aura()),
                        line.you() ? Tone.GOOD : Tone.MUTED);
            }
        });
    }

    /** The phase, the active milestone with its progress, and who is on. */
    void showStatus(final NordtalUser user) {
        effects.async(() -> {
            final Standing.Status status;
            try {
                status = standing.status(user.locale());
            } catch (final RuntimeException failure) {
                effects.warn("/smp status failed", failure);
                user.reply(MESSAGES.smp().status().failed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            user.reply(CommandMessages.MESSAGES.phase().current(status.phase()), Tone.NEUTRAL);
            if (!status.read()) {
                // The first second after enable: an empty milestone here would read as "finished".
                user.reply(MESSAGES.smp().status().unread(), Tone.MUTED);
            } else if (status.milestone().isPresent()) {
                user.reply(MESSAGES.smp().status().milestone(new MilestoneContext(status.milestone().get()),
                        status.percent()), Tone.NEUTRAL);
            } else {
                // Every milestone in the season is done. That is the one line here that is news.
                user.reply(MESSAGES.smp().status().finished(), Tone.GOOD);
            }
            user.reply(status.online() == 0 ? MESSAGES.smp().status().onlineSection().none()
                    : status.online() == 1 ? MESSAGES.smp().status().onlineSection().one()
                    : MESSAGES.smp().status().online(status.online()), Tone.MUTED);
        });
    }
}
