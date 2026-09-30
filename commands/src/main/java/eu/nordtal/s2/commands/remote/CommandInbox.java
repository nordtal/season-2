package eu.nordtal.s2.commands.remote;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.database.command.CommandRequest;
import eu.nordtal.s2.database.command.CommandRequests;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.Tone;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * The far end of a travelling command: claims a request, runs it here and writes the answer back.
 *
 * Admin is re-read against the claimed identity, since {@code discord_user.admin} can be revoked while a row waits.
 */
public final class CommandInbox {

    /** Whether an identity is currently an admin, re-read per claimed request. */
    @FunctionalInterface
    public interface AdminCheck {

        /**
         * @param request the claimed row: its {@code discordId} is the identity to check, its {@code minecraftId} the
         *     fallback
         * @return whether they may run an admin-only command right now
         */
        boolean isAdmin(CommandRequest request);

        /**
         * Admits the console by its source and anyone else only by an identity in the admin set.
         *
         * @param admins            every admin's Discord id, re-read per call
         * @param adminMinecraftIds every admin's Minecraft account, for a game row with no Discord link
         */
        static AdminCheck of(
                final java.util.function.Supplier<java.util.Set<String>> admins,
                final java.util.function.Supplier<java.util.Set<java.util.UUID>> adminMinecraftIds) {
            return request -> {
                if ("CONSOLE".equals(request.source())) {
                    return true;
                }
                if (request.discordId().isPresent()) {
                    return admins.get().contains(request.discordId().get().value());
                }
                return request.minecraftId()
                        .map(mcUuid -> adminMinecraftIds.get().contains(mcUuid))
                        .orElse(false);
            };
        }
    }

    private record Entry(Declaration declaration, BiConsumer<NordtalUser, Values> run) {}

    private final String target;
    private final CommandRequests requests;
    private final Messages messages;
    private final AdminCheck adminCheck;
    private final BiConsumer<String, Throwable> warn;
    private final Map<String, Entry> commands = new HashMap<>();
    private final AtomicBoolean draining = new AtomicBoolean();

    public CommandInbox(
            final Target target,
            final CommandRequests requests,
            final Messages messages,
            final AdminCheck adminCheck,
            final BiConsumer<String, Throwable> warn) {
        this.target = Objects.requireNonNull(target, "target").name();
        this.requests = Objects.requireNonNull(requests, "requests");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.adminCheck = Objects.requireNonNull(adminCheck, "adminCheck");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /**
     * Makes a command runnable here.
     *
     * @throws IllegalArgumentException if the target is not this inbox's, two commands claim one path, or the effects
     *     run {@code async} on another thread
     */
    public <E extends CommandEffects> CommandInbox register(final NordtalCommand<E> command, final E effects) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(effects, "effects");

        requireInline(command, effects);
        final Declaration declaration = command.declaration();
        if (!declaration.target().name().equals(target)) {
            throw new IllegalArgumentException(declaration.name() + " is run by " + declaration.target()
                    + " and was registered on the " + target + " inbox");
        }
        final String path = key(declaration);
        if (commands.putIfAbsent(path, new Entry(declaration, (user, values) -> command.run(user, values, effects)))
                != null) {
            throw new IllegalArgumentException("two commands both claim " + declaration.name());
        }
        return this;
    }

    /**
     * Runs everything waiting for this process; a re-entrant call does nothing rather than queueing.
     *
     * @return how many requests were settled
     */
    public int drain() {
        if (!draining.compareAndSet(false, true)) {
            return 0;
        }
        try {
            int handled = 0;
            while (true) {
                final Optional<CommandRequest> claimed;
                try {
                    claimed = requests.claim(target);
                } catch (final RuntimeException failure) {
                    warn.accept("could not claim a command request", failure);
                    return handled;
                }
                if (claimed.isEmpty()) {
                    return handled;
                }
                handle(claimed.get());
                handled++;
            }
        } finally {
            draining.set(false);
        }
    }

    /** Returns how many commands can be run here. */
    public int size() {
        return commands.size();
    }

