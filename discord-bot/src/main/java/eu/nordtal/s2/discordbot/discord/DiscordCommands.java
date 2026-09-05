package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.commands.remote.RequestArguments;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Messages;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Every declared command, as Discord slash commands.
 *
 * <h2>What this makes true</h2>
 * "Every admin command on both platforms". Until now that held in one direction only: the bot had
 * {@code /phase} and the game had everything else, so an admin whose client could not reach a
 * backend - which includes the case where that backend is the problem - had no way to run
 * {@code /smp reload} or {@code /hg start} at all. A command whose target is not the bot becomes a
 * {@code command_request} row, and the answer comes back into the same ephemeral message.
 *
 * <h2>Discord's shape, and why the declarations already fit it</h2>
 * A slash command is at most three levels deep - {@code /root group sub} - which is exactly what the
 * deepest declarations need ({@code /smp objective complete}, {@code /smp farmreset now}). That is
 * not luck: the paths were chosen to be typed the same way on both surfaces, and Discord's limit is
 * the tighter of the two. {@code DiscordCommandsTest} fails the build if a fourth level or an
 * over-long name or description is ever declared, because JDA refuses the <b>whole command set</b> at
 * registration - so one bad command means the guild loses all of them, including the ones that were
 * fine.
 *
 * <h2>Two steps, as a button</h2>
 * An irreversible command is confirmed here with a button rather than by typing it again. Same rule,
 * different surface: Discord has buttons and chat does not, and asking somebody to retype a slash
 * command they picked out of a menu would be theatre. The button carries the command and its
 * arguments in its own id, so one minted by an older build, or for a different command, does
 * nothing - and says so, because a button that silently does nothing is how somebody concludes the
 * bot is broken.
 */
public final class DiscordCommands extends ListenerAdapter {

    /** JDA's own limits. Exceeding any of them makes JDA refuse the whole command set. */
    private static final int MAX_DESCRIPTION = 100;
    private static final int MAX_COMPONENT_ID = 100;

    /** The prefix that says a button belongs to this adapter and to no other flow. */
    static final String BUTTON = "nordtal:cmd:";

    /**
     * Between the path and the arguments in a button id.
     *
     * <p>A pipe cannot appear in the path, and the id is split on the <em>first</em> one, so an
     * argument that somehow contained one would still be read whole.</p>
     */
    static final char SEPARATOR = '|';

    private record Entry(Declaration declaration, BiConsumer<NordtalUser, Values> run,
                         Function<Values, Optional<Map.Entry<String, Map<String, ?>>>> problem) {
    }

    private final Messages messages;
    private final AdminFlagDao admins;
    private final AccessDirectory access;
    private final Outbox outbox;
    private final ExecutorService worker;
    private final Map<String, Entry> byPath = new LinkedHashMap<>();
    private final Map<String, java.util.function.Supplier<java.util.Collection<String>>> suggestions =
            new LinkedHashMap<>();

    /**
     * @param jdbi where the admin flag and the asker's language are read from. A {@code Jdbi} rather
     *             than the DAO itself, because {@link AdminFlagDao} is package-private on purpose -
     *             it is the bot's own read of a column {@code GuildState} owns the writing of, and
     *             nothing outside this package should be able to reach for it
     */
    public DiscordCommands(final Messages messages, final org.jdbi.v3.core.Jdbi jdbi,
                           final AccessDirectory access, final Outbox outbox,
                           final ExecutorService worker) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.admins = Objects.requireNonNull(jdbi, "jdbi").onDemand(AdminFlagDao.class);
        this.access = Objects.requireNonNull(access, "access");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    /** A command the bot runs itself. */
    public <E extends CommandEffects> DiscordCommands local(final NordtalCommand<E> command,
                                                            final E effects) {
        add(command.declaration(), (user, values) -> command.run(user, values, effects),
                command::problem);
        return this;
    }

