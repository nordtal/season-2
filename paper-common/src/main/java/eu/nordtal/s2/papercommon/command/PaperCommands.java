package eu.nordtal.s2.papercommon.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.Confirmations;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Messages;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A {@link Declaration} turned into a real Brigadier tree, once, for all three Paper plugins.
 *
 * <h2>Why an adapter instead of three hand-built trees</h2>
 * The three plugins were building the same shapes by hand - a literal, then literals, then an
 * argument node, then a {@code requires} that asks the same question, then a confirmation the
 * command already declared it needs. Every one of those was an opportunity to get one of them
 * subtly different from the others, and {@code /hg} demonstrated the cost: its gate asked for a
 * {@link Player} and the console could run none of it, on the one command that starts the season's
 * flagship event.
 *
 * <h2>Local and remote look identical to whoever typed it</h2>
 * A command whose {@link Declaration#target()} is this process runs here. Anything else becomes a
 * {@code command_request} row addressed to the process that owns it, and the answer comes back into
 * the same chat. The only difference a person sees is a line saying which process is handling it,
 * and a second or so.
 *
 * <p>Commands targeting {@link Target#PROXY} are deliberately <b>not</b> registered here, and
 * declining them is not an omission. Velocity intercepts a command it knows before the packet ever
 * reaches a backend, so {@code /phase} is already available on all three servers from one
 * registration on the proxy - and a copy here would be shadowed by it, which is the worst of both:
 * dead code that looks live.</p>
 *
 * <h2>The confirmation is honoured here, not decided here</h2>
 * {@link Declaration#irreversible()} is an obligation on adapters. This is one adapter's way of
 * meeting it: the whole command line typed again inside {@link Confirmations#WINDOW}. For a command
 * that travels, the confirmation happens <em>before</em> the row is written - confirming on the far
 * side would mean two round trips and a pending confirmation living in a process the asker cannot
 * see.
 */
public final class PaperCommands {

    /** One registered command: what it is, and what to do when somebody runs it. */
    private record Entry(Declaration declaration, java.util.function.BiConsumer<NordtalUser, Values> run) {
    }

    private final Plugin plugin;
    private final Messages messages;
    private final Target here;
    private final Outbox outbox;
    private final Function<UUID, java.util.Locale> localeOf;
    private final Predicate<UUID> isAdmin;
    private final Function<UUID, Optional<String>> discordIdOf;
    private final PaperUser.Chime chime;
    private final Confirmations confirmations = new Confirmations();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, List<LiteralArgumentBuilder<CommandSourceStack>>> extras =
            new LinkedHashMap<>();
    private final Map<String, java.util.function.Supplier<java.util.Collection<String>>> suggestions =
            new LinkedHashMap<>();

    /**
     * @param here        which process this is, so a command can tell its own from somebody else's
     * @param outbox      how a command reaches another process, or {@code null} to register only
     *                    local ones - which is what a plugin with no database connection would do
     * @param localeOf    the player's language, from the plugin's own cache and never a query
     * @param isAdmin     the admin flag, from the plugin's own cache and never a query: this is
     *                    called from Brigadier's {@code requires}, which runs while a client's
     *                    command tree is being built
     * @param discordIdOf the linked Discord account, for a command that travels and has to say who
     *                    asked
     * @param chime       the sound a reply makes, or {@link PaperUser.Chime#silent()}
     */
    public PaperCommands(final Plugin plugin, final Messages messages, final Target here,
                         final Outbox outbox, final Function<UUID, java.util.Locale> localeOf,
                         final Predicate<UUID> isAdmin,
                         final Function<UUID, Optional<String>> discordIdOf,
                         final PaperUser.Chime chime) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.here = Objects.requireNonNull(here, "here");
        this.outbox = outbox;
        this.localeOf = Objects.requireNonNull(localeOf, "localeOf");
        this.isAdmin = Objects.requireNonNull(isAdmin, "isAdmin");
        this.discordIdOf = Objects.requireNonNull(discordIdOf, "discordIdOf");
        this.chime = Objects.requireNonNull(chime, "chime");
    }

    /** A command this process runs itself. */
    public <E extends CommandEffects> PaperCommands local(final NordtalCommand<E> command,
                                                          final E effects) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(effects, "effects");
        final Declaration declaration = command.declaration();
        if (declaration.isRemoteOn(here)) {
            throw new IllegalArgumentException(declaration.name() + " is run by "
                    + declaration.target() + ", not by " + here);
        }
        entries.add(new Entry(declaration,
                (user, values) -> command.run(user, values, effects)));
        return this;
    }

    /**
     * A command another process runs, reachable from here.
     *
     * <p>Silently skipped for {@link Target#PROXY} and for this process's own target: the first is
     * already served everywhere by Velocity, and the second would be a round trip to ourselves.
     * Skipping rather than throwing is what lets a caller hand over the whole catalogue.</p>
     */
    public PaperCommands remote(final Declaration declaration) {
        Objects.requireNonNull(declaration, "declaration");
        if (declaration.target() == here || declaration.target() == Target.PROXY) {
            return this;
        }
        if (!declaration.surfaces().contains(eu.nordtal.s2.commands.Surface.GAME)) {
            return this;
        }
        if (outbox == null) {
            throw new IllegalStateException(declaration.name() + " has to travel, and this adapter"
                    + " was built without an outbox");
        }
        entries.add(new Entry(declaration, (user, values) -> outbox.send(declaration, user, values)));
        return this;
    }

    /** Every declaration that is not this process's own, in one call. */
    public PaperCommands remoteAll(final List<Declaration> declarations) {
        declarations.forEach(this::remote);
        return this;
    }

    /**
     * What to offer for one argument while somebody is still typing it.
     *
     * <h2>Why the values come from the caller and not from the declaration</h2>
     * A {@link eu.nordtal.s2.commands.Argument.Kind#CHOICE} carries its own values because they are
     * fixed; a milestone key is not. The list of milestones is this server's reloadable YAML, the
     * list of objectives is whichever milestone is active right now, and neither is knowable in a
     * module compiled against no platform. So the declaration says <em>that</em> the argument is a
     * word and the plugin says <em>which</em> words.
     *
     * <p><b>Must not block and must not query.</b> Brigadier asks for suggestions while somebody is
     * typing, once per keystroke, for every client with the command in its tree. An in-memory
     * source is the only kind that belongs here - which is what both callers have anyway, because
     * the track and the active milestone are already cached for the boards.</p>
     */
    public PaperCommands suggest(final Declaration declaration, final String argument,
                                 final java.util.function.Supplier<java.util.Collection<String>> values) {
        if (declaration.arguments().stream().noneMatch(a -> a.name().equals(argument))) {
            throw new IllegalArgumentException(declaration.name() + " has no argument '" + argument
                    + "', so nothing would ever ask for these suggestions");
        }
        suggestions.put(declaration.name() + " " + argument, Objects.requireNonNull(values, "values"));
        return this;
    }

    /**
     * A subtree this adapter did not build, hung under one of its roots.
     *
     * <p>For the commands that are not {@link NordtalCommand}s and should not become ones.
     * {@code /smp update} is the case it exists for: it already travels, through
     * {@code update_request} to a container that is not a command target at all, and its answer is
     * the updater's own report - text that docs/updater.md forbids rendering a second time. Folding
     * it in would mean a second transport for one command and a second rendering of one report.</p>
     *
     * @param root the first path segment it belongs under, which must be one a command here uses
     */
    public PaperCommands extra(final String root,
                               final LiteralArgumentBuilder<CommandSourceStack> node) {
        extras.computeIfAbsent(Objects.requireNonNull(root, "root"), name -> new ArrayList<>())
                .add(Objects.requireNonNull(node, "node"));
        return this;
    }

    /**
     * The trees, one per distinct first path segment.
     *
     * <h2>Assembled bottom-up, and it has to be</h2>
     * Brigadier's {@code ArgumentBuilder.then(ArgumentBuilder)} <b>builds its argument on the
     * spot</b>: the child is turned into an immutable node and copied into the parent there and
     * then. So a tree grown as the paths are walked loses everything added to a node after it was
     * handed to its parent - {@code /smp objective complete} would attach an empty {@code objective}
     * to {@code smp}, and the {@code complete} added a line later would go into a builder nothing
     * refers to any more. The command parses as unknown, and nothing anywhere says why.
     *
     * <p>So the paths are first collected into a plain tree of {@link Node}, and only then
     * materialised depth-first, each node complete before its parent takes it.</p>
     */
    public List<LiteralCommandNode<CommandSourceStack>> build() {
        final Map<String, Node> roots = new LinkedHashMap<>();
        for (final Entry entry : entries) {
            final List<String> path = entry.declaration().path();
            Node node = roots.computeIfAbsent(path.getFirst(), Node::new);
            for (int depth = 1; depth < path.size(); depth++) {
                node = node.children.computeIfAbsent(path.get(depth), Node::new);
            }
            if (node.command != null) {
                throw new IllegalStateException("two commands both claim /"
                        + String.join(" ", path));
            }
            node.command = entry;
        }

        for (final String root : extras.keySet()) {
            if (!roots.containsKey(root)) {
                throw new IllegalStateException("an extra subtree was hung under /" + root
                        + ", which no command here uses as a root - it would never be registered");
            }
        }

        return roots.values().stream()
                .map(root -> {
                    final LiteralArgumentBuilder<CommandSourceStack> builder = materialise(root);
                    extras.getOrDefault(root.literal, List.of()).forEach(builder::then);
                    return builder.requires(this::mayUse).build();
                })
                .toList();
    }

    /** One literal of a command path, with whatever hangs off it. */
    private static final class Node {

        private final String literal;
        private final Map<String, Node> children = new LinkedHashMap<>();
        private Entry command;

        private Node(final String literal) {
            this.literal = literal;
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> materialise(final Node node) {
        final LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(node.literal);
        for (final Node child : node.children.values()) {
            builder.then(materialise(child));
        }

        final boolean runnableHere = node.command != null && arguments(builder, node.command);
        if (!runnableHere) {
            // Nothing can be run by typing exactly this. Brigadier's own answer here is "Unknown or
            // incomplete command, see below for error" with a red caret, which tells somebody who
            // mistyped an argument nothing at all about what the command wanted. So every node that
            // is not itself a command answers with what IS one underneath it.
            builder.executes(context -> help(context, node));
        }
        return builder;
    }

    /**
     * Hang a command's arguments off the last literal of its path.
     *
     * @return whether the literal itself became runnable - which it does only when every required
     *         argument can be left out
     */
    private boolean arguments(final LiteralArgumentBuilder<CommandSourceStack> parent,
                              final Entry entry) {
        final List<eu.nordtal.s2.commands.Argument> arguments = entry.declaration().arguments();
        if (arguments.isEmpty()) {
            parent.executes(context -> run(context, entry, Map.of()));
            return true;
        }

        // Back to front, for the same reason build() is: a node has to be complete before it is
        // handed to its parent.
        RequiredArgumentBuilder<CommandSourceStack, ?> child = null;
        for (int at = arguments.size() - 1; at >= 0; at--) {
            final RequiredArgumentBuilder<CommandSourceStack, ?> node =
                    node(entry.declaration(), arguments.get(at));
            final int index = at;
            // Runnable at this depth only when nothing required is still missing. Otherwise typing
            // half the command answers with the usage line rather than running it with a hole in
            // it - Values would throw about a declaration disagreement, which is a sentence written
            // for a programmer.
            if (satisfied(arguments, index + 1)) {
                node.executes(context -> run(context, entry, read(context, arguments, index + 1)));
            } else {
                node.executes(context -> usage(context, entry.declaration()));
            }
            if (child != null) {
                node.then(child);
            }
            child = node;
        }

        parent.then(child);
        if (arguments.getFirst().required()) {
            return false;
        }
        parent.executes(context -> run(context, entry, Map.of()));
        return true;
    }

    /** Whether a command given its first {@code count} arguments has everything it needs. */
    private static boolean satisfied(final List<eu.nordtal.s2.commands.Argument> arguments,
                                     final int count) {
        for (int at = count; at < arguments.size(); at++) {
            if (arguments.get(at).required()) {
                return false;
            }
        }
        return true;
    }

    /**
     * What can be typed here, and what each one is for.
     *
     * <h2>Why this replaces Brigadier's own message</h2>
     * "Unknown or incomplete command, see below for error" plus a red caret is an answer about the
     * parser, not about the command. Somebody who typed {@code /smp aura} and got it learns that
     * something is wrong and nothing about what - and the arguments are already declared, so the
     * server knows exactly what was missing.
     *
     * <p>One line per command underneath: the usage, derived from the declaration so it cannot go
     * stale, and one sentence saying what it does. Sorted, because a list whose order depends on
     * registration order reads as random.</p>
     */
    private int help(final CommandContext<CommandSourceStack> context, final Node node) {
        final NordtalUser user = user(context.getSource().getSender());
        final List<Declaration> below = new ArrayList<>();
        collect(node, below);

        if (below.isEmpty()) {
            // Only reachable for a root whose every command was skipped by remote(), which today
            // cannot happen - a root exists because something was added under it.
            user.reply("command.help.nothing", Map.of(), Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }
        if (below.size() == 1) {
            return usage(context, below.getFirst());
        }

        user.reply("command.help.header", Map.of("command", "/" + node.literal));
        below.stream()
                .sorted(java.util.Comparator.comparing(Declaration::name))
                .forEach(declaration -> user.reply("command.help.line",
                        Map.of("usage", declaration.usage(),
                                "what", user.phrase(declaration.describeKey()))));
        return Command.SINGLE_SUCCESS;
    }

    /** The usage of one command, plus the sentence saying what it is for. */
    private int usage(final CommandContext<CommandSourceStack> context,
                      final Declaration declaration) {
        final NordtalUser user = user(context.getSource().getSender());
        user.reply("command.help.usage", Map.of("usage", declaration.usage()), Feedback.REFUSED);
        user.reply("command.help.what", Map.of("what", user.phrase(declaration.describeKey())));
        return Command.SINGLE_SUCCESS;
    }

    private static void collect(final Node node, final List<Declaration> into) {
        if (node.command != null) {
            into.add(node.command.declaration());
        }
        node.children.values().forEach(child -> collect(child, into));
    }

    private RequiredArgumentBuilder<CommandSourceStack, ?> node(final Declaration declaration,
                                                               final eu.nordtal.s2.commands.Argument argument) {
        final java.util.function.Supplier<java.util.Collection<String>> offered =
                suggestions.get(declaration.name() + " " + argument.name());
        return switch (argument.kind()) {
            case WORD -> {
                final RequiredArgumentBuilder<CommandSourceStack, ?> word =
                        Commands.argument(argument.name(), StringArgumentType.word());
                if (offered != null) {
                    word.suggests((context, builder) -> {
                        offered.get().forEach(builder::suggest);
                        return builder.buildFuture();
                    });
                }
                yield word;
            }
            case GREEDY_STRING ->
                    Commands.argument(argument.name(), StringArgumentType.greedyString());
            case INTEGER -> Commands.argument(argument.name(),
                    IntegerArgumentType.integer(argument.min(), argument.max()));
            case PLAYER -> Commands.argument(argument.name(), StringArgumentType.word())
                    .suggests((context, builder) -> {
                        for (final Player online : Bukkit.getOnlinePlayers()) {
                            builder.suggest(online.getName());
                        }
                        return builder.buildFuture();
                    });
            case CHOICE -> Commands.argument(argument.name(), StringArgumentType.word())
                    .suggests((context, builder) -> {
                        argument.choices().forEach(builder::suggest);
                        return builder.buildFuture();
                    });
        };
    }

    /** Everything Brigadier parsed, in the shapes {@link Values} hands out. */
    private static Map<String, Object> read(final CommandContext<CommandSourceStack> context,
                                            final List<eu.nordtal.s2.commands.Argument> arguments,
                                            final int count) {
        final Map<String, Object> values = new LinkedHashMap<>();
        for (int at = 0; at < count; at++) {
            final eu.nordtal.s2.commands.Argument argument = arguments.get(at);
            switch (argument.kind()) {
                case INTEGER -> values.put(argument.name(),
                        IntegerArgumentType.getInteger(context, argument.name()));
                case PLAYER -> {
                    final Player target =
                            Bukkit.getPlayerExact(StringArgumentType.getString(context, argument.name()));
                    if (target == null) {
                        // Left absent. run() turns that into "that player is not online" rather
                        // than letting Values throw about a declaration disagreement, which is what
                        // a missing required argument means everywhere else.
                        return values;
                    }
                    values.put(argument.name(), target.getUniqueId());
                }
                default -> values.put(argument.name(),
                        StringArgumentType.getString(context, argument.name()));
            }
        }
        return values;
    }

    private int run(final CommandContext<CommandSourceStack> context, final Entry entry,
                    final Map<String, Object> values) {
        final NordtalUser user = user(context.getSource().getSender());

        // A player argument that resolved to nobody. Answered here rather than by the command,
        // because "that name is not on this server" is a property of the surface the name was typed
        // on: in Discord the same argument is a member picked from a list and cannot miss.
        for (final eu.nordtal.s2.commands.Argument argument : entry.declaration().arguments()) {
            if (argument.required() && !values.containsKey(argument.name())
                    && argument.kind() == eu.nordtal.s2.commands.Argument.Kind.PLAYER) {
                user.reply("command.player-offline", Map.of(), Feedback.REFUSED);
                return Command.SINGLE_SUCCESS;
            }
        }

        if (entry.declaration().irreversible() && !confirmed(user, context.getInput())) {
            return Command.SINGLE_SUCCESS;
        }
        entry.run().accept(user, new Values(entry.declaration(), values));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * "Type it again", keyed on the exact line.
     *
     * <p>The whole input including its arguments, so a pending {@code /smp milestone unlock
     * ancient-debris} cannot be spent on a different milestone typed thirty seconds later.</p>
     */
    private boolean confirmed(final NordtalUser user, final String input) {
        final String what = input.startsWith("/") ? input : "/" + input;
        if (confirmations.confirm(user, what)) {
            return true;
        }
        user.reply("command.confirm.retype", Map.of(
                "command", what,
                "seconds", String.valueOf(Confirmations.WINDOW.toSeconds())), Feedback.REFUSED);
        return false;
    }

    /**
     * Whether the sender may use any of this.
     *
     * <h2>"Not a player" is not the same as "the console"</h2>
     * A {@code BlockCommandSender} is not a player, and neither is the {@code ProxiedCommandSender}
     * that {@code /execute as ... run ...} produces, nor a datapack function's sender. Asking for
     * the console <em>by type</em> is what keeps a command block on a season where players build
     * things from reaching {@code /smp milestone unlock}.
     */
    private boolean mayUse(final CommandSourceStack source) {
        return mayUse(source.getSender(), isAdmin);
    }

    /**
     * The decision on its own: a player who is flagged admin, or the console. Nothing else.
     *
     * <p>Public and static so it can be asserted without a server, which is the only part of a
     * command tree that ever can be. It was {@code SmpCommand.mayUse} until 2026-09-05, tested
     * there, and copied nowhere - which meant {@code limbo} and {@code hunger-games} each had their
     * own answer to the same question, and one of them was wrong.</p>
     */
    public static boolean mayUse(final CommandSender sender, final Predicate<UUID> isAdmin) {
        if (sender instanceof Player player) {
            return isAdmin.test(player.getUniqueId());
        }
        return sender instanceof ConsoleCommandSender;
    }

    private NordtalUser user(final CommandSender sender) {
        if (sender instanceof Player player) {
            // admin is true without a lookup: the tree is gated on mayUse before any handler runs,
            // so reaching here IS the admin check.
            return PaperUser.of(plugin, player, localeOf.apply(player.getUniqueId()), true,
                    discordIdOf.apply(player.getUniqueId()).orElse(null), messages, chime);
        }
        return PaperUser.console(plugin, sender, messages);
    }
}
