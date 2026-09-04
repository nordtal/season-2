package eu.nordtal.s2.smp.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
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
                .then(Commands.literal("add")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(this::addPoi)))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(this::removePoi)))
                .build();
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