    /**
     * A command another process runs.
     *
     * <p>Skipped for anything not declared on {@link Surface#DISCORD}: {@code /smp navigate} opens
     * an inventory, and a slash command answering "you have to be in game" is worse than one that
     * does not exist. Skipped rather than refused, so a caller can hand over the whole catalogue.</p>
     */
    public DiscordCommands remote(final Declaration declaration) {
        if (!declaration.surfaces().contains(Surface.DISCORD)
                || byPath.containsKey(key(declaration))) {
            return this;
        }
        add(declaration, (user, values) -> outbox.send(declaration, user, values),
                // Asked on the far side, where the command is: this process holds the declaration
                // and not the implementation.
                values -> Optional.empty());
        return this;
    }

    /**
     * What to offer for one argument while somebody is still typing it.
     *
     * <p>The mirror of {@code PaperCommands#suggest}, and the same rule: it must be in memory or
     * cheap, because Discord asks per keystroke. {@code /access settle} is what this exists for -
     * without the list of open references it is a six-character string recalled from memory, on the
     * one command that books money.</p>
     */
    public DiscordCommands suggest(final Declaration declaration, final String argument,
                                   final java.util.function.Supplier<java.util.Collection<String>> values) {
        if (declaration.arguments().stream().noneMatch(a -> a.name().equals(argument))) {
            throw new IllegalArgumentException(declaration.name() + " has no argument '" + argument
                    + "', so nothing would ever ask for these suggestions");
        }
        suggestions.put(declaration.name() + " " + argument, Objects.requireNonNull(values, "values"));
        return this;
    }

    /** Every declaration, minus the ones this bot already runs itself. */
    public DiscordCommands remoteAll(final List<Declaration> declarations) {
        declarations.forEach(this::remote);
        return this;
    }

    private void add(final Declaration declaration, final BiConsumer<NordtalUser, Values> run,
                     final Function<Values, Optional<Map.Entry<String, Map<String, ?>>>> problem) {
        if (byPath.putIfAbsent(key(declaration), new Entry(declaration, run, problem)) != null) {
            throw new IllegalArgumentException("two commands both claim " + declaration.name());
        }
    }

    /**
     * The command set, for {@code jda.updateCommands()}.
     *
     * <p>Built from the declarations, so a command added to the catalogue appears in Discord without
     * anything here being edited. That is what the catalogue is for.</p>
     */
    public List<CommandData> commands() {
        final Map<String, SlashCommandData> roots = new LinkedHashMap<>();

        for (final Entry entry : byPath.values()) {
            final List<String> path = entry.declaration().path();
            final SlashCommandData root = roots.computeIfAbsent(path.getFirst(),
                    name -> net.dv8tion.jda.api.interactions.commands.build.Commands
                            .slash(name, rootDescription(name))
                            .setDescriptionLocalization(DiscordLocale.GERMAN,
                                    german("command.describe.root." + name))
                            // Hidden from ordinary members at Discord's own level too. It is NOT the
                            // check - discord_user.admin is, and Discord's permission system is not
                            // that list - but a command nobody may run should not be in the menu.
                            .setDefaultPermissions(DefaultMemberPermissions.DISABLED));

            switch (path.size()) {
                case 1 -> options(entry).forEach(root::addOptions);
                case 2 -> root.addSubcommands(subcommand(path.get(1), entry));
                case 3 -> group(root, path.get(1)).addSubcommands(subcommand(path.get(2), entry));
                default -> throw new IllegalStateException(entry.declaration().name()
                        + " is " + path.size() + " levels deep and Discord allows three. The paths"
                        + " were chosen to fit both surfaces; a fourth level needs a different"
                        + " shape, not a workaround here.");
            }
        }
        return List.copyOf(roots.values());
    }

    private SubcommandGroupData group(final SlashCommandData root, final String name) {
        return root.getSubcommandGroups().stream()
                .filter(existing -> existing.getName().equals(name))
                .findFirst()
                .orElseGet(() -> {
                    final SubcommandGroupData created = new SubcommandGroupData(name,
                            rootDescription(root.getName()))
                            .setDescriptionLocalization(DiscordLocale.GERMAN,
                                    german("command.describe.root." + root.getName()));
                    root.addSubcommandGroups(created);
                    return created;
                });
    }

