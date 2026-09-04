package eu.nordtal.s2.smp.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.OpenPayment;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.phase.SeasonDates;
import eu.nordtal.s2.papercommon.command.PaperUser;
import eu.nordtal.s2.smp.feedback.SmpSounds;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;

/**
 * {@code /smp access <player>} - why can this person not get in?
 *
 * <h2>Why a Minecraft server answers a question about payments</h2>
 * Because that is where the person asking it is standing. The full answer lives in Discord
 * ({@code /access-status}), and moving the whole of that command here was rejected on 2026-09-04 -
 * it names roles, channels and a purchase history, none of which mean anything in chat. What an
 * admin actually needs in game is three lines: <b>is this account linked, does it have access, and
 * is there a purchase halfway through?</b>
 *
 * <p>The third line is the one that was asked for by name, and it is the one that pays for this
 * command existing at all. "They have not paid" and "they are in the middle of paying" produce the
 * same disconnect screen and the same complaint, and only one of them means the admin should wait
 * rather than act. It is readable from here because the purchase flow's state is a <em>row</em> and
 * not a cache - season 1 kept it in memory, so a restart answered "setup expired" to everybody
 * mid-purchase.</p>
 *
 * <h2>The answer goes to the asker and nowhere else</h2>
 * Chat to the sender, no broadcast, no log line. It carries somebody's Discord id and a payment
 * reference; those are things this network's admins already see in the admin channel, and they are
 * not things to print into a shared chat.
 *
 * <h2>Online only, deliberately</h2>
 * The player argument resolves through {@code Bukkit.getPlayerExact}, the same way {@code /smp aura}
 * does. An offline lookup would need a name-to-UUID resolution this server has no source for -
 * {@code account_link} stores UUIDs and Discord ids, and no name - and the case this command is for
 * is somebody standing in front of you.
 */
public final class AccessLookup {

    private final Plugin plugin;
    private final AccessDirectory access;
    private final PlayerLocales locales;
    private final Messages messages;
    private final SmpSounds sounds;

    public AccessLookup(final Plugin plugin, final AccessDirectory access,
                        final PlayerLocales locales, final Messages messages,
                        final SmpSounds sounds) {
        this.plugin = plugin;
        this.access = access;
        this.locales = locales;
        this.messages = messages;
        this.sounds = sounds;
    }

    /** The subtree, for {@code SmpCommand} to hang under {@code /smp}. Admin-only by that gate. */
    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("access")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (final Player online : Bukkit.getOnlinePlayers()) {
                                builder.suggest(online.getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(this::lookup));
    }

    private int lookup(final CommandContext<CommandSourceStack> context) {
        final String name = StringArgumentType.getString(context, "player");
        final NordtalUser asker = asker(context.getSource().getSender());

        final Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            asker.reply("smp.admin.player-offline", Map.of(), Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }

        // Two queries, off the main thread. Neither is on a login path, and this command is run a
        // handful of times a season by somebody who is already waiting for an answer.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> report(asker, name, target));
        return Command.SINGLE_SUCCESS;
    }

    private void report(final NordtalUser asker, final String name, final Player target) {
        final AccessState state;
        try {
            state = access.accessState(target.getUniqueId());
        } catch (final RuntimeException failure) {
            plugin.getLogger().warning("/smp access could not read " + name + ": " + failure);
            asker.reply("smp.access.failed", Map.of(), Feedback.REFUSED);
            return;
        }

        if (state.discordId() == null) {
            // An unlinked account should not have got past the proxy at all, so this is worth
            // saying plainly rather than folding into "no access": it means something else is
            // already wrong.
            asker.reply("smp.access.unlinked", Map.of("player", name));
            return;
        }

        asker.reply("smp.access.linked", Map.of("player", name, "discord", state.discordId()));

        if (state.accessActive() && state.accessValidUntil() != null) {
            asker.reply("smp.access.active",
                    Map.of("until", SeasonDates.format(state.accessValidUntil())));
        } else if (state.accessValidUntil() != null) {
            asker.reply("smp.access.expired",
                    Map.of("since", SeasonDates.format(state.accessValidUntil())));
        } else {
            asker.reply("smp.access.never");
        }

        final Optional<OpenPayment> pending;
        try {
            pending = access.openPayment(state.discordId());
        } catch (final RuntimeException failure) {
            plugin.getLogger().warning("/smp access could not read the open payment for " + name
                    + ": " + failure);
            asker.reply("smp.access.payment-unknown");
            return;
        }

        pending.ifPresentOrElse(
                payment -> asker.reply(
                        // The distinction that makes the line worth printing: a request with no
                        // bunq tab is somebody who picked a number of days and never got as far as
                        // a payment link, which is a different thing to chase.
                        payment.hasTab() ? "smp.access.payment" : "smp.access.payment-unstarted",
                        Map.of("reference", payment.reference(),
                                "days", payment.days(),
                                "amount", payment.amount(),
                                "since", SeasonDates.format(payment.created()))),
                () -> asker.reply("smp.access.no-payment"));
    }

    /**
     * Whoever asked, as a {@link NordtalUser}.
     *
     * <p>{@code admin} is {@code true} without a lookup, and that is not a hole: {@code /smp} is
     * gated on {@code SmpCommand#mayUse} before any of this runs, so reaching here <em>is</em> the
     * admin check. Repeating it would be a second database read per invocation for an answer
     * already given.</p>
     */
    private NordtalUser asker(final CommandSender sender) {
        if (sender instanceof Player player) {
            return PaperUser.of(plugin, player, locales.of(player.getUniqueId()), true, null,
                    messages, sounds::play);
        }
        return PaperUser.console(plugin, sender, messages);
    }
}
