package eu.nordtal.s2.smp.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.papercommon.command.PaperUser;
import eu.nordtal.s2.smp.db.PlaceRow;
import eu.nordtal.s2.smp.db.PoiRow;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.navigate.NavigateGui;
import eu.nordtal.s2.smp.navigate.Navigation;
import eu.nordtal.s2.smp.navigate.NavigationTarget;
import eu.nordtal.s2.smp.player.Identities;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /navigate} and {@code /poi}.
 *
 * <p><b>Every database call in here runs off the main thread</b> and hops back only to open an
 * inventory or send a line. That rule was written into this repository on 2026-09-01 after
 * {@code /hg start} was found doing the opposite, and a command is exactly where it is easiest to
 * forget: it is typed rarely, so a slow query there looks like nothing until the day the database
 * is slow and the whole server stutters with it.
 *
 * <p>POIs are public and unlimited: anyone may create one and everyone sees all of them. Deleting is
 * the one asymmetry - <b>your own, or anybody's if you are an admin</b> - which is the narrowest
 * rule that still lets a mistake be cleaned up without letting anybody erase somebody else's work.
 */
public final class NavigateCommand {

    private static final int MAX_POI_NAME = 32;

    private final Plugin plugin;
    private final SmpDao dao;
    private final Navigation navigation;
    private final Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSounds sounds;

    public NavigateCommand(final Plugin plugin, final SmpDao dao, final Navigation navigation,
                           final Identities identities, final Messages messages,
                           final PlayerLocales locales, final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.navigation = navigation;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
    }

    public LiteralCommandNode<CommandSourceStack> navigate() {
        return Commands.literal("navigate")
                .requires(source -> source.getSender() instanceof Player)
                .executes(this::openGui)
                .build();
    }

    public LiteralCommandNode<CommandSourceStack> poi() {
        return Commands.literal("poi")
                .requires(source -> source.getSender() instanceof Player)
                // Every node below is reachable by typing exactly it, and every one of them
                // answers - see the class comment. A bare /poi lists what it takes; /poi add
                // says what it is still missing.
                .executes(this::poiHelp)
                .then(subcommand(Sub.ADD, this::addPoi))
                .then(subcommand(Sub.REMOVE, this::removePoi))
                .build();
    }