    private SubcommandData subcommand(final String name, final Entry entry) {
        final SubcommandData data = new SubcommandData(name, describe(entry.declaration()))
                .setDescriptionLocalization(DiscordLocale.GERMAN,
                        german(entry.declaration().describeKey()));
        options(entry).forEach(data::addOptions);
        return data;
    }

    private List<OptionData> options(final Entry entry) {
        final List<OptionData> options = new ArrayList<>();
        for (final Argument argument : entry.declaration().arguments()) {
            final OptionData option = switch (argument.kind()) {
                case WORD, GREEDY_STRING, CHOICE -> new OptionData(OptionType.STRING,
                        argument.name(), argumentDescription(argument), argument.required());
                case INTEGER -> new OptionData(OptionType.INTEGER, argument.name(),
                        argumentDescription(argument), argument.required())
                        .setMinValue(argument.min())
                        .setMaxValue(argument.max());
                // A member picked from Discord's own list, resolved through account_link. That is
                // the half of Argument.PLAYER only this surface can do, and it is why a command sees
                // a UUID either way.
                // Both are a member picked from Discord's own list. They differ in what comes out:
                // a PLAYER is followed through account_link to a UUID, an ACCOUNT is taken as the
                // Discord id itself - which is what lets /access grant work for somebody who has
                // not linked a Minecraft account at all, and is exactly who it exists for.
                case PLAYER, ACCOUNT -> new OptionData(OptionType.USER, argument.name(),
                        argumentDescription(argument), argument.required());
            };
            option.setDescriptionLocalization(DiscordLocale.GERMAN,
                    german("command.argument." + argument.name()));
            if (suggestions.containsKey(entry.declaration().name() + " " + argument.name())) {
                option.setAutoComplete(true);
            }
            if (argument.kind() == Argument.Kind.CHOICE) {
                argument.choices().forEach(choice -> option.addChoice(choice, choice));
            }
            options.add(option);
        }
        return options;
    }

    // ------------------------------------------------------------------ the interaction

    @Override
    public void onSlashCommandInteraction(final SlashCommandInteractionEvent event) {
        final List<String> path = new ArrayList<>();
        path.add(event.getName());
        if (event.getSubcommandGroup() != null) {
            path.add(event.getSubcommandGroup());
        }
        if (event.getSubcommandName() != null) {
            path.add(event.getSubcommandName());
        }
        final Entry entry = byPath.get(String.join(" ", path));
        if (entry == null) {
            return;
        }

        // Ephemeral and deferred, always. An interaction not acknowledged within three seconds is
        // dead, and everything below - the admin flag, the account link, the request row - is a
        // database round trip that must not happen on a gateway thread.
        event.deferReply(true).queue();
        worker.execute(() -> dispatch(event, entry));
    }

    private void dispatch(final SlashCommandInteractionEvent event, final Entry entry) {
        final DiscordUser user = resolve(event);
        if (entry.declaration().adminOnly() && !user.admin()) {
            user.reply("command.not-admin");
            return;
        }

        final Map<String, Object> values = new LinkedHashMap<>();
        for (final Argument argument : entry.declaration().arguments()) {
            final OptionMapping option = event.getOption(argument.name());
            if (option == null) {
                continue;
            }
            switch (argument.kind()) {
                case INTEGER -> values.put(argument.name(), (int) option.getAsLong());
                case ACCOUNT -> values.put(argument.name(), option.getAsUser().getId());
                case PLAYER -> {
                    final Optional<UUID> linked =
                            access.linkedMinecraftAccount(option.getAsUser().getId());
                    if (linked.isEmpty()) {
                        // Discord knows this member and the network does not. Its own sentence,
                        // because it is a different problem from "that player is not online".
                        user.reply("command.player-unlinked",
                                Map.of("player", option.getAsUser().getName()));
                        return;
                    }
                    values.put(argument.name(), linked.get());
                }
                default -> values.put(argument.name(), option.getAsString());
            }
        }

        final Values parsed = new Values(entry.declaration(), values);
        final var problem = entry.problem().apply(parsed);
        if (problem.isPresent()) {
            user.reply(problem.get().getKey(), problem.get().getValue());
            return;
        }

        if (entry.declaration().irreversible()) {
            confirm(user, entry.declaration(), parsed);
            return;
        }
        entry.run().accept(user, parsed);
    }

