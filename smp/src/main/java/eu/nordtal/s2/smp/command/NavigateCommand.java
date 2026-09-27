package eu.nordtal.s2.smp.command;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.nordtal.s2.commands.CommandMessages;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.papercommon.command.PaperUser;
import eu.nordtal.s2.smp.SmpMessages;
import eu.nordtal.s2.smp.db.PoiRow;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.navigate.NavigateGui;
import eu.nordtal.s2.smp.navigate.Navigation;
import eu.nordtal.s2.smp.navigate.NavigationTarget;
import eu.nordtal.s2.smp.player.Identities;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /navigate} and {@code /poi}; every database call runs off the main thread.
 *
 * POIs are public and unlimited; only the creator or an admin may delete one.
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
    private final java.util.function.Supplier<ToneColours> colours;

    public NavigateCommand(
            final Plugin plugin,
            final SmpDao dao,
            final Navigation navigation,
            final Identities identities,
            final Messages messages,
            final PlayerLocales locales,
            final SmpSounds sounds,
            final java.util.function.Supplier<ToneColours> colours) {
        this.plugin = plugin;
        this.dao = dao;
        this.navigation = navigation;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
        this.colours = colours;
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
                // Every node below answers when typed on its own.
                .executes(this::poiHelp)
                .then(subcommand(Sub.ADD, this::addPoi))
                .then(subcommand(Sub.REMOVE, this::removePoi))
                .build();
    }

    /** One {@code /poi} subcommand: the literal, its name argument, and its own usage line. */
    private LiteralArgumentBuilder<CommandSourceStack> subcommand(
            final Sub sub, final Command<CommandSourceStack> action) {
        return Commands.literal(sub.literal)
                .executes(context -> usage(context, sub))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(action));
    }

    /** What {@code /poi} takes; both the tree and the help are built from this, so the usage cannot go stale. */
    private enum Sub {
        ADD("add"),
        REMOVE("remove");

        private final String literal;

        Sub(final String literal) {
            this.literal = literal;
        }

        /** Returns the usage, as in {@code /poi add <name>}. */
        String usage() {
            return "/poi " + literal + " <name>";
        }

        MessageRef describe() {
            return switch (this) {
                case ADD -> SmpMessages.MESSAGES.command().describe().poi().add();
                case REMOVE -> SmpMessages.MESSAGES.command().describe().poi().remove();
            };
        }
    }

    /** Answers {@code /poi} typed alone, with the adapter's own help keys, since this tree is built by hand. */
    private int poiHelp(final CommandContext<CommandSourceStack> context) {
        final NordtalUser user = user(context);
        user.reply(CommandMessages.MESSAGES.command().help().header("/poi"), Tone.NEUTRAL);
        for (final Sub sub : Sub.values()) {
            user.reply(
                    CommandMessages.MESSAGES.command().help().line(sub.usage(), user.phrase(sub.describe())),
                    Tone.MUTED);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int usage(final CommandContext<CommandSourceStack> context, final Sub sub) {
        final NordtalUser user = user(context);
        user.reply(CommandMessages.MESSAGES.command().help().usage(sub.usage()), Feedback.REFUSED, Tone.NEUTRAL);
        user.reply(CommandMessages.MESSAGES.command().help().what(user.phrase(sub.describe())), Tone.MUTED);
        return Command.SINGLE_SUCCESS;
    }

    /** Returns whoever typed it, with the admin flag from the cache, since this runs on the main thread. */
    private NordtalUser user(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        return PaperUser.of(
                plugin,
                player,
                locales.of(player.getUniqueId()),
                identities.of(player.getUniqueId()).admin(),
                () -> identities.discordIdOf(player.getUniqueId()),
                messages,
                sounds::play,
                colours);
    }

    private int openGui(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        final UUID uuid = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final List<PoiRow> pois = dao.allPois();
            final Optional<NavigationTarget> lastDeath = identities
                    .discordIdOf(uuid)
                    .flatMap(dao::lastDeathOf)
                    .map(place -> NavigationTarget.lastDeath(place.world(), place.x(), place.y(), place.z()));

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.openInventory(
                        new NavigateGui(messages, locales, navigation, player, lastDeath, pois).getInventory());
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int addPoi(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        final Locale locale = locales.of(player.getUniqueId());
        final String name = StringArgumentType.getString(context, "name").trim();

        if (name.isEmpty() || name.length() > MAX_POI_NAME) {
            tell(
                    player,
                    MessageRenderer.of(messages)
                            .format(locale, MESSAGES.smp().poi().badName(MAX_POI_NAME)),
                    Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }

        final Optional<String> discordId = identities.discordIdOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            tell(
                    player,
                    MessageRenderer.of(messages)
                            .format(locale, MESSAGES.smp().error().noAccountLink()),
                    Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }

        final Location at = java.util.Objects.requireNonNull(player.getLocation());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (dao.allPois().stream().anyMatch(poi -> poi.name().equalsIgnoreCase(name))) {
                tell(
                        player,
                        MessageRenderer.of(messages)
                                .format(locale, MESSAGES.smp().poi().duplicate(name)),
                        Feedback.REFUSED);
                return;
            }
            dao.createPoi(
                    name, at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), discordId.get());
            tell(
                    player,
                    MessageRenderer.of(messages)
                            .format(locale, MESSAGES.smp().poi().added(name)),
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
                tell(
                        player,
                        MessageRenderer.of(messages)
                                .format(locale, MESSAGES.smp().poi().notFound(name)),
                        Feedback.REFUSED);
                return;
            }
            final PoiRow poi = found.get();
            if (!admin && !poi.createdBy().equals(discordId.orElse(""))) {
                tell(
                        player,
                        MessageRenderer.of(messages)
                                .format(locale, MESSAGES.smp().poi().notYours()),
                        Feedback.REFUSED);
                return;
            }
            dao.deletePoi(poi.id());
            Bukkit.getScheduler().runTask(plugin, () -> navigation.clearWorld(poi.world()));
            tell(
                    player,
                    MessageRenderer.of(messages)
                            .format(locale, MESSAGES.smp().poi().removed(name)),
                    Feedback.SMALL_SUCCESS);
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Sends a message and its sound in one hop to the main thread, so they never land a tick apart. */
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