    /**
     * One {@code /poi} subcommand: the literal, its name argument, and the usage line the literal
     * answers with on its own.
     */
    private LiteralArgumentBuilder<CommandSourceStack> subcommand(
            final Sub sub, final Command<CommandSourceStack> action) {
        return Commands.literal(sub.literal)
                .executes(context -> usage(context, sub))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(action));
    }

    /**
     * What {@code /poi} takes, and the one place it is written down.
     *
     * <p>The tree is built from this and so is the help, for the reason
     * {@code Declaration#usage} gives: a usage line kept by hand next to a command is the first
     * thing to go stale when an argument is added, and the way it goes stale is that it keeps
     * telling people to type something that no longer parses.</p>
     */
    private enum Sub {

        ADD("add"),
        REMOVE("remove");

        private final String literal;

        Sub(final String literal) {
            this.literal = literal;
        }

        /** {@code /poi add <name>} - the same convention {@code Declaration#usage} uses. */
        String usage() {
            return "/poi " + literal + " <name>";
        }

        String describeKey() {
            return "command.describe.poi." + literal;
        }
    }

    // ------------------------------------------------------------------ /poi help

    /**
     * What can be typed here, and what each one is for.
     *
     * <h2>Why this is duplicated from PaperCommands rather than shared</h2>
     * {@code PaperCommands} gives every node of a {@link eu.nordtal.s2.commands.Declaration} tree
     * this answer already, and until 2026-09-09 {@code /poi} had none - a bare {@code /poi} fell
     * through to {@code UnknownCommandEvent} and told a player "That command does not exist" about
     * a command that does. This is one of the two hand-built trees in the repository (see
     * {@code SmpPlugin#registerCommands} for why they are hand-built), so it carries the answer
     * itself. It uses the adapter's own four message keys, so the wording and the shape stay one
     * decision and an operator's override reaches both.
     */
    private int poiHelp(final CommandContext<CommandSourceStack> context) {
        final NordtalUser user = user(context);
        user.reply("command.help.header", Map.of("command", "/poi"), Tone.NEUTRAL);
        for (final Sub sub : Sub.values()) {
            user.reply("command.help.line",
                    Map.of("usage", sub.usage(), "what", user.phrase(sub.describeKey())),
                    Tone.MUTED);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** The usage of one subcommand, plus the sentence saying what it is for. */
    private int usage(final CommandContext<CommandSourceStack> context, final Sub sub) {
        final NordtalUser user = user(context);
        user.reply("command.help.usage", Map.of("usage", sub.usage()), Feedback.REFUSED,
                Tone.NEUTRAL);
        user.reply("command.help.what", Map.of("what", user.phrase(sub.describeKey())), Tone.MUTED);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Whoever typed it - always a player, since the root's {@code requires} refuses the console.
     *
     * <p>The admin flag comes from the cache and not a query: this runs on the main thread, inside
     * a Brigadier handler, on a command any player can type.</p>
     */
    private NordtalUser user(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        return PaperUser.of(plugin, player, locales.of(player.getUniqueId()),
                identities.of(player.getUniqueId()).admin(),
                () -> identities.discordIdOf(player.getUniqueId()), messages, sounds::play);
    }

    // ------------------------------------------------------------------ /navigate

    private int openGui(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        final UUID uuid = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final List<PoiRow> pois = dao.allPois();
            final Optional<NavigationTarget> lastDeath = identities.discordIdOf(uuid)
                    .flatMap(dao::lastDeathOf)
                    .map(place -> NavigationTarget.lastDeath(place.world(), place.x(), place.y(), place.z()));

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.openInventory(new NavigateGui(messages, locales, navigation, player,
                        lastDeath, pois).getInventory());
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ /poi

    private int addPoi(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        final Locale locale = locales.of(player.getUniqueId());
        final String name = StringArgumentType.getString(context, "name").trim();

        if (name.isEmpty() || name.length() > MAX_POI_NAME) {
            tell(player, MessageRenderer.of(messages).format(locale, "smp.poi.bad-name", "max", MAX_POI_NAME),
                    Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }

        final Optional<String> discordId = identities.discordIdOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            tell(player, MessageRenderer.of(messages).get(locale, "smp.error.no-account-link"),
                    Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }

        final Location at = player.getLocation();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (dao.allPois().stream().anyMatch(poi -> poi.name().equalsIgnoreCase(name))) {
                tell(player, MessageRenderer.of(messages).format(locale, "smp.poi.duplicate", "name", name),
                        Feedback.REFUSED);
                return;
            }
            dao.createPoi(name, at.getWorld().getName(), at.getBlockX(), at.getBlockY(),
                    at.getBlockZ(), discordId.get());
            tell(player, MessageRenderer.of(messages).format(locale, "smp.poi.added", "name", name),
                    Feedback.SMALL_SUCCESS);
        });
        return Command.SINGLE_SUCCESS;
    }

    private int removePoi(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        final Locale locale = locales.of(player.getUniqueId());
        final String name = StringArgumentType.getString(context, "name").trim();
        final boolean admin = identities.of(player.getUniqueId()).admin();
        final Optional<String> discordId = identities.discordIdOf(player.getUniqueId());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final Optional<PoiRow> found = dao.allPois().stream()
                    .filter(poi -> poi.name().equalsIgnoreCase(name))
                    .findFirst();
            if (found.isEmpty()) {
                tell(player, MessageRenderer.of(messages).format(locale, "smp.poi.not-found", "name", name),
                        Feedback.REFUSED);
                return;
            }
            final PoiRow poi = found.get();
            if (!admin && !poi.createdBy().equals(discordId.orElse(""))) {
                tell(player, MessageRenderer.of(messages).get(locale, "smp.poi.not-yours"),
                        Feedback.REFUSED);
                return;
            }
            dao.deletePoi(poi.id());
            Bukkit.getScheduler().runTask(plugin, () -> navigation.clearWorld(poi.world()));
            // The counterpart of smp.poi.added, and it gets the counterpart's sound: a small
            // thing the player asked for that worked.
            tell(player, MessageRenderer.of(messages).format(locale, "smp.poi.removed", "name", name),
                    Feedback.SMALL_SUCCESS);
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Sends one already-rendered message on the main thread, from wherever it is called. */
    private void tell(final Player player, final Component message) {
        tell(player, message, null);
    }

    /**
     * The same, plus a sound.
     *
     * <p>Both in the one hop back to the main thread: the message and its sound belong to the same
     * moment, and scheduling them separately is how they end up a tick apart.
     */
    private void tell(final Player player, final Component message, final Feedback feedback) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(message);
                if (feedback != null) {
                    sounds.play(player, feedback);
                }
            }
        });
    }
}