    /** Ask before an irreversible command, with a button that carries what it will do. */
    private void confirm(final DiscordUser user, final Declaration declaration,
                         final Values values) {
        final String arguments;
        try {
            arguments = RequestArguments.encode(declaration, values);
        } catch (final RuntimeException malformed) {
            user.reply("command.remote.failed");
            return;
        }
        final String id = buttonId(declaration, arguments);
        if (id.length() > MAX_COMPONENT_ID) {
            // Cannot happen for anything declared today - DiscordCommandsTest asserts every
            // declaration fits - so this is a guard against a future argument rather than a live
            // path, and it fails loudly rather than dropping the confirmation.
            throw new IllegalStateException(declaration.name() + "'s confirmation id is "
                    + id.length() + " characters and Discord allows " + MAX_COMPONENT_ID);
        }

        user.reply("command.confirm.discord", Map.of("command", declaration.usage()));
        user.hook().editOriginal(user.text())
                .setComponents(ActionRow.of(
                        Button.danger(id, messages.get(user.locale(), "command.confirm.yes")),
                        Button.secondary(BUTTON + "cancel",
                                messages.get(user.locale(), "command.confirm.no"))))
                .queue();
    }

    @Override
    public void onButtonInteraction(final ButtonInteractionEvent event) {
        final String id = event.getComponentId();
        if (!id.startsWith(BUTTON)) {
            return;
        }
        // The components go NOW, not when the command's first reply lands. deferEdit only
        // acknowledges the interaction; until the message is actually edited the button is still
        // there and still live, and the work is on another thread - so the same button could be
        // pressed twice. On /smp farmreset now that is two world deletions.
        event.editComponents().queue();
        worker.execute(() -> {
            final DiscordUser user = resolve(event);
            if (id.equals(BUTTON + "cancel")) {
                user.reply("command.cancelled");
                return;
            }

            final Optional<Entry> entry = decode(id);
            if (entry.isEmpty()) {
                user.reply("command.confirm.stale");
                return;
            }
            // Re-checked here and not carried on the button: an admin can be revoked between being
            // shown the confirmation and pressing it, which is exactly the case the live revocation
            // was built for.
            if (entry.get().declaration().adminOnly() && !user.admin()) {
                user.reply("command.not-admin");
                return;
            }
            try {
                entry.get().run().accept(user, RequestArguments.decode(
                        entry.get().declaration(), argumentsOf(id)));
            } catch (final RuntimeException malformed) {
                user.reply("command.confirm.stale");
            }
        });
    }

