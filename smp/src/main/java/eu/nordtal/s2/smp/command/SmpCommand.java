package eu.nordtal.s2.smp.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.farm.FarmWorldReset;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.progress.ObjectiveEngine;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * {@code /smp} - the escape hatches, and the little that has to be managed by hand.
 *
 * <h2>The escape hatches are a design feature, not a debug menu</h2>
 * docs/smp.md names three, and each exists because an objective can turn out to be impossible after
 * it has been announced: a single objective completed by an admin, a whole milestone unlocked by
 * one, and a target lowered below its collected progress by a reload. All three pay
 * {@code pot x (reached / target)} rather than the full pot, so using one is never worth more than
 * doing the work.
 *
 * <h2>Every aura change writes its reason</h2>
 * Including an admin's. A balance nobody can explain is a balance nobody trusts, and the correction
 * command is precisely the one somebody will be asked to explain.
 *
 * <p>Admin is the Discord role mirrored into the database, read from {@link Identities}' cache. There
 * is no LuckPerms and no second admin list.
 */
public final class SmpCommand {

    private final Plugin plugin;
    private final SmpDao dao;
    private final ObjectiveEngine engine;
    private final FarmWorldReset farmReset;
    private final Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;
    private final Runnable reload;

    /**
     * {@code /smp update}, in its own class. It is the one part of this command that talks to
     * another container rather than to this server, and none of the escape hatches above have
     * anything to do with it.
     */
    private final UpdateCommands updates;
    private final AccessLookup accessLookup;
    private final SmpSounds sounds;

