package eu.nordtal.season.smp.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.ToneColours;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.command.PaperUser;
import eu.nordtal.season.papercommon.command.Replies;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.SmpTexts;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.navigate.NavigateGui;
import eu.nordtal.season.smp.navigate.Navigation;
import eu.nordtal.season.smp.navigate.NavigationTarget;
import eu.nordtal.season.smp.navigate.PlaceDao;
import eu.nordtal.season.smp.navigate.PoiRow;
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
 * The commands {@code /navigate} and {@code /poi}. Every database call runs off the main thread.
 *
 * Every player may add a POI. Only its creator or an admin may delete it.
 */
public final class NavigateCommand {

    private static final int MAX_POI_NAME = 32;

    static final DocumentSpec<DocumentSpec.None> NOTHING_TO_CANCEL = DocumentSpec.game(
                    "smp", "navigate-nothing-to-cancel", "Navigate", DocumentSpec.Shape.CHAT)
            .titled("Navigation already off");

    record Named(@Example("Home") String name) {}

    record MaxLength(@Example("32") int max) {}

    /** The plain help line of a subcommand, which the command help's line takes in. */
    record Described(Sub sub) {}

    static final DocumentSpec<Named> ADDED = poi("poi-added", Named.class).titled("Point of interest added");
    static final DocumentSpec<Named> REMOVED = poi("poi-removed", Named.class).titled("Point of interest removed");
    static final DocumentSpec<Named> DUPLICATE =
            poi("poi-duplicate", Named.class).titled("Name taken");
    static final DocumentSpec<Named> NOT_FOUND =
            poi("poi-not-found", Named.class).titled("Point of interest not found");
    /** Points of interest are public to see and to use; removing is the one asymmetry. */
    static final DocumentSpec<DocumentSpec.None> NOT_YOURS = DocumentSpec.game(
                    "smp", "poi-not-yours", "Points of interest", DocumentSpec.Shape.CHAT)
            .titled("Point of interest of another player");

    static final DocumentSpec<MaxLength> BAD_NAME =
            poi("poi-bad-name", MaxLength.class).titled("Name too long");
    static final DocumentSpec<Described> DESCRIBE =
            poi("poi-describe", Described.class).titled("Command descriptions");

    private static <V extends Record> DocumentSpec<V> poi(final String name, final Class<V> values) {
        return DocumentSpec.game("smp", name, "Points of interest", DocumentSpec.Shape.CHAT, values);
    }

    private final Plugin plugin;
    private final PlaceDao dao;
    private final Navigation navigation;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final SmpSounds sounds;
    private final java.util.function.Supplier<ToneColours> colours;

    public NavigateCommand(
            final Plugin plugin,
            final PlaceDao dao,
            final Navigation navigation,
            final Identities identities,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final SmpSounds sounds,
            final java.util.function.Supplier<ToneColours> colours) {
        this.plugin = plugin;
        this.dao = dao;
        this.navigation = navigation;
        this.identities = identities;
        this.renderer = renderer;
        this.documents = documents;
        this.sounds = sounds;
        this.colours = colours;
    }

    public LiteralCommandNode<CommandSourceStack> navigate() {
        return Commands.literal("navigate")
                .requires(source -> source.getSender() instanceof Player)
                .executes(this::openGui)
                .then(Commands.literal("cancel").executes(this::cancel))
                .build();
    }

    public LiteralCommandNode<CommandSourceStack> poi() {
        return Commands.literal("poi")
                .requires(source -> source.getSender() instanceof Player)
                .executes(this::poiHelp)
                .then(subcommand(Sub.ADD, this::addPoi))
                .then(subcommand(Sub.REMOVE, this::removePoi))
                .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> subcommand(
            final Sub sub, final Command<CommandSourceStack> action) {
        return Commands.literal(sub.literal)
                .executes(context -> usage(context, sub))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(action));
    }

    /** The {@code /poi} subcommands. The command tree and the help text both read it. */
    enum Sub {
        ADD("add"),
        REMOVE("remove");

        private final String literal;

        Sub(final String literal) {
            this.literal = literal;
        }

        String usage() {
            return "/poi " + literal + " <name>";
        }

        Sent<Described> describe() {
            return DESCRIBE.with(new Described(this));
        }
    }

    private int poiHelp(final CommandContext<CommandSourceStack> context) {
        final PaperUser user = user(context);
        user.reply(Replies.HELP_HEADER.with(new Replies.Typed("/poi")));
        for (final Sub sub : Sub.values()) {
            user.reply(Replies.HELP_LINE.with(new Replies.Usage(sub.usage(), user.phrase(sub.describe()))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int usage(final CommandContext<CommandSourceStack> context, final Sub sub) {
        final PaperUser user = user(context);
        user.reply(
                Replies.HELP_USAGE.with(new Replies.Usage(sub.usage(), user.phrase(sub.describe()))), Feedback.REFUSED);
        return Command.SINGLE_SUCCESS;
    }

    /** Returns the sender as a user. The admin flag comes from the cache, because this runs on the main thread. */
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
                new NavigateGui(renderer, documents, identities, navigation, player, lastDeath, pois).open(player);
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int cancel(final CommandContext<CommandSourceStack> context) {
        final PaperUser user = user(context);
        final UUID uuid = ((Player) context.getSource().getSender()).getUniqueId();
        if (!navigation.isNavigating(uuid)) {
            user.reply(Sent.of(NOTHING_TO_CANCEL), Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }
        navigation.clear(uuid);
        user.reply(Sent.of(NavigateGui.STOPPED));
        return Command.SINGLE_SUCCESS;
    }

    private int addPoi(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        final Locale locale = identities.languageOf(player.getUniqueId());
        final String name = StringArgumentType.getString(context, "name").trim();

        if (name.isEmpty() || name.length() > MAX_POI_NAME) {
            tell(player, renderer.format(locale, BAD_NAME.with(new MaxLength(MAX_POI_NAME))), Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }

        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            tell(player, renderer.format(locale, Sent.of(SmpTexts.NO_ACCOUNT_LINK)), Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }

        final Location at = java.util.Objects.requireNonNull(player.getLocation());
        PaperScheduler.of(plugin).execute(() -> {
            if (dao.allPois().stream().anyMatch(poi -> poi.name().equalsIgnoreCase(name))) {
                tell(player, renderer.format(locale, DUPLICATE.with(new Named(name))), Feedback.REFUSED);
                return;
            }
            dao.createPoi(
                    name,
                    at.getWorld().getName(),
                    at.getBlockX(),
                    at.getBlockY(),
                    at.getBlockZ(),
                    discordId.get().value());
            tell(player, renderer.format(locale, ADDED.with(new Named(name))), Feedback.SMALL_SUCCESS);
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
                tell(player, renderer.format(locale, NOT_FOUND.with(new Named(name))), Feedback.REFUSED);
                return;
            }
            final PoiRow poi = found.get();
            if (!admin
                    && !poi.createdBy().equals(discordId.map(DiscordId::value).orElse(""))) {
                tell(player, renderer.format(locale, Sent.of(NOT_YOURS)), Feedback.REFUSED);
                return;
            }
            dao.deletePoi(poi.id());
            PaperScheduler.of(plugin).onMain(() -> navigation.clearWorld(poi.world()));
            tell(player, renderer.format(locale, REMOVED.with(new Named(name))), Feedback.SMALL_SUCCESS);
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Sends the message and the sound in one main-thread task. */
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