    @Override
    public void onCommandAutoCompleteInteraction(
            final net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent event) {
        final List<String> path = new ArrayList<>();
        path.add(event.getName());
        if (event.getSubcommandGroup() != null) {
            path.add(event.getSubcommandGroup());
        }
        if (event.getSubcommandName() != null) {
            path.add(event.getSubcommandName());
        }
        final Entry entry = byPath.get(String.join(" ", path));
        if (entry == null) {
            return;
        }
        final var offered = suggestions.get(
                entry.declaration().name() + " " + event.getFocusedOption().getName());
        if (offered == null) {
            return;
        }

        // On the worker, not here. An autocomplete interaction arrives on a gateway thread, and a
        // supplier is allowed to be a query - the only one registered today is `openReferences`,
        // which is a SELECT. Three seconds is the budget either way, and a gateway thread waiting
        // on the database stalls the whole guild.
        //
        // The admin flag is deliberately NOT re-read: it would be a second query per keystroke, and
        // these values are only offered to somebody Discord already shows the command to. The
        // command itself re-checks discord_user.admin before doing anything with the answer, which
        // the bot's old /settle did in neither place.
        final String typed = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        worker.execute(() -> event.replyChoiceStrings(offered.get().stream()
                        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(typed))
                        // Discord shows at most 25 and refuses a reply with more.
                        .limit(25)
                        .toList())
                .queue());
    }

    /**
     * The id a confirmation button carries: this adapter's prefix, the command's path, and the
     * arguments it was asked with.
     *
     * <p>Everything the press needs is in the id and nothing is held in memory, which is what makes
     * a button survive a restart of the bot - and what makes one minted by an older build
     * identifiable rather than merely inert.</p>
     */
    static String buttonId(final Declaration declaration, final String arguments) {
        return BUTTON + String.join(" ", declaration.path()) + SEPARATOR + arguments;
    }

    /** The command path a button id names, or empty when the id is not one of ours. */
    static Optional<String> commandOf(final String id) {
        if (!id.startsWith(BUTTON)) {
            return Optional.empty();
        }
        final String rest = id.substring(BUTTON.length());
        final int split = rest.indexOf(SEPARATOR);
        return split < 0 ? Optional.empty() : Optional.of(rest.substring(0, split));
    }

    /** The arguments a button id carries. Empty string for a command that takes none. */
    static String argumentsOf(final String id) {
        final String rest = id.substring(BUTTON.length());
        return rest.substring(rest.indexOf(SEPARATOR) + 1);
    }

    /** The command a button id names, or empty when this build does not have it. */
    private Optional<Entry> decode(final String id) {
        return commandOf(id).map(byPath::get);
    }

    private DiscordUser resolve(final IReplyCallback event) {
        final String discordId = event.getUser().getId();
        return new DiscordUser(event.getUser(),
                Locales.parse(admins.localeOf(discordId).orElse(null)),
                AdminFlagDao.admits(admins.isAdmin(discordId)),
                event.getHook(), messages);
    }

    // ------------------------------------------------------------------ descriptions

    private String describe(final Declaration declaration) {
        return shorten(messages.get(Locale.ENGLISH, declaration.describeKey()));
    }

    private String rootDescription(final String root) {
        // A root is not a command, so there is nothing to describe but the family. Discord requires
        // a non-empty description for every command, including one that only holds subcommands.
        return shorten(messages.get(Locale.ENGLISH, "command.describe.root." + root));
    }

    private String argumentDescription(final Argument argument) {
        return shorten(messages.get(Locale.ENGLISH, "command.argument." + argument.name()));
    }

    /**
     * The German label, for Discord's own localisation.
     *
     * <p>The NAMES stay English on both surfaces: a path is a command's identity, and a command with
     * two names is a command people report bugs about twice. Only the descriptions are translated,
     * which is what a German client actually reads in the menu.</p>
     */
    private String german(final String key) {
        return shorten(messages.get(Locale.GERMAN, key));
    }

    /**
     * Discord's hundred characters, taken at a sentence boundary where there is one.
     *
     * <h2>Why the describe keys are not simply written short</h2>
     * They are read in chat as well, where there is room for the reason as well as the effect - and
     * the reason is the half that stops somebody running {@code /smp milestone unlock} to see what
     * it does. Cutting at the full stop keeps the sentence that says what the command does and drops
     * the one that says why; cutting mid-word would keep neither.
     *
     * <p>English only, because a Discord command description is registered once and globally: it is
     * not rendered per viewer. Everything the command <em>says back</em> is still in the asker's own
     * language.</p>
     */
    static String shorten(final String text) {
        if (text.length() <= MAX_DESCRIPTION) {
            return text;
        }
        final int sentence = text.indexOf(". ");
        if (sentence > 0 && sentence + 1 <= MAX_DESCRIPTION) {
            return text.substring(0, sentence + 1);
        }
        return text.substring(0, MAX_DESCRIPTION - 1) + "…";
    }

    private static String key(final Declaration declaration) {
        return String.join(" ", declaration.path());
    }
}
