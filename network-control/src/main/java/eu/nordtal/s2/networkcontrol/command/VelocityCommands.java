package eu.nordtal.s2.networkcontrol.command;

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
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * {@code :paper-common}'s {@code PaperCommands}, for Velocity.
 *
 * <h2>Why this is a second class and not a shared one</h2>
 * The two platforms resolve <b>different Brigadier artefacts</b> - {@code com.mojang:brigadier} on
 * Paper, {@code com.velocitypowered:velocity-brigadier} on Velocity - and neither is on Maven
 * Central. A module compiled against both does not exist; a module compiled against neither cannot
 * name {@code CommandSource} or {@code CommandSourceStack}. That is the same reason
 * docs/architecture.md gave in 2026-08-31 for having no shared command helper at all, and it is
 * still true of the <em>tree building</em>. What changed is that everything above the tree - the
 * declaration, the decisions, the messages, the confirmation - is now shared, so what is duplicated
 * here is only the twenty lines that name a platform type.
 *
 * <p>The two are deliberately kept in the same shape, so a rule added to one is findable in the
 * other: same method names, same order, same comments where the reasoning is identical.</p>
 *
 * <h2>What the proxy does not register</h2>
 * The backends' commands. Velocity answers a command it knows <b>before the packet reaches a
 * backend</b>, so registering {@code /smp} here would shadow the SMP's own - turning a local command
 * into a round trip through a request row, on the very server that owns it. The backends register
 * each other's instead, which is why an admin on limbo can still run {@code /smp reload}.
 */
public final class VelocityCommands {

    private record Entry(Declaration declaration, BiConsumer<NordtalUser, Values> run,
                         java.util.function.Function<Values,
                                 java.util.Optional<Map.Entry<String, Map<String, ?>>>> problem) {
    }

    private static final class Node {

        private final String literal;
        private final Map<String, Node> children = new LinkedHashMap<>();
        private Entry command;

        private Node(final String literal) {
            this.literal = literal;
        }
    }

    private final ProxyServer proxy;
    private final LoginRoster roster;
    private final Messages messages;
    private final Confirmations confirmations = new Confirmations();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Supplier<Collection<String>>> suggestions = new LinkedHashMap<>();

    public VelocityCommands(final ProxyServer proxy, final LoginRoster roster,
                            final Messages messages) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /** A command this process runs itself. */
    public <E extends CommandEffects> VelocityCommands local(final NordtalCommand<E> command,
                                                             final E effects) {
        entries.add(new Entry(command.declaration(),
                (user, values) -> command.run(user, values, effects),
                command::problem));
        return this;
    }