    public SmpCommand(final Plugin plugin, final SmpDao dao, final ObjectiveEngine engine,
                      final FarmWorldReset farmReset, final Identities identities,
                      final Messages messages, final PlayerLocales locales, final Runnable reload,
                      final UpdateCommands updates, final AccessLookup accessLookup,
                      final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.engine = engine;
        this.farmReset = farmReset;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
        this.reload = reload;
        this.updates = updates;
        this.accessLookup = accessLookup;
        this.sounds = sounds;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("smp")
                .requires(this::isAdmin)
                .then(Commands.literal("reload").executes(this::handleReload))
                .then(Commands.literal("farmreset")
                        .then(Commands.literal("now").executes(this::handleFarmReset)))
                .then(Commands.literal("objective")
                        .then(Commands.literal("complete")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(this::handleCompleteObjective))))
                .then(Commands.literal("milestone")
                        .then(Commands.literal("unlock")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(this::handleUnlockMilestone))))
                .then(updates.build())
                .then(accessLookup.build())
                .then(Commands.literal("aura")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("delta", IntegerArgumentType.integer(-10000, 10000))
                                        .executes(this::handleAura))))
                .build();
    }

    /**
     * Whether the sender may use any of this.
     *
     * <p>The console always may: it is the operator, and a database that has lost its admin flags is
     * exactly when somebody needs a way in.
     */
    private boolean isAdmin(final CommandSourceStack source) {
        return mayUse(source.getSender(), uuid -> identities.of(uuid).admin());
    }

    /**
     * The decision on its own: a player who is flagged admin, or the console. Nothing else.
     *
     * <h2>"Not a player" is not the same as "the console"</h2>
     * It used to return {@code true} for everything that was not a {@link Player}, with a comment
     * saying the console is the operator. The comment was right and the check was wider than the
     * comment: a {@code BlockCommandSender} is not a player, and neither is the
     * {@code ProxiedCommandSender} that {@code /execute as … run …} produces, nor a datapack
     * function's sender. All three would have passed - which on this server means a command block
     * could call {@code /smp aura}, {@code /smp milestone unlock} and {@code /smp update restart}.
     *
     * <p>That is not theoretical here. Terralith and Dungeons and Taverns are required datapacks on
     * this server, and the SMP is explicitly a place where players build things: a command block is
     * something the season hands people. Asking for the console <em>by type</em> is the whole fix.</p>
     *
     * <p>Package-visible and static so the decision can be asserted without a server, which is the
     * only part of a command that ever can be.</p>
     */
    static boolean mayUse(final CommandSender sender, final Predicate<UUID> isAdmin) {
        if (sender instanceof Player player) {
            return isAdmin.test(player.getUniqueId());
        }
        return sender instanceof ConsoleCommandSender;
    }

    private int handleReload(final CommandContext<CommandSourceStack> context) {
        reload.run();
        reply(context, "smp.admin.reloaded");
        return Command.SINGLE_SUCCESS;
    }

    private int handleFarmReset(final CommandContext<CommandSourceStack> context) {
        reply(context, "smp.admin.farmreset");
        farmReset.resetNow();
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Completes one objective of the active milestone by hand.
     *
     * <p>Pays out at once, scaled to what was actually collected - which is the whole point of the
     * hatch: an objective that turns out to be impossible is closed without pretending it was done.
     */
    private int handleCompleteObjective(final CommandContext<CommandSourceStack> context) {
        final String key = StringArgumentType.getString(context, "key");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final Optional<String> active = dao.activeMilestoneKey();
            if (active.isEmpty()) {
                reply(context, "smp.admin.no-active-milestone");
                return;
            }
            final Optional<ObjectiveRow> objective = dao.objective(active.get(), key);
            if (objective.isEmpty()) {
                reply(context, "smp.admin.no-such-objective");
                return;
            }
            // null: an admin's escape hatch has nobody standing behind it, so the milestone it may
            // complete is a network event for everybody rather than a congratulation for whoever
            // typed the command.
            engine.finishObjective(active.get(), objective.get(), null);
            plugin.getLogger().info("an admin completed objective " + active.get() + "/" + key);
        });
        return Command.SINGLE_SUCCESS;
    }

    private int handleUnlockMilestone(final CommandContext<CommandSourceStack> context) {
        final String key = StringArgumentType.getString(context, "key");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            engine.unlockMilestone(key, null);
            plugin.getLogger().info("an admin unlocked milestone " + key);
        });
        reply(context, "smp.admin.milestone-unlocked");
        return Command.SINGLE_SUCCESS;
    }

    private int handleAura(final CommandContext<CommandSourceStack> context) {
        final String name = StringArgumentType.getString(context, "player");
        final int delta = IntegerArgumentType.getInteger(context, "delta");
        final Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            reply(context, "smp.admin.player-offline");
            return Command.SINGLE_SUCCESS;
        }
        final Optional<String> discordId = identities.discordIdOf(target.getUniqueId());
        if (discordId.isEmpty()) {
            reply(context, "smp.error.no-account-link", Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }

        final String by = context.getSource().getSender() instanceof Player admin
                ? admin.getName() : "console";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // The reason carries who did it. An unexplained balance is the thing this row exists to
            // prevent, and an admin's correction is the likeliest one to be questioned.
            dao.addAura(discordId.get(), delta, AuraReason.ADMIN.stored(), "by " + by);
            dao.auraOf(discordId.get())
                    .ifPresent(now -> identities.recordAura(target.getUniqueId(), now));
            plugin.getLogger().info(by + " changed " + name + "'s aura by " + delta);
        });
        reply(context, "smp.admin.aura-changed");
        return Command.SINGLE_SUCCESS;
    }

    /** Answers on the main thread, in the sender's language when there is one. */
    private void reply(final CommandContext<CommandSourceStack> context, final String key) {
        reply(context, key, null);
    }

    /**
     * The same, plus a sound - which the console, being no player, never hears.
     */
    private void reply(final CommandContext<CommandSourceStack> context, final String key,
                       final Feedback feedback) {
        final CommandSender sender = context.getSource().getSender();
        final Locale locale = sender instanceof Player player
                ? locales.of(player.getUniqueId()) : Locale.ENGLISH;
        Bukkit.getScheduler().runTask(plugin, () -> {
            sender.sendMessage(MessageRenderer.of(messages).get(locale, key));
            if (feedback != null && sender instanceof Player player) {
                sounds.play(player, feedback);
            }
        });
    }
}
