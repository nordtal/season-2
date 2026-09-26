package eu.nordtal.s2.commands.remote;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.command.CommandRequest;
import eu.nordtal.s2.common.command.CommandRequests;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.Tone;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * The far end of a travelling command: claim a request, run it here, write the answer back.
 *
 * {@link #drain()} is called on a {@code nordtal_command} notification <em>and</em> on a timer:
 * <b>the poll is the guarantee</b>, the notification is what makes a command feel instant, and a
 * notification is never the state - so every wake-up claims in a loop until the inbox is empty
 * rather than assuming one signal means one row.
 *
 * Authorisation happens here too, and it is not a duplicate. The asking surface refused a
 * non-admin already, immediately and with a sentence. This is the second check, and it exists
 * because {@code discord_user.admin} can be revoked while a row waits - which is precisely the
 * emergency the live revocation was built for. A request carries no permission with it; it carries
 * an identity, and permission is re-read against that identity here.
 *
 * A command that throws is an answer, not a crash. Whatever a command does, the row gets settled.
 * A target that claimed a row and then died is the one case this cannot cover, and it is why
 * {@code RUNNING} is distinguishable from {@code PENDING} afterwards: a request nobody ever
 * claimed is a target that is down, and one claimed and never settled is a target that is up and
 * stuck.
 */
public final class CommandInbox {

    /** Whether an identity is currently an admin. Re-read per claimed request, never cached here. */
    @FunctionalInterface
    public interface AdminCheck {

        /**
         * @param request the claimed row - its {@code discordId} is the identity to check, and its
         *                {@code minecraftId} the fallback for a surface that had no Discord id
         * @return whether they may run an admin-only command right now
         */
        boolean isAdmin(CommandRequest request);

        /**
         * The check every inbox should use, written once.
         *
         * What it refuses to do, and why the alternative is a hole:
         * {@code request.discordId().map(admins::contains).orElse(true)} looks equivalent, with the
         * {@code orElse(true)} meaning "the console, which is the operator" - but V11 only forces a Discord id for
         * {@code source='DISCORD'}: a {@code GAME} row may legitimately have none, and
         * {@code limbo} writes exactly those, because a waiting room holds no account links. Every
         * one of those rows was read as the console and ran <b>unauthorised</b>.
         *
         * So the console is identified by what it is - {@code source = 'CONSOLE'}, which the
         * schema pins to having neither identity - and everything else has to produce an identity
         * that is in the admin set. An absent one is refused, which is the direction to fail in.
         *
         * @param admins           every admin's Discord id, re-read per call
         * @param adminMinecraftIds every admin's Minecraft account, for a row written by a game
         *                          surface that had no link to hand
         */
        static AdminCheck of(
                final java.util.function.Supplier<java.util.Set<String>> admins,
                final java.util.function.Supplier<java.util.Set<java.util.UUID>> adminMinecraftIds) {
            return request -> {
                if ("CONSOLE".equals(request.source())) {
                    return true;
                }
                if (request.discordId().isPresent()) {
                    return admins.get().contains(request.discordId().get());
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
     * Make a command runnable here.
     *
     * The type parameter is what lets one registry hold commands over different effect
     * interfaces: the effects are captured at registration and never leave this method's signature,
     * so nothing downstream has to know that {@code /smp aura} and {@code /hg start} are typed
     * differently.
     *
     * @throws IllegalArgumentException if the command's target is not this inbox's, if two commands
     *                                  claim the same path, or if the effects hand their work to
     *                                  another thread - see {@link #requireInline}
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
     * Run everything waiting for this process.
     *
     * Re-entrant calls do nothing rather than queueing: the notification listener and the poll
     * timer are different threads and will occasionally arrive together, and one of them finding the
     * inbox already being drained is exactly the case where the right answer is to let the other one
     * finish the work it is already doing.
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

    /** How many commands can be run here. For a startup log line, and for tests. */
    public int size() {
        return commands.size();
    }

    /**
     * Refuse effects whose {@code async} does not run before it returns.
     *
     * The bug this makes impossible: a command's work happens inside {@link CommandEffects#async},
     * and this inbox settles the request row the moment {@code run} returns. Effects built with a
     * scheduler - the ones the local chat adapter uses, and the obvious thing to pass here by
     * accident - would therefore
     * settle the row before the command had said a word: the asker gets "the command changed
     * something and had nothing to say about it" for work that has not started, and the real answer
     * is written into a row nobody reads any more.
     *
     * Nothing about that failure points at its cause, and it only happens on the surface furthest
     * from the logs. So it is checked here, once, at startup: a probe is submitted through
     * {@code async}, and it has to have run <b>on this very thread</b> by the time the call
     * returns.
     *
     * The thread, and not a flag: asking "did it run?" with a flag is a race in both directions,
     * because a pool thread can pick the task up and set the flag between {@code async} returning
     * and the flag being read, so a scheduler would be waved through whenever the machine was busy
     * enough to context switch there - the dangerous direction.
     *
     * The identity of the thread has no such race. Another thread can never write <em>this</em>
     * thread into the box, and a same-thread executor always has by the time {@code async} returns.
     * Nothing waits, and nothing depends on who wins.
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

        // A command that answered nothing did its work and said nothing about it. Saying so is not decoration: a blank.
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
        return eu.nordtal.s2.common.message.Locales.parse(request.locale());
    }

    private static String key(final Declaration declaration) {
        return String.join(" ", declaration.path());
    }
}
