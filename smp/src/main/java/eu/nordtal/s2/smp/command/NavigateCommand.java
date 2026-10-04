package eu.nordtal.s2.smp.command;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messagerendering.ToneColours;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.PaperCommonMessages;
import eu.nordtal.s2.papercommon.command.PaperUser;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.papercommon.time.PaperScheduler;
import eu.nordtal.s2.smp.SmpMessages;
import eu.nordtal.s2.smp.db.PoiRow;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.navigate.NavigateGui;
import eu.nordtal.s2.smp.navigate.Navigation;
import eu.nordtal.s2.smp.navigate.NavigationTarget;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
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
    private final MessageRenderer renderer;
    private final SmpSounds sounds;
    private final java.util.function.Supplier<ToneColours> colours;

    public NavigateCommand(
            final Plugin plugin,
            final SmpDao dao,
            final Navigation navigation,
            final Identities identities,
            final MessageRenderer renderer,
            final SmpSounds sounds,
            final java.util.function.Supplier<ToneColours> colours) {
        this.plugin = plugin;
        this.dao = dao;
        this.navigation = navigation;
        this.identities = identities;
        this.renderer = renderer;
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
        final PaperUser user = user(context);
        user.reply(PaperCommonMessages.MESSAGES.command().help().header("/poi"), Tone.NEUTRAL);
        for (final Sub sub : Sub.values()) {
            user.reply(
                    PaperCommonMessages.MESSAGES.command().help().line(sub.usage(), user.phrase(sub.describe())),
                    Tone.MUTED);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int usage(final CommandContext<CommandSourceStack> context, final Sub sub) {
        final PaperUser user = user(context);
        user.reply(PaperCommonMessages.MESSAGES.command().help().usage(sub.usage()), Feedback.REFUSED, Tone.NEUTRAL);
        user.reply(PaperCommonMessages.MESSAGES.command().help().what(user.phrase(sub.describe())), Tone.MUTED);
        return Command.SINGLE_SUCCESS;
    }

    /** Returns whoever typed it, with the admin flag from the cache, since this runs on the main thread. */
    private PaperUser user(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        return PaperUser.of(
                plugin,
                player,
                identities.languageOf(player.getUniqueId()),
                identities.of(player.getUniqueId()).admin(),
                () -> identities.discordIdOf(player.getUniqueId()),
                renderer,
                sounds::play,
                colours);
    }

    private int openGui(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        final UUID uuid = player.getUniqueId();

        PaperScheduler.of(plugin).execute(() -> {
            final List<PoiRow> pois = dao.allPois();
            final Optional<NavigationTarget> lastDeath = identities
                    .discordIdOf(uuid)
                    .flatMap(dao::lastDeathOf)
                    .map(place -> NavigationTarget.lastDeath(place.world(), place.x(), place.y(), place.z()));

            PaperScheduler.of(plugin).onMain(() -> {
                if (!player.isOnline()) {
                    return;
                }
                new NavigateGui(renderer, identities, navigation, player, lastDeath, pois).open(player);
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int addPoi(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        final Locale locale = identities.languageOf(player.getUniqueId());
        final String name = StringArgumentType.getString(context, "name").trim();

        if (name.isEmpty() || name.length() > MAX_POI_NAME) {
            tell(player, renderer.format(locale, MESSAGES.smp().poi().badName(MAX_POI_NAME)), Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }

        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            tell(player, renderer.format(locale, MESSAGES.smp().error().noAccountLink()), Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }

        final Location at = java.util.Objects.requireNonNull(player.getLocation());
        PaperScheduler.of(plugin).execute(() -> {
            if (dao.allPois().stream().anyMatch(poi -> poi.name().equalsIgnoreCase(name))) {
                tell(player, renderer.format(locale, MESSAGES.smp().poi().duplicate(name)), Feedback.REFUSED);
                return;
            }
            dao.createPoi(
                    name,
                    at.getWorld().getName(),
                    at.getBlockX(),
                    at.getBlockY(),
                    at.getBlockZ(),
                    discordId.get().value());
            tell(player, renderer.format(locale, MESSAGES.smp().poi().added(name)), Feedback.SMALL_SUCCESS);
        });
        return Command.SINGLE_SUCCESS;
    }

    private int removePoi(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        final Locale locale = identities.languageOf(player.getUniqueId());
        final String name = StringArgumentType.getString(context, "name").trim();
        final boolean admin = identities.of(player.getUniqueId()).admin();
        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());

        PaperScheduler.of(plugin).execute(() -> {
            final Optional<PoiRow> found = dao.allPois().stream()
                    .filter(poi -> poi.name().equalsIgnoreCase(name))
                    .findFirst();
            if (found.isEmpty()) {
                tell(player, renderer.format(locale, MESSAGES.smp().poi().notFound(name)), Feedback.REFUSED);
                return;
            }
            final PoiRow poi = found.get();
            if (!admin
                    && !poi.createdBy().equals(discordId.map(DiscordId::value).orElse(""))) {
                tell(player, renderer.format(locale, MESSAGES.smp().poi().notYours()), Feedback.REFUSED);
                return;
            }
            dao.deletePoi(poi.id());
            PaperScheduler.of(plugin).onMain(() -> navigation.clearWorld(poi.world()));
            tell(player, renderer.format(locale, MESSAGES.smp().poi().removed(name)), Feedback.SMALL_SUCCESS);
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Sends a message and its sound in one hop to the main thread, so they never land a tick apart. */
    private void tell(final Player player, final Component message, final Feedback feedback) {
        PaperScheduler.of(plugin).onMain(() -> {
            if (player.isOnline()) {
                player.sendMessage(message);
                if (feedback != null) {
                    sounds.play(player, feedback);
                }
            }
        });
    }
}
