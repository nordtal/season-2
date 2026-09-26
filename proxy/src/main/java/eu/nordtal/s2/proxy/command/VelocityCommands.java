package eu.nordtal.s2.proxy.command;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.Confirmations;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * {@code :paper-common}'s {@code PaperCommands}, for Velocity.
 *
 * A second class rather than a shared one because the two platforms resolve different Brigadier
 * artefacts - {@code com.mojang:brigadier} on Paper, {@code com.velocitypowered:velocity-brigadier}
 * here - so no module can be compiled against both, and one compiled against neither cannot name
 * {@code CommandSource}. Only the tree building is duplicated; the declarations, decisions, messages
 * and confirmation are shared. The two are kept in the same shape - same method names, same order -
 * so a rule added to one is findable in the other.
 *
 * The proxy does not register the backends' commands. Velocity answers a command it knows before
 * the packet reaches a backend, so registering {@code /smp} here would shadow the SMP's own and turn
 * a local command into a round trip through a request row.
 *
 * This class has {@link #local} and no counterpart to {@code PaperCommands}' {@code remote}: there
 * is no second registration path here that could filter a declaration by its surfaces, so what
 * {@code PaperCommands#remote} does - registering a travelling command on {@code GAME} or
 * {@code CONSOLE} rather than on {@code GAME} alone - has nothing to mirror on this side. Everything
 * handed to {@code local} is built into the tree whatever its surfaces say.
 *
 * The surface is decided while the tree is built, by {@link #gate(Node)}: a node whose every
 * command lost {@link Surface#GAME} is gated against every {@link Player}, so it is not in the tree
 * a client receives and the person typing reads "Unknown command". {@link #run} keeps the same
 * check as the lock behind that gate, but it is no longer the single place that answers the
 * question.
 */
public final class VelocityCommands {

    private record Entry(
            Declaration declaration,
            BiConsumer<NordtalUser, Values> run,
            java.util.function.Function<Values, java.util.Optional<MessageRef>> problem) {}

    private static final class Node {

        private final String literal;
        private final Map<String, Node> children = new LinkedHashMap<>();
        private @Nullable Entry command;

        private Node(final String literal) {
            this.literal = literal;
        }
    }

    private final ProxyServer proxy;
    private final LoginRoster roster;
    private final Messages messages;
    private final Supplier<ToneColours> colours;
    private final Confirmations confirmations = new Confirmations();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Supplier<Collection<String>>> suggestions = new LinkedHashMap<>();

    public VelocityCommands(
            final ProxyServer proxy,
            final LoginRoster roster,
            final Messages messages,
            final Supplier<ToneColours> colours) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.colours = Objects.requireNonNull(colours, "colours");
    }

    /** A command this process runs itself. */
    public <E extends CommandEffects> VelocityCommands local(final NordtalCommand<E> command, final E effects) {
        entries.add(
                new Entry(command.declaration(), (user, values) -> command.run(user, values, effects), command::check));
        return this;
    }

    /** What to offer for one argument. Must be in memory - see {@code PaperCommands#suggest}. */
    public VelocityCommands suggest(
            final Declaration declaration, final String argument, final Supplier<Collection<String>> values) {
        if (declaration.arguments().stream().noneMatch(a -> a.name().equals(argument))) {
            throw new IllegalArgumentException(declaration.name() + " has no argument '" + argument
                    + "', so nothing would ever ask for these suggestions");
        }
        suggestions.put(declaration.name() + " " + argument, values);
        return this;
    }

    /** One {@link BrigadierCommand} per distinct first path segment, ready to register. */
    public List<BrigadierCommand> build() {
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
        // Bottom-up: Brigadier's ArgumentBuilder.then(ArgumentBuilder) builds its argument on the spot.
        return roots.values().stream()
                .map(root -> {
                    final LiteralArgumentBuilder<CommandSource> builder = materialise(root);
                    // A root whose every command is admin-only is gated itself, or a runnable bare root stays open.
                    final Predicate<CommandSource> gate = gate(root);
                    if (gate != null) {
                        builder.requires(gate);
                    }
                    return new BrigadierCommand(builder);
                })
                .toList();
    }

    /** Whether everything runnable at or below this node is admin-only. */
    private static boolean adminOnly(final Node node) {
        if (node.command != null && !node.command.declaration().adminOnly()) {
            return false;
        }
        return node.children.values().stream().allMatch(VelocityCommands::adminOnly);
    }

    /** Whether nothing runnable at or below this node carries {@link Surface#GAME}. */
    private static boolean offGame(final Node node) {
        if (node.command != null && node.command.declaration().surfaces().contains(Surface.GAME)) {
            return false;
        }
        return node.children.values().stream().allMatch(VelocityCommands::offGame);
    }

    /**
     * What has to be true of a source for this node to exist at all, or {@code null} when open to everyone.
     *
     * {@code PaperCommands#gate} is the same method, and the two are kept in the same shape on purpose.
     *
     * A node whose every command lost {@link Surface#GAME} is gated against every {@link Player},
     * so it is not in the tree the proxy sends a client and the person typing reads Brigadier's own
     * "Unknown command" rather than a sentence naming the command as off-limits.
     *
     * Not a player means the console here, which {@link #mayUse} lets through unconditionally - so
     * the console keeps every one of these, which is the one surface that may never be lost.
     */
    private @Nullable Predicate<CommandSource> gate(final Node node) {
        if (offGame(node)) {
            return source -> !(source instanceof Player) && mayUse(source);
        }
        return adminOnly(node) ? this::mayUse : null;
    }

    private LiteralArgumentBuilder<CommandSource> materialise(final Node node) {
        final LiteralArgumentBuilder<CommandSource> builder = BrigadierCommand.literalArgumentBuilder(node.literal);
        for (final Node child : node.children.values()) {
            // Only when everything below it is admin-only, or when nothing below it may be typed in game at all.
            final LiteralArgumentBuilder<CommandSource> sub = materialise(child);
            final Predicate<CommandSource> gate = gate(child);
            builder.then(gate == null ? sub : sub.requires(gate));
        }
        final boolean runnableHere = node.command != null && arguments(builder, node.command);
        if (!runnableHere) {
            builder.executes(context -> help(context, node));
        }
        return builder;
    }

    private boolean arguments(final LiteralArgumentBuilder<CommandSource> parent, final Entry entry) {
        final List<Argument> arguments = entry.declaration().arguments();
        if (arguments.isEmpty()) {
            parent.executes(context -> run(context, entry, Map.of()));
            return true;
        }

        RequiredArgumentBuilder<CommandSource, ?> child = null;
        for (int at = arguments.size() - 1; at >= 0; at--) {
            final RequiredArgumentBuilder<CommandSource, ?> node = node(entry.declaration(), arguments.get(at));
            final int index = at;
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

    private static boolean satisfied(final List<Argument> arguments, final int count) {
        for (int at = count; at < arguments.size(); at++) {
            if (arguments.get(at).required()) {
                return false;
            }
        }
        return true;
    }

    private RequiredArgumentBuilder<CommandSource, ?> node(final Declaration declaration, final Argument argument) {
        final Supplier<Collection<String>> offered = suggestions.get(declaration.name() + " " + argument.name());
        return switch (argument.kind()) {
            case WORD, REFERENCE -> {
                final RequiredArgumentBuilder<CommandSource, ?> word =
                        BrigadierCommand.requiredArgumentBuilder(argument.name(), StringArgumentType.word());
                if (offered != null) {
                    word.suggests((context, builder) -> {
                        offered.get().forEach(builder::suggest);
                        return builder.buildFuture();
                    });
                }
                yield word;
            }
            case GREEDY_STRING -> {
                final RequiredArgumentBuilder<CommandSource, ?> greedy =
                        BrigadierCommand.requiredArgumentBuilder(argument.name(), StringArgumentType.greedyString());
                if (offered != null) {
                    greedy.suggests((context, builder) -> {
                        offered.get().forEach(builder::suggest);
                        return builder.buildFuture();
                    });
                }
                yield greedy;
            }
            case INTEGER ->
                BrigadierCommand.requiredArgumentBuilder(
                        argument.name(), IntegerArgumentType.integer(argument.min(), argument.max()));
            case PLAYER, ACCOUNT ->
                BrigadierCommand.requiredArgumentBuilder(argument.name(), StringArgumentType.word())
                        .suggests((context, builder) -> {
                            proxy.getAllPlayers().forEach(online -> builder.suggest(online.getUsername()));
                            return builder.buildFuture();
                        });
            case CHOICE ->
                BrigadierCommand.requiredArgumentBuilder(argument.name(), StringArgumentType.word())
                        .suggests((context, builder) -> {
                            argument.choices().forEach(builder::suggest);
                            return builder.buildFuture();
                        });
        };
    }

    private Map<String, Object> read(
            final CommandContext<CommandSource> context, final List<Argument> arguments, final int count) {
        final Map<String, Object> values = new LinkedHashMap<>();
        for (int at = 0; at < count; at++) {
            final Argument argument = arguments.get(at);
            switch (argument.kind()) {
                case INTEGER -> values.put(argument.name(), IntegerArgumentType.getInteger(context, argument.name()));
                case PLAYER, ACCOUNT -> {
                    final var target = proxy.getPlayer(StringArgumentType.getString(context, argument.name()));
                    if (target.isEmpty()) {
                        return values;
                    }
                    if (argument.kind() == Argument.Kind.PLAYER) {
                        values.put(argument.name(), target.get().getUniqueId());
                        continue;
                    }
                    // A map lookup: the roster filled the Discord id at login, so an empty case is a symptom.
                    final var linked = roster.of(target.get().getUniqueId())
                            .map(eu.nordtal.s2.proxy.gate.LoginRoster.Session::discordId);
                    if (linked.isEmpty()) {
                        return values;
                    }
                    values.put(argument.name(), linked.get());
                }
                default -> values.put(argument.name(), StringArgumentType.getString(context, argument.name()));
            }
        }
        return values;
    }

    private int run(final CommandContext<CommandSource> context, final Entry entry, final Map<String, Object> values) {
        final NordtalUser user = user(context.getSource());

        if (user.origin() == NordtalUser.Origin.CONSOLE
                && !entry.declaration().surfaces().contains(Surface.CONSOLE)) {
            // /phase is the one this exists for: it records who took the decision, and the console is nobody.
            user.reply(MESSAGES.command().notFromConsole(), Feedback.REFUSED, Tone.BAD);
            return Command.SINGLE_SUCCESS;
        }

        // The lock behind gate()'s tree filtering: a player reaching this line reads the same "unknown command".
        if (user.origin() == NordtalUser.Origin.GAME
                && !entry.declaration().surfaces().contains(Surface.GAME)) {
            user.reply(MESSAGES.command().unknown(), Feedback.REFUSED, Tone.BAD);
            return Command.SINGLE_SUCCESS;
        }

        // A root-level command has no node above it to carry a requires, so this cannot be skipped by the tree.
        if (entry.declaration().adminOnly() && !mayUse(context.getSource())) {
            user.reply(MESSAGES.command().notAdmin(), Feedback.REFUSED, Tone.BAD);
            return Command.SINGLE_SUCCESS;
        }

        for (final Argument argument : entry.declaration().arguments()) {
            if (!argument.required() || values.containsKey(argument.name())) {
                continue;
            }
            if (argument.kind() == Argument.Kind.PLAYER) {
                user.reply(MESSAGES.command().playerOffline(), Feedback.REFUSED, Tone.WARN);
                return Command.SINGLE_SUCCESS;
            }
            if (argument.kind() == Argument.Kind.ACCOUNT) {
                user.reply(MESSAGES.command().accountUnreachable(), Feedback.REFUSED, Tone.BAD);
                return Command.SINGLE_SUCCESS;
            }
        }

        // Before the confirmation, or /phase set NOT_A_PHASE would demand a retype before saying it does not exist.
        final var problem = entry.problem().apply(new Values(entry.declaration(), values));
        if (problem.isPresent()) {
            user.reply(problem.get(), Feedback.REFUSED, Tone.BAD);
            return Command.SINGLE_SUCCESS;
        }

        if (entry.declaration().irreversible() && !confirmed(user, context.getInput())) {
            return Command.SINGLE_SUCCESS;
        }
        entry.run().accept(user, new Values(entry.declaration(), values));
        return Command.SINGLE_SUCCESS;
    }

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

    private int help(final CommandContext<CommandSource> context, final Node node) {
        // A root with a declared default runs it instead of listing itself: /phase is /phase show.
        final java.util.Optional<Declaration> preset =
                eu.nordtal.s2.commands.Catalogue.rootDefault(node.literal, mayUse(context.getSource()));
        if (preset.isPresent()) {
            final Node child = node.children.get(preset.get().path().get(1));
            if (child != null
                    && child.command != null
                    && child.command.declaration().equals(preset.get())) {
                return run(context, child.command, Map.of());
            }
        }

        final NordtalUser user = user(context.getSource());
        final List<Declaration> below = new ArrayList<>();
        collect(node, below);

        if (!mayUse(context.getSource())) {
            below.removeIf(Declaration::adminOnly);
            if (below.isEmpty()) {
                user.reply(MESSAGES.command().notAdmin(), Feedback.REFUSED, Tone.BAD);
                return Command.SINGLE_SUCCESS;
            }
        }
        // Listing a command that is not in this player's tree would name something they are then told does not exist.
        if (user.origin() == NordtalUser.Origin.GAME) {
            below.removeIf(declaration -> !declaration.surfaces().contains(Surface.GAME));
            if (below.isEmpty()) {
                user.reply(MESSAGES.command().unknown(), Feedback.REFUSED, Tone.BAD);
                return Command.SINGLE_SUCCESS;
            }
        }
        if (below.isEmpty()) {
            user.reply(MESSAGES.command().help().nothing(), Feedback.REFUSED, Tone.WARN);
            return Command.SINGLE_SUCCESS;
        }
        if (below.size() == 1) {
            return usage(context, below.getFirst());
        }

        user.reply(MESSAGES.command().help().header("/" + node.literal), Tone.NEUTRAL);
        below.stream()
                .sorted(Comparator.comparing(Declaration::name))
                .forEach(declaration -> user.reply(
                        MESSAGES.command().help().line(declaration.usage(), user.phrase(declaration.describe())),
                        Tone.MUTED));
        return Command.SINGLE_SUCCESS;
    }

    private int usage(final CommandContext<CommandSource> context, final Declaration declaration) {
        final NordtalUser user = user(context.getSource());
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

    /**
     * A map lookup, never a query.
     *
     * Brigadier evaluates this while building the command tree it sends to a client, not a place for a blocking
     * JDBC call.
     *
     * The console passes here unconditionally and is refused later, per command, by its surface
     * set: a {@code requires} that hid a command from the console would hide it from tab completion
     * as well, and "the console may not run this one" is worth a sentence rather than a command
     * that appears not to exist.
     *
     * The reverse direction is a {@code requires} instead - see {@link #gate(Node)}. "A player may
     * not type this one" is answered by leaving the node out of their tree: a command taken off the
     * game is to be gone, while the console is the one surface that is never taken away.
     */
    private boolean mayUse(final CommandSource source) {
        if (source instanceof Player player) {
            return roster.isAdmin(player.getUniqueId());
        }
        return true;
    }

    private NordtalUser user(final CommandSource source) {
        return source instanceof Player player
                ? new VelocityUser(player, roster, messages, colours)
                // The console's own audience, so a reply reaches the proxy log rather than stdout.
                : new ConsoleUser(messages, proxy.getConsoleCommandSource());
    }
}