    /**
     * Refuses effects whose {@code async} has not run on the calling thread by the time it returns.
     *
     * Otherwise the row settles before the command speaks; checking the thread, unlike a flag, cannot race.
     */
    private static void requireInline(final NordtalCommand<?> command, final CommandEffects effects) {
        final AtomicReference<Thread> ranOn = new AtomicReference<>();
        effects.async(() -> ranOn.set(Thread.currentThread()));
        if (!Thread.currentThread().equals(ranOn.get())) {
            throw new IllegalArgumentException(command.declaration().name()
                    + " was registered on the command inbox with effects that hand their work to"
                    + " another thread. The inbox settles the request when run() returns, so the"
                    + " answer would be written before the command produced it - build these"
                    + " effects with Runnable::run instead of a scheduler.");
        }
    }

    private void handle(final CommandRequest request) {
        final Entry entry = commands.get(request.command());
        if (entry == null) {
            // A row for a command this build does not have. That is a version skew.
            settle(
                    request,
                    false,
                    messages.format(
                            localeOf(request), MESSAGES.command().remote().unknown("/" + request.command())));
            return;
        }

        final Optional<Boolean> admin = checkAdmin(request);
        if (admin.isEmpty()) {
            return;
        }

        final RemoteUser user = new RemoteUser(request, messages, admin.get());
        if (entry.declaration().adminOnly() && !admin.get()) {
            // Not a duplicate of the asking side's check: this is the revocation that happened while the row waited.
            user.reply(MESSAGES.command().notAdmin(), Tone.BAD);
            settle(request, true, user.text());
            return;
        }

        final Optional<Values> values = decodeArguments(entry, request);
        if (values.isEmpty()) {
            return;
        }

        runEntry(entry, request, user, values.get());
    }

    private Optional<Boolean> checkAdmin(final CommandRequest request) {
        try {
            return Optional.of(adminCheck.isAdmin(request));
        } catch (final RuntimeException failure) {
            warn.accept("could not re-check the admin flag for /" + request.command(), failure);
            settle(
                    request,
                    false,
                    messages.format(
                            localeOf(request), MESSAGES.command().remote().failed()));
            return Optional.empty();
        }
    }

    private Optional<Values> decodeArguments(final Entry entry, final CommandRequest request) {
        try {
            return Optional.of(RequestArguments.decode(entry.declaration(), request.arguments()));
        } catch (final RuntimeException malformed) {
            warn.accept(
                    "/" + request.command() + " arrived with arguments this build cannot read: " + request.arguments(),
                    malformed);
            settle(
                    request,
                    false,
                    messages.format(
                            localeOf(request), MESSAGES.command().remote().arguments("/" + request.command())));
            return Optional.empty();
        }
    }

    private void runEntry(final Entry entry, final CommandRequest request, final RemoteUser user, final Values values) {
        try {
            entry.run().accept(user, values);
        } catch (final RuntimeException failure) {
            warn.accept("/" + request.command() + " threw while running for " + request.requestedBy(), failure);
            settle(
                    request,
                    false,
                    messages.format(
                            localeOf(request), MESSAGES.command().remote().failed()));
            return;
        }

        // A command that answered nothing still worked, and saying so tells the asker it ran.
        settle(
                request,
                true,
                user.lineCount() == 0
                        ? messages.format(
                                localeOf(request), MESSAGES.command().remote().silent())
                        : user.text());
    }

    private void settle(final CommandRequest request, final boolean ok, final String result) {
        try {
            requests.finish(request.id(), ok, result);
        } catch (final RuntimeException failure) {
            // Nothing left to do with it. The row stays RUNNING and the asker's wait runs out.
            warn.accept("could not settle command request " + request.id(), failure);
        }
    }

    private java.util.Locale localeOf(final CommandRequest request) {
        return eu.nordtal.s2.common.language.Locales.parse(request.locale());
    }

    private static String key(final Declaration declaration) {
        return String.join(" ", declaration.path());
    }
}
