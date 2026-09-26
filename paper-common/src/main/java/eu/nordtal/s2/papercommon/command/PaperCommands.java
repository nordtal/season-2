package eu.nordtal.s2.papercommon.command;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

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
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.ToneColours;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Declaration} turned into a real Brigadier tree, once, for all three Paper plugins.
 *
 * So that three hand-built trees cannot answer the same question differently.
 *
 * Local and remote look identical to whoever typed it: a command whose
 * {@link Declaration#target()} is this process runs here, anything else becomes a
 * {@code command_request} row and the answer comes back into the same chat.
 *
 * Commands targeting {@link Target#PROXY} are deliberately <b>not</b> registered here. Velocity
 * intercepts a command it knows before the packet reaches a backend, so a copy here would be
 * shadowed by it - dead code that looks live.
 *
 * {@link Declaration#irreversible()} is honoured by retyping the whole command line inside
 * {@link Confirmations#WINDOW}. For a command that travels, that happens <em>before</em> the row is
 * written: confirming on the far side would put a pending confirmation in a process the asker
 * cannot see.
 */
public final class PaperCommands {

    /** One registered command: what it is, and what to do when somebody runs it. */
    private record Entry(
            Declaration declaration,
            java.util.function.BiConsumer<NordtalUser, Values> run,
            java.util.function.Function<Values, java.util.Optional<MessageRef>> problem) {}

    private final Plugin plugin;
    private final Messages messages;
    private final Target here;
    private final @Nullable Outbox outbox;
    private final Function<UUID, java.util.Locale> localeOf;
    private final Predicate<UUID> isAdmin;
    private final Function<UUID, Optional<String>> discordIdOf;
    private final PaperUser.Chime chime;
    private final java.util.function.Supplier<ToneColours> colours;
    private final Confirmations confirmations = new Confirmations();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, List<LiteralArgumentBuilder<CommandSourceStack>>> extras = new LinkedHashMap<>();
    private final Map<String, List<LiteralArgumentBuilder<CommandSourceStack>>> openExtras = new LinkedHashMap<>();
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
     * @param colours     the tone palette this plugin is configured with right now - a supplier, so
     *                    the {@link PaperUser} built for the next command typed sees a reload that
     *                    happened after this tree was built
     */
    public PaperCommands(
            final Plugin plugin,
            final Messages messages,
            final Target here,
            final @Nullable Outbox outbox,
            final Function<UUID, java.util.Locale> localeOf,
            final Predicate<UUID> isAdmin,
            final Function<UUID, Optional<String>> discordIdOf,
            final PaperUser.Chime chime,
            final java.util.function.Supplier<ToneColours> colours) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.here = Objects.requireNonNull(here, "here");
        this.outbox = outbox;
        this.localeOf = Objects.requireNonNull(localeOf, "localeOf");
        this.isAdmin = Objects.requireNonNull(isAdmin, "isAdmin");
        this.discordIdOf = Objects.requireNonNull(discordIdOf, "discordIdOf");
        this.chime = Objects.requireNonNull(chime, "chime");
        this.colours = Objects.requireNonNull(colours, "colours");
    }

    /** A command this process runs itself. */
    public <E extends CommandEffects> PaperCommands local(final NordtalCommand<E> command, final E effects) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(effects, "effects");
        final Declaration declaration = command.declaration();
        if (declaration.isRemoteOn(here)) {
            throw new IllegalArgumentException(
                    declaration.name() + " is run by " + declaration.target() + ", not by " + here);
        }
        entries.add(new Entry(declaration, (user, values) -> command.run(user, values, effects), command::check));
        return this;
    }

    /**
     * A command another process runs, reachable from here.
     *
     * Silently skipped for {@link Target#PROXY} and for this process's own target: the first is
     * already served everywhere by Velocity, and the second would be a round trip to ourselves.
     * Skipping rather than throwing is what lets a caller hand over the whole catalogue.
     */
    public PaperCommands remote(final Declaration declaration) {
        Objects.requireNonNull(declaration, "declaration");
        // isRemoteOn rather than `target != here`: only it knows Target.LOCAL is never remote anywhere.
        if (!declaration.isRemoteOn(here) || declaration.target() == Target.PROXY) {
            return this;
        }
        // GAME or CONSOLE, not GAME alone: dropping GAME must not silently drop console reach elsewhere too.
        if (!declaration.surfaces().contains(eu.nordtal.s2.commands.Surface.GAME)
                && !declaration.surfaces().contains(eu.nordtal.s2.commands.Surface.CONSOLE)) {
            return this;
        }
        if (outbox == null) {
            throw new IllegalStateException(
                    declaration.name() + " has to travel, and this adapter" + " was built without an outbox");
        }
        // problem() cannot be asked here: this process holds the declaration but not the command.
        entries.add(new Entry(
                declaration,
                (user, values) -> outbox.send(declaration, user, values),
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
     * The values come from the caller because they are not fixed - the declaration says
     * <em>that</em> the argument is a word and the plugin says <em>which</em> words.
     *
     * <b>Must not block and must not query.</b> Brigadier asks once per keystroke, for every
     * client with the command in its tree, so only an in-memory source belongs here.
     */
    public PaperCommands suggest(
            final Declaration declaration,
            final String argument,
            final java.util.function.Supplier<java.util.Collection<String>> values) {
        final eu.nordtal.s2.commands.Argument declared = declaration.arguments().stream()
                .filter(a -> a.name().equals(argument))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(declaration.name()
                        + " has no argument '" + argument
                        + "', so nothing would ever ask for these suggestions"));
        // node() applies these only in the WORD branch; every other kind brings its own suggestions.
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
     * For the commands that are not {@link NordtalCommand}s and should not become ones.
     * {@code /smp update} is the case it exists for: it already travels through
     * {@code update_request} to a container that is not a command target, and its answer is
     * steward-worker's own report, which must not be rendered twice.
     *
     * @param root the first path segment it belongs under, which must be one a command here uses
     */
    public PaperCommands extra(final String root, final LiteralArgumentBuilder<CommandSourceStack> node) {
        extras.computeIfAbsent(Objects.requireNonNull(root, "root"), name -> new ArrayList<>())
                .add(Objects.requireNonNull(node, "node"));
        return this;
    }

    /**
     * The same, for a subtree that is <b>not</b> admin-only.
     *
     * The default is closed because the failure is asymmetric: {@code build()} puts no
     * {@code requires} on a root - gating {@code /hg} would hide {@code /hg ready} from every
     * player - so an extra hung on one is ungated unless this adapter gates it.
     */
    public PaperCommands extraOpen(final String root, final LiteralArgumentBuilder<CommandSourceStack> node) {
        openExtras
                .computeIfAbsent(Objects.requireNonNull(root, "root"), name -> new ArrayList<>())
                .add(Objects.requireNonNull(node, "node"));
        return this;
    }

    /**
     * The trees, one per distinct first path segment.
     *
     * <b>Assembled bottom-up, and it has to be.</b> Brigadier's
     * {@code ArgumentBuilder.then(ArgumentBuilder)} builds its argument on the spot, so a tree grown
     * as the paths are walked loses everything added to a node after its parent took it - and the
     * command then parses as unknown with nothing saying why. The paths are therefore collected into
     * a plain tree of {@link Node} first and materialised depth-first.
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
                throw new IllegalStateException("two commands both claim /" + String.join(" ", path));
            }
            node.command = entry;
        }

        for (final String root : java.util.stream.Stream.concat(extras.keySet().stream(), openExtras.keySet().stream())
                .toList()) {
            if (!roots.containsKey(root)) {
                throw new IllegalStateException("an extra subtree was hung under /" + root
                        + ", which no command here uses as a root - it would never be registered");
            }
        }

        return roots.values().stream()
                .map(root -> {
                    final LiteralArgumentBuilder<CommandSourceStack> builder = materialise(root);
                    // Gated when everything under it is admin-only: a bare-root command needs this too.
                    final Predicate<CommandSourceStack> gate = gate(root);
                    if (gate != null && !openExtras.containsKey(root.literal)) {
                        builder.requires(gate);
                    }
                    // Gated here: the root itself carries no requires unless the line above added one.
                    extras.getOrDefault(root.literal, List.of())
                            .forEach(extra -> builder.then(extra.requires(this::mayUse)));
                    openExtras.getOrDefault(root.literal, List.of()).forEach(builder::then);
                    // No requires on a root carrying an open subtree: requires gates the whole shared subtree.
                    return builder.build();
                })
                .toList();
    }

    /** One literal of a command path, with whatever hangs off it. */
    private static final class Node {

        private final String literal;
        private final Map<String, Node> children = new LinkedHashMap<>();
        private @Nullable Entry command;

        private Node(final String literal) {
            this.literal = literal;
        }
    }

    /**
     * Whether everything runnable at or below this node is admin-only.
     *
     * One open command anywhere below opens the whole subtree's {@code requires}, and it stays the
     * only thing a non-admin can run because every admin-only node deeper down carries its own
     * check.
     */
    private static boolean adminOnly(final Node node) {
        if (node.command != null && !node.command.declaration().adminOnly()) {
            return false;
        }
        return node.children.values().stream().allMatch(PaperCommands::adminOnly);
    }

    /**
     * Whether nothing runnable at or below this node carries {@link eu.nordtal.s2.commands.Surface#GAME}.
     *
     * The same shape as {@link #adminOnly(Node)}, and asked for the same reason: one command below
     * that a player may type keeps the whole subtree in their tree, because {@code requires} is
     * inherited downwards.
     */
    private static boolean offGame(final Node node) {
        if (node.command != null
                && node.command.declaration().surfaces().contains(eu.nordtal.s2.commands.Surface.GAME)) {
            return false;
        }
        return node.children.values().stream().allMatch(PaperCommands::offGame);
    }

    /**
     * What has to be true of a source for this node to exist, or {@code null} when it is open.
     *
     * A command taken off {@link eu.nordtal.s2.commands.Surface#GAME} must <b>disappear</b>
     * from the game rather than answer with "that command moved". There is one Brigadier tree per
     * server and not one per audience, so the only place that question can be answered is here: a
     * node whose requirement no {@link Player} passes is left out of the tree sent to every
     * client, is refused by the server's own parse, and reads to the person typing as Brigadier's
     * "Unknown command".
     *
     * The console is never gated out: {@link #mayUse(CommandSender, Predicate)} already lets
     * only a {@link ConsoleCommandSender} through for a non-player, so the off-game gate is that
     * one plus "not a player".
     */
    private @Nullable Predicate<CommandSourceStack> gate(final Node node) {
        if (offGame(node)) {
            return source -> !(source.getSender() instanceof Player) && mayUse(source);
        }
        return adminOnly(node) ? this::mayUse : null;
    }

    private LiteralArgumentBuilder<CommandSourceStack> materialise(final Node node) {
        final LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(node.literal);
        for (final Node child : node.children.values()) {
            // On the child, not this node: this node may be a root also carrying an open command.
            final LiteralArgumentBuilder<CommandSourceStack> sub = materialise(child);
            final Predicate<CommandSourceStack> gate = gate(child);
            builder.then(gate == null ? sub : sub.requires(gate));
        }

        final boolean runnableHere = node.command != null && arguments(builder, node.command);
        if (!runnableHere) {
            // Brigadier's own red caret says nothing about what was wanted; answer with what runs underneath.
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
    private boolean arguments(final LiteralArgumentBuilder<CommandSourceStack> parent, final Entry entry) {
        final List<eu.nordtal.s2.commands.Argument> arguments =
                entry.declaration().arguments();
        if (arguments.isEmpty()) {
            parent.executes(context -> dispatch(context, entry, new Parsed(Map.of(), Map.of())));
            return true;
        }

        // Back to front, like build(): a node has to be complete before it is handed to its parent.
        RequiredArgumentBuilder<CommandSourceStack, ?> child = null;
        for (int at = arguments.size() - 1; at >= 0; at--) {
            final RequiredArgumentBuilder<CommandSourceStack, ?> node = node(entry.declaration(), arguments.get(at));
            final int index = at;
            // Runnable here only when nothing required is still missing; otherwise it answers with usage.
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
    private static boolean satisfied(final List<eu.nordtal.s2.commands.Argument> arguments, final int count) {
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
     * Replaces Brigadier's own message, which is an answer about the parser rather than about the
     * command.
     *
     * One line per command underneath, derived from the declaration so it cannot go stale, and
     * sorted, because a list ordered by registration reads as random.
     */
    private int help(final CommandContext<CommandSourceStack> context, final Node node) {
        // A root with a declared default runs it, admin flag included: this path bypasses the child's requires.
        final java.util.Optional<Declaration> preset =
                eu.nordtal.s2.commands.Catalogue.rootDefault(node.literal, mayUse(context.getSource()));
        if (preset.isPresent()) {
            final Node child = node.children.get(preset.get().path().get(1));
            if (child != null
                    && child.command != null
                    && child.command.declaration().equals(preset.get())) {
                return dispatch(context, child.command, new Parsed(Map.of(), Map.of()));
            }
        }

        final NordtalUser user = user(context.getSource().getSender());
        final List<Declaration> below = new ArrayList<>();
        collect(node, below);

        // Only what this person could actually run: the root carries no requires, so a non-admin reaches this too.
        if (!mayUse(context.getSource())) {
            below.removeIf(Declaration::adminOnly);
            if (below.isEmpty()) {
                user.reply(MESSAGES.command().notAdmin(), Feedback.REFUSED, Tone.BAD);
                return Command.SINGLE_SUCCESS;
            }
        }
        // The same rule for the surface: a command outside this player's tree must not be listed either.
        if (user.origin() == NordtalUser.Origin.GAME) {
            below.removeIf(declaration -> !declaration.surfaces().contains(eu.nordtal.s2.commands.Surface.GAME));
            if (below.isEmpty()) {
                user.reply(MESSAGES.command().unknown(), Feedback.REFUSED, Tone.BAD);
                return Command.SINGLE_SUCCESS;
            }
        }

        if (below.isEmpty()) {
            // Only reachable for a root whose every command was skipped by remote().
            user.reply(MESSAGES.command().help().nothing(), Feedback.REFUSED, Tone.WARN);
            return Command.SINGLE_SUCCESS;
        }
        if (below.size() == 1) {
            return usage(context, below.getFirst());
        }

        // REFUSED once, on the header line only, not once per line below.
        user.reply(MESSAGES.command().help().header("/" + node.literal), Feedback.REFUSED, Tone.NEUTRAL);
        below.stream()
                .sorted(java.util.Comparator.comparing(Declaration::name))
                .forEach(declaration -> user.reply(
                        MESSAGES.command().help().line(declaration.usage(), user.phrase(declaration.describe())),
                        Tone.MUTED));
        return Command.SINGLE_SUCCESS;
    }

    /** The usage of one command, plus the sentence saying what it is for. */
    private int usage(final CommandContext<CommandSourceStack> context, final Declaration declaration) {
        final NordtalUser user = user(context.getSource().getSender());
        user.reply(MESSAGES.command().help().usage(declaration.usage()), Feedback.REFUSED, Tone.NEUTRAL);
        user.reply(MESSAGES.command().help().what(user.phrase(declaration.describe())), Tone.MUTED);
        return Command.SINGLE_SUCCESS;
    }

    private static void collect(final Node node, final List<Declaration> into) {
        if (node.command != null) {
            into.add(node.command.declaration());
        }
        node.children.values().forEach(child -> collect(child, into));
    }

    private RequiredArgumentBuilder<CommandSourceStack, ?> node(
            final Declaration declaration, final eu.nordtal.s2.commands.Argument argument) {
        final java.util.function.Supplier<java.util.Collection<String>> offered =
                suggestions.get(declaration.name() + " " + argument.name());
        return switch (argument.kind()) {
            case WORD, REFERENCE -> {
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
            case GREEDY_STRING -> Commands.argument(argument.name(), StringArgumentType.greedyString());
            case INTEGER ->
                Commands.argument(argument.name(), IntegerArgumentType.integer(argument.min(), argument.max()));
            // Both are typed as a name; a PLAYER resolves to a UUID, an ACCOUNT to the Discord id behind it.
            case PLAYER, ACCOUNT ->
                Commands.argument(argument.name(), StringArgumentType.word()).suggests((context, builder) -> {
                    for (final Player online : Bukkit.getOnlinePlayers()) {
                        builder.suggest(online.getName());
                    }
                    return builder.buildFuture();
                });
            case CHOICE ->
                Commands.argument(argument.name(), StringArgumentType.word()).suggests((context, builder) -> {
                    argument.choices().forEach(builder::suggest);
                    return builder.buildFuture();
                });
        };
    }

    /**
     * Everything Brigadier parsed, in the shapes {@link Values} hands out.
     *
     * Separately carries the accounts that still have to be looked up.
     *
     * @param values   what is already known, on the main thread, without touching a database
     * @param accounts argument name to the UUID whose {@code account_link} row has to be read
     */
    private record Parsed(Map<String, Object> values, Map<String, UUID> accounts) {}

    /** Everything Brigadier parsed, in the shapes {@link Values} hands out. */
    private Parsed read(
            final CommandContext<CommandSourceStack> context,
            final List<eu.nordtal.s2.commands.Argument> arguments,
            final int count) {
        final Map<String, Object> values = new LinkedHashMap<>();
        final Map<String, UUID> accounts = new LinkedHashMap<>();
        for (int at = 0; at < count; at++) {
            final eu.nordtal.s2.commands.Argument argument = arguments.get(at);
            switch (argument.kind()) {
                case INTEGER -> values.put(argument.name(), IntegerArgumentType.getInteger(context, argument.name()));
                case PLAYER, ACCOUNT -> {
                    final Player target = Bukkit.getPlayerExact(StringArgumentType.getString(context, argument.name()));
                    if (target == null) {
                        // Left absent: run() turns this into "player not online" rather than letting Values throw.
                        return new Parsed(values, accounts);
                    }
                    if (argument.kind() == eu.nordtal.s2.commands.Argument.Kind.PLAYER) {
                        values.put(argument.name(), target.getUniqueId());
                        continue;
                    }
                    // An ACCOUNT resolves later: this runs on the main thread, which never queries a database.
                    accounts.put(argument.name(), target.getUniqueId());
                }
                default -> values.put(argument.name(), StringArgumentType.getString(context, argument.name()));
            }
        }
        return new Parsed(values, accounts);
    }

    /**
     * The step between Brigadier and {@link #run}: read the account links, if there are any.
     *
     * {@code account_link} is a database read and must not happen on the main thread, so the
     * lookup hops off and the command hops back - everything after it is main-thread work. A command
     * with no {@code ACCOUNT} argument never leaves the thread it was typed on.
     *
     * An account that does not resolve is left out of the values, which is what makes
     * {@code command.account-unreachable} the one answer for "not online" and "not linked" alike.
     */
    private int dispatch(final CommandContext<CommandSourceStack> context, final Entry entry, final Parsed parsed) {
        final CommandSender sender = context.getSource().getSender();
        final String input = context.getInput();
        if (parsed.accounts().isEmpty()) {
            return run(sender, input, entry, parsed.values());
        }

        try {
            offThread(sender, input, entry, parsed);
        } catch (final IllegalPluginAccessException disabled) {
            // Without the guard, the plugin going down here leaves a stack trace on the console instead.
            plugin.getLogger().fine("Dropped a command because the plugin is no longer enabled");
        }
        return Command.SINGLE_SUCCESS;
    }

    private void offThread(final CommandSender sender, final String input, final Entry entry, final Parsed parsed) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final Map<String, Object> resolved = new LinkedHashMap<>(parsed.values());
            for (final Map.Entry<String, UUID> account : parsed.accounts().entrySet()) {
                final java.util.Optional<String> linked;
                try {
                    linked = discordIdOf.apply(account.getValue());
                } catch (final RuntimeException failure) {
                    plugin.getLogger()
                            .log(
                                    java.util.logging.Level.WARNING,
                                    "Could not read the account link for " + account.getValue(),
                                    failure);
                    back(() -> user(sender).reply(MESSAGES.command().accountUnreachable(), Feedback.REFUSED, Tone.BAD));
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
            plugin.getLogger().fine("Dropped a command answer because the plugin is no longer" + " enabled");
        }
    }

    private int run(
            final CommandSender sender, final String input, final Entry entry, final Map<String, Object> values) {
        final NordtalUser user = user(sender);

        // Declared per command as a Surface, so the rule lives next to the command, not in each adapter.
        if (user.origin() == NordtalUser.Origin.CONSOLE
                && !entry.declaration().surfaces().contains(eu.nordtal.s2.commands.Surface.CONSOLE)) {
            user.reply(MESSAGES.command().notFromConsole(), Feedback.REFUSED, Tone.BAD);
            return Command.SINGLE_SUCCESS;
        }

        // The lock behind gate()'s requires: answers "Unknown command", not a hint the command exists elsewhere.
        if (user.origin() == NordtalUser.Origin.GAME
                && !entry.declaration().surfaces().contains(eu.nordtal.s2.commands.Surface.GAME)) {
            user.reply(MESSAGES.command().unknown(), Feedback.REFUSED, Tone.BAD);
            return Command.SINGLE_SUCCESS;
        }

        // The tree's requires is the gate; this is the lock behind it, so the check survives any shape of tree.
        if (entry.declaration().adminOnly() && !mayUse(sender, isAdmin)) {
            user.reply(MESSAGES.command().notAdmin(), Feedback.REFUSED, Tone.BAD);
            return Command.SINGLE_SUCCESS;
        }

        // Answered here rather than by the command: "not on this server" is a property of the surface.
        for (final eu.nordtal.s2.commands.Argument argument :
                entry.declaration().arguments()) {
            if (!argument.required() || values.containsKey(argument.name())) {
                continue;
            }
            if (argument.kind() == eu.nordtal.s2.commands.Argument.Kind.PLAYER) {
                user.reply(MESSAGES.command().playerOffline(), Feedback.REFUSED, Tone.WARN);
                return Command.SINGLE_SUCCESS;
            }
            if (argument.kind() == eu.nordtal.s2.commands.Argument.Kind.ACCOUNT) {
                // Either not online or not linked: one answer from where the admin stands.
                user.reply(MESSAGES.command().accountUnreachable(), Feedback.REFUSED, Tone.BAD);
                return Command.SINGLE_SUCCESS;
            }
        }

        // Before the confirmation, deliberately: an invalid argument must not be confirmed first.
        final var problem = entry.problem().apply(new Values(entry.declaration(), values));
        if (problem.isPresent()) {
            user.reply(problem.get(), Feedback.REFUSED, Tone.BAD);
            return Command.SINGLE_SUCCESS;
        }

        if (entry.declaration().irreversible() && !confirmed(user, input)) {
            return Command.SINGLE_SUCCESS;
        }
        entry.run().accept(user, new Values(entry.declaration(), values));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * "Type it again", keyed on the exact line including its arguments.
     *
     * A pending confirmation cannot be spent on a different argument typed seconds later.
     */
    private boolean confirmed(final NordtalUser user, final String input) {
        final String what = input.startsWith("/") ? input : "/" + input;
        if (confirmations.confirm(user, what)) {
            return true;
        }
        user.reply(
                MESSAGES.command().confirm().retype(what, String.valueOf(Confirmations.WINDOW.toSeconds())),
                Feedback.REFUSED,
                Tone.WARN);
        return false;
    }

    /**
     * Whether the sender may use any of this.
     *
     * "Not a player" is not the same as "the console": a command block, a
     * {@code ProxiedCommandSender} and a datapack function are none of either, so the console is
     * asked for <em>by type</em>.
     */
    private boolean mayUse(final CommandSourceStack source) {
        return mayUse(source.getSender(), isAdmin);
    }

    /**
     * The decision on its own: a player who is flagged admin, or the console. Nothing else.
     *
     * Public and static so it can be asserted without a server, which is the only part of a command
     * tree that ever can be.
     */
    public static boolean mayUse(final CommandSender sender, final Predicate<UUID> isAdmin) {
        if (sender instanceof Player player) {
            return isAdmin.test(player.getUniqueId());
        }
        return sender instanceof ConsoleCommandSender;
    }

    private NordtalUser user(final CommandSender sender) {
        if (sender instanceof Player player) {
            // admin=true: already gated by mayUse. A supplier: an eager read would query the main thread.
            return PaperUser.of(
                    plugin,
                    player,
                    localeOf.apply(player.getUniqueId()),
                    true,
                    () -> discordIdOf.apply(player.getUniqueId()),
                    messages,
                    chime,
                    colours);
        }
        return PaperUser.console(plugin, sender, messages, colours);
    }
}