    /** What to offer for one argument. Must be in memory - see {@code PaperCommands#suggest}. */
    public VelocityCommands suggest(final Declaration declaration, final String argument,
                                    final Supplier<Collection<String>> values) {
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
                throw new IllegalStateException("two commands both claim /"
                        + String.join(" ", path));
            }
            node.command = entry;
        }
        // Bottom-up, for the reason PaperCommands#build spells out: Brigadier's
        // ArgumentBuilder.then(ArgumentBuilder) builds its argument on the spot, so anything added
        // to a node after its parent took it is silently lost.
        return roots.values().stream()
                .map(root -> new BrigadierCommand(materialise(root)))
                .toList();
    }

    private LiteralArgumentBuilder<CommandSource> materialise(final Node node) {
        final LiteralArgumentBuilder<CommandSource> builder =
                BrigadierCommand.literalArgumentBuilder(node.literal);
        for (final Node child : node.children.values()) {
            builder.then(materialise(child).requires(this::mayUse));
        }
        final boolean runnableHere = node.command != null && arguments(builder, node.command);
        if (!runnableHere) {
            builder.executes(context -> help(context, node));
        }
        return builder;
    }

    private boolean arguments(final LiteralArgumentBuilder<CommandSource> parent,
                              final Entry entry) {
        final List<Argument> arguments = entry.declaration().arguments();
        if (arguments.isEmpty()) {
            parent.executes(context -> run(context, entry, Map.of()));
            return true;
        }

        RequiredArgumentBuilder<CommandSource, ?> child = null;
        for (int at = arguments.size() - 1; at >= 0; at--) {
            final RequiredArgumentBuilder<CommandSource, ?> node =
                    node(entry.declaration(), arguments.get(at));
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

    private RequiredArgumentBuilder<CommandSource, ?> node(final Declaration declaration,
                                                           final Argument argument) {
        final Supplier<Collection<String>> offered =
                suggestions.get(declaration.name() + " " + argument.name());
        return switch (argument.kind()) {
            case WORD -> {
                final RequiredArgumentBuilder<CommandSource, ?> word =
                        BrigadierCommand.requiredArgumentBuilder(argument.name(),
                                StringArgumentType.word());
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
                        BrigadierCommand.requiredArgumentBuilder(argument.name(),
                                StringArgumentType.greedyString());
                if (offered != null) {
                    greedy.suggests((context, builder) -> {
                        offered.get().forEach(builder::suggest);
                        return builder.buildFuture();
                    });
                }
                yield greedy;
            }
            case INTEGER -> BrigadierCommand.requiredArgumentBuilder(argument.name(),
                    IntegerArgumentType.integer(argument.min(), argument.max()));
            case PLAYER -> BrigadierCommand.requiredArgumentBuilder(argument.name(),
                            StringArgumentType.word())
                    .suggests((context, builder) -> {
                        proxy.getAllPlayers().forEach(online -> builder.suggest(online.getUsername()));
                        return builder.buildFuture();
                    });
            case CHOICE -> BrigadierCommand.requiredArgumentBuilder(argument.name(),
                            StringArgumentType.word())
                    .suggests((context, builder) -> {
                        argument.choices().forEach(builder::suggest);
                        return builder.buildFuture();
                    });
        };
    }

    private Map<String, Object> read(final CommandContext<CommandSource> context,
                                     final List<Argument> arguments, final int count) {
        final Map<String, Object> values = new LinkedHashMap<>();
        for (int at = 0; at < count; at++) {
            final Argument argument = arguments.get(at);
            switch (argument.kind()) {
                case INTEGER -> values.put(argument.name(),
                        IntegerArgumentType.getInteger(context, argument.name()));
                case PLAYER -> {
                    final var target = proxy.getPlayer(
                            StringArgumentType.getString(context, argument.name()));
                    if (target.isEmpty()) {
                        return values;
                    }
                    values.put(argument.name(), target.get().getUniqueId());
                }
                default -> values.put(argument.name(),
                        StringArgumentType.getString(context, argument.name()));
            }
        }
        return values;
    }

    private int run(final CommandContext<CommandSource> context, final Entry entry,
                    final Map<String, Object> values) {
        final NordtalUser user = user(context.getSource());

        if (user.origin() == NordtalUser.Origin.CONSOLE
                && !entry.declaration().surfaces().contains(Surface.CONSOLE)) {
            // /phase is the one this exists for: it records who took the decision, and the console
            // is nobody in particular. Rejected 2026-08-31 and enforced by each adapter separately
            // until the surface set became the single place that says so.
            user.reply("command.not-from-console", Map.of(), Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }

        for (final Argument argument : entry.declaration().arguments()) {
            if (argument.required() && !values.containsKey(argument.name())
                    && argument.kind() == Argument.Kind.PLAYER) {
                user.reply("command.player-offline", Map.of(), Feedback.REFUSED);
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
        user.reply("command.confirm.retype", Map.of(
                "command", what,
                "seconds", String.valueOf(Confirmations.WINDOW.toSeconds())), Feedback.REFUSED);
        return false;
    }

    private int help(final CommandContext<CommandSource> context, final Node node) {
        final NordtalUser user = user(context.getSource());
        final List<Declaration> below = new ArrayList<>();
        collect(node, below);

        if (!mayUse(context.getSource())) {
            below.removeIf(Declaration::adminOnly);
            if (below.isEmpty()) {
                user.reply("command.not-admin", Map.of(), Feedback.REFUSED);
                return Command.SINGLE_SUCCESS;
            }
        }
        if (below.isEmpty()) {
            user.reply("command.help.nothing", Map.of(), Feedback.REFUSED);
            return Command.SINGLE_SUCCESS;
        }
        if (below.size() == 1) {
            return usage(context, below.getFirst());
        }

        user.reply("command.help.header", Map.of("command", "/" + node.literal));
        below.stream()
                .sorted(Comparator.comparing(Declaration::name))
                .forEach(declaration -> user.reply("command.help.line",
                        Map.of("usage", declaration.usage(),
                                "what", user.phrase(declaration.describeKey()))));
        return Command.SINGLE_SUCCESS;
    }

    private int usage(final CommandContext<CommandSource> context, final Declaration declaration) {
        final NordtalUser user = user(context.getSource());
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

    /**
     * A map lookup, never a query - Brigadier evaluates this while building the command tree it
     * sends to a client, which is not a place for a blocking JDBC call.
     *
     * <p>The console passes here and is refused later, per command, by its surface set: a
     * {@code requires} that hid a command from the console would hide it from tab completion as
     * well, and "the console may not run this one" is worth a sentence rather than a command that
     * appears not to exist.</p>
     */
    private boolean mayUse(final CommandSource source) {
        if (source instanceof Player player) {
            return roster.isAdmin(player.getUniqueId());
        }
        return true;
    }

    private NordtalUser user(final CommandSource source) {
        return source instanceof Player player
                ? new VelocityUser(player, roster, messages)
                : new ConsoleUser(messages);
    }
}
