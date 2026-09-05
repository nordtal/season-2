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
import org.bukkit.plugin.IllegalPluginAccessException;
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
    private record Entry(Declaration declaration,
                         java.util.function.BiConsumer<NordtalUser, Values> run,
                         java.util.function.Function<Values,
                                 java.util.Optional<Map.Entry<String, Map<String, ?>>>> problem) {
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
    private final Map<String, List<LiteralArgumentBuilder<CommandSourceStack>>> openExtras =
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
                (user, values) -> command.run(user, values, effects),
                command::check));
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
        // A remote command's own problem() cannot be asked here: this process holds the
        // declaration but not the command, and the command is where the check lives. It is asked on
        // the far side instead, which costs a round trip for a typo and keeps one implementation.
        entries.add(new Entry(declaration, (user, values) -> outbox.send(declaration, user, values),
                values -> java.util.Optional.empty()));
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
        final eu.nordtal.s2.commands.Argument declared = declaration.arguments().stream()
                .filter(a -> a.name().equals(argument))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(declaration.name()
                        + " has no argument '" + argument
                        + "', so nothing would ever ask for these suggestions"));
        // node() applies these in the WORD branch and only there - every other kind brings its own
        // suggestions, which is the point of being that kind. Registering them for one of those
        // used to be accepted and then silently ignored, which is the worst of the three possible
        // behaviours: nothing offered, and nothing said.
        if (declared.kind() != eu.nordtal.s2.commands.Argument.Kind.WORD) {
            throw new IllegalArgumentException(declaration.name() + ": argument '" + argument
                    + "' is a " + declared.kind() + ", which carries its own suggestions - these"
                    + " would never be offered");
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
     * The same, for a subtree that is <b>not</b> admin-only.
     *
     * <h2>Why the plain {@link #extra} is gated and this one has to be asked for</h2>
     * Because the failure is asymmetric. {@code build()} deliberately puts no {@code requires} on a
     * root - a root is shared, and gating {@code /hg} would hide {@code /hg ready} from every
     * player - so an extra hung on one is ungated unless this adapter gates it. {@code /smp update}
     * was hung on that way and lost its admin check entirely: any player could have run
     * {@code /smp update restart}, which takes the whole network down after a minute's countdown.
     *
     * <p>So the default is closed and the exception is named. {@code /hg ready} is the only caller,
     * and it carries its own player check anyway.</p>
     */
    public PaperCommands extraOpen(final String root,
                                   final LiteralArgumentBuilder<CommandSourceStack> node) {
        openExtras.computeIfAbsent(Objects.requireNonNull(root, "root"), name -> new ArrayList<>())
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

        for (final String root : java.util.stream.Stream.concat(
                extras.keySet().stream(), openExtras.keySet().stream()).toList()) {
            if (!roots.containsKey(root)) {
                throw new IllegalStateException("an extra subtree was hung under /" + root
                        + ", which no command here uses as a root - it would never be registered");
            }
        }

        return roots.values().stream()
                .map(root -> {
                    final LiteralArgumentBuilder<CommandSourceStack> builder = materialise(root);
                    // Gated, like every node this adapter builds below a root. The root itself
                    // carries no requires, so an extra that is not gated here is not gated at all.
                    extras.getOrDefault(root.literal, List.of())
                            .forEach(extra -> builder.then(extra.requires(this::mayUse)));
                    openExtras.getOrDefault(root.literal, List.of()).forEach(builder::then);
                    // NO requires on the root, deliberately. Brigadier's requires gates a whole
                    // subtree, and a root is shared: /hg carries `ready`, which any player may run
                    // and which this adapter does not own. Gating the root would hide it. Every
                    // node this adapter creates below the root carries the check instead, so what a
                    // non-admin sees under /hg is exactly `ready`.
                    return builder.build();
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
            // The check goes on the child rather than on this node, because this node may be a root
            // that also carries somebody else's open command. Brigadier inherits requires down a
            // subtree, so one on each first-level node covers everything below it.
            builder.then(materialise(child).requires(this::mayUse));
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
            parent.executes(context -> dispatch(context, entry, new Parsed(Map.of(), Map.of())));
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
                node.executes(context -> dispatch(context, entry, read(context, arguments, index + 1)));
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
        parent.executes(context -> dispatch(context, entry, new Parsed(Map.of(), Map.of())));
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

        // Only what this person could actually run. The root carries no requires (see build()), so
        // a player who typed /hg reaches this - and a list of commands they would be refused is
        // worse than no list.
        if (!mayUse(context.getSource())) {
            below.removeIf(Declaration::adminOnly);
            if (below.isEmpty()) {
                user.reply("command.not-admin", Map.of(), Feedback.REFUSED);
                return Command.SINGLE_SUCCESS;
            }
        }

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
            // Both are typed as a Minecraft name here and differ in what they resolve TO: a
            // PLAYER becomes a UUID, an ACCOUNT becomes the Discord id behind it. In Discord they
            // differ the other way round, which is the whole reason they are two kinds.
            case PLAYER, ACCOUNT -> Commands.argument(argument.name(), StringArgumentType.word())
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

    /**
     * Everything Brigadier parsed, in the shapes {@link Values} hands out - and, separately, the
     * accounts that still have to be looked up.
     *
     * @param values   what is already known, on the main thread, without touching a database
     * @param accounts argument name to the UUID whose {@code account_link} row has to be read
     */
    private record Parsed(Map<String, Object> values, Map<String, UUID> accounts) {
    }

    /** Everything Brigadier parsed, in the shapes {@link Values} hands out. */
    private Parsed read(final CommandContext<CommandSourceStack> context,
                        final List<eu.nordtal.s2.commands.Argument> arguments,
                        final int count) {
        final Map<String, Object> values = new LinkedHashMap<>();
        final Map<String, UUID> accounts = new LinkedHashMap<>();
        for (int at = 0; at < count; at++) {
            final eu.nordtal.s2.commands.Argument argument = arguments.get(at);
            switch (argument.kind()) {
                case INTEGER -> values.put(argument.name(),
                        IntegerArgumentType.getInteger(context, argument.name()));
                case PLAYER, ACCOUNT -> {
                    final Player target =
                            Bukkit.getPlayerExact(StringArgumentType.getString(context, argument.name()));
                    if (target == null) {
                        // Left absent. run() turns that into "that player is not online" rather
                        // than letting Values throw about a declaration disagreement, which is what
                        // a missing required argument means everywhere else.
                        return new Parsed(values, accounts);
                    }
                    if (argument.kind() == eu.nordtal.s2.commands.Argument.Kind.PLAYER) {
                        values.put(argument.name(), target.getUniqueId());
                        continue;
                    }
                    // An ACCOUNT is a Discord id, and in game the only way to reach one is through
                    // account_link. Noted here and read somewhere else: this method runs inside a
                    // Brigadier handler, which is the main thread, and the rule this repository has
                    // held since 2026-09-01 is that a Paper plugin never queries from it. The read
                    // used to happen right here.
                    accounts.put(argument.name(), target.getUniqueId());
                }
                default -> values.put(argument.name(),
                        StringArgumentType.getString(context, argument.name()));
            }
        }
        return new Parsed(values, accounts);
    }

    /**
     * The step between Brigadier and {@link #run}: read the account links, if there are any.
     *
     * <h2>Off the main thread, then back onto it</h2>
     * {@code account_link} is a database read, and the one thing every command path here must not do
     * is wait on a database while the server is stopped behind it. So the lookup hops off, and the
     * command itself hops back - because everything after it is main-thread work: a chime, an
     * inventory, a world. A command with no {@code ACCOUNT} argument, which is fifteen of the
     * seventeen, never leaves the thread it was typed on.
     *
     * <p>An account that does not resolve is simply left out of the values, which is what makes
     * {@code command.account-unreachable} in {@link #run} the one answer for "not online" and "not
     * linked" alike.</p>
     */
    private int dispatch(final CommandContext<CommandSourceStack> context, final Entry entry,
                         final Parsed parsed) {
        final CommandSender sender = context.getSource().getSender();
        final String input = context.getInput();
        if (parsed.accounts().isEmpty()) {
            return run(sender, input, entry, parsed.values());
        }

        try {
            offThread(sender, input, entry, parsed);
        } catch (final IllegalPluginAccessException disabled) {
            // The plugin is going down between the keystroke and this line. The mirror of the guard
            // in back(): without it the exception leaves a Brigadier handler as a stack trace on
            // the console instead of a sentence to whoever typed.
            plugin.getLogger().fine("Dropped a command because the plugin is no longer enabled");
        }
        return Command.SINGLE_SUCCESS;
    }

    private void offThread(final CommandSender sender, final String input, final Entry entry,
                           final Parsed parsed) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final Map<String, Object> resolved = new LinkedHashMap<>(parsed.values());
            for (final Map.Entry<String, UUID> account : parsed.accounts().entrySet()) {
                final java.util.Optional<String> linked;
                try {
                    linked = discordIdOf.apply(account.getValue());
                } catch (final RuntimeException failure) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Could not read the account link for " + account.getValue(), failure);
                    back(() -> user(sender).reply("command.account-unreachable", Map.of(),
                            Feedback.REFUSED));
                    return;
                }
                if (linked.isEmpty()) {
                    break;
                }
                resolved.put(account.getKey(), linked.get());
            }
            back(() -> run(sender, input, entry, resolved));
        });
    }

    /** Back onto the server thread, or nowhere at all if the plugin went away while we were off it. */
    private void back(final Runnable work) {
        try {
            Bukkit.getScheduler().runTask(plugin, work);
        } catch (final IllegalPluginAccessException disabled) {
            plugin.getLogger().fine("Dropped a command answer because the plugin is no longer"
                    + " enabled");
        }
    }

    private int run(final CommandSender sender, final String input, final Entry entry,
                    final Map<String, Object> values) {
        final NordtalUser user = user(sender);

        // A command the console may not run. Declared per command as a Surface, so the rule is
        // visible next to the command rather than repeated in each adapter: /phase is refused here
        // because it takes a decision about the season and the audit row records who took it, and a
        // console has no identity to record. Rejected for the console on 2026-08-31 and, until
        // 2026-09-05, enforced by each adapter separately - which is how one of them came to enforce
        // it differently.
        if (user.origin() == NordtalUser.Origin.CONSOLE
                && !entry.declaration().surfaces().contains(eu.nordtal.s2.commands.Surface.CONSOLE)) {
            user.reply("command.not-from-console", Map.of(), Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }

        // A player argument that resolved to nobody. Answered here rather than by the command,
        // because "that name is not on this server" is a property of the surface the name was typed
        // on: in Discord the same argument is a member picked from a list and cannot miss.
        for (final eu.nordtal.s2.commands.Argument argument : entry.declaration().arguments()) {
            if (!argument.required() || values.containsKey(argument.name())) {
                continue;
            }
            if (argument.kind() == eu.nordtal.s2.commands.Argument.Kind.PLAYER) {
                user.reply("command.player-offline", Map.of(), Feedback.REFUSED);
                return Command.SINGLE_SUCCESS;
            }
            if (argument.kind() == eu.nordtal.s2.commands.Argument.Kind.ACCOUNT) {
                // Either not online, or online and not linked. Both mean "there is no Discord
                // account this name reaches", which is one answer from where the admin is standing.
                user.reply("command.account-unreachable", Map.of(), Feedback.REFUSED);
                return Command.SINGLE_SUCCESS;
            }
        }

        // Before the confirmation, deliberately. Without this, /phase set NOT_A_PHASE answers
        // "this cannot be undone, type it again", takes the retype, and only then says the phase
        // does not exist.
        final var problem = entry.problem().apply(new Values(entry.declaration(), values));
        if (problem.isPresent()) {
            user.reply(problem.get().getKey(), problem.get().getValue(), Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }

        if (entry.declaration().irreversible() && !confirmed(user, input)) {
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
            // The supplier, not the value: this runs on Brigadier's thread for every invocation
            // and for the help output, and a plugin whose only source is account_link would make
            // that a query. Outbox#send is the one caller that asks, on its own scheduler.
            return PaperUser.of(plugin, player, localeOf.apply(player.getUniqueId()), true,
                    () -> discordIdOf.apply(player.getUniqueId()), messages, chime);
        }
        return PaperUser.console(plugin, sender, messages);
    }
}
