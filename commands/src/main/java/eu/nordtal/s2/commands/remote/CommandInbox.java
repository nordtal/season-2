package eu.nordtal.s2.commands.remote;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.command.CommandRequest;
import eu.nordtal.s2.common.command.CommandRequests;
import eu.nordtal.s2.common.message.Messages;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * The far end of a travelling command: claim a request, run it here, write the answer back.
 *
 * <h2>What it is called from, and why that is two things</h2>
 * {@link #drain()} is called on a {@code nordtal_command} notification <em>and</em> on a timer. That
 * is the rule the phase and the admin roster already follow and it is inherited rather than
 * rediscovered: <b>the poll is the guarantee</b>, the notification is what makes a command feel
 * instant, and a notification is never the state - so every wake-up claims in a loop until the
 * inbox is empty rather than assuming one signal means one row.
 *
 * <h2>Authorisation happens here too, and it is not a duplicate</h2>
 * The asking surface refused a non-admin already, immediately and with a sentence. This is the
 * second check, and it exists because {@code discord_user.admin} can be revoked while a row waits -
 * which is precisely the emergency the live revocation was built for. A request carries no
 * permission with it; it carries an identity, and permission is re-read against that identity here.
 *
 * <h2>A command that throws is an answer, not a crash</h2>
 * Whatever a command does, the row gets settled. A target that claimed a row and then died is the
 * one case this cannot cover, and it is why {@code RUNNING} is distinguishable from {@code PENDING}
 * afterwards: a request nobody ever claimed is a target that is down, and one claimed and never
 * settled is a target that is up and stuck.
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
    }

    private record Entry(Declaration declaration, BiConsumer<NordtalUser, Values> run) {
    }

    private final String target;
    private final CommandRequests requests;
    private final Messages messages;
    private final AdminCheck adminCheck;
    private final BiConsumer<String, Throwable> warn;
    private final Map<String, Entry> commands = new HashMap<>();
    private final AtomicBoolean draining = new AtomicBoolean();

    public CommandInbox(final Target target, final CommandRequests requests,
                        final Messages messages, final AdminCheck adminCheck,
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
     * <p>The type parameter is what lets one registry hold commands over different effect
     * interfaces: the effects are captured at registration and never leave this method's signature,
     * so nothing downstream has to know that {@code /smp aura} and {@code /hg start} are typed
     * differently.</p>
     *
     * @throws IllegalArgumentException if the command's target is not this inbox's, or if two
     *                                  commands claim the same path
     */
    public <E> CommandInbox register(final NordtalCommand<E> command, final E effects) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(effects, "effects");

        final Declaration declaration = command.declaration();
        if (!declaration.target().name().equals(target)) {
            throw new IllegalArgumentException(declaration.name() + " is run by "
                    + declaration.target() + " and was registered on the " + target + " inbox");
        }
        final String path = key(declaration);
        if (commands.putIfAbsent(path, new Entry(declaration,
                (user, values) -> command.run(user, values, effects))) != null) {
            throw new IllegalArgumentException("two commands both claim " + declaration.name());
        }
        return this;
    }

    /**
     * Run everything waiting for this process.
     *
     * <p>Re-entrant calls do nothing rather than queueing: the notification listener and the poll
     * timer are different threads and will occasionally arrive together, and one of them finding the
     * inbox already being drained is exactly the case where the right answer is to let the other one
     * finish the work it is already doing.</p>
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

    private void handle(final CommandRequest request) {
        final Entry entry = commands.get(request.command());
        if (entry == null) {
            // A row for a command this build does not have. That is a version skew - one process
            // updated and another did not - and it is worth saying so plainly, because the
            // alternative reading ("the command silently did nothing") is the one somebody would
            // otherwise arrive at.
            settle(request, false, messages.format(localeOf(request), "command.remote.unknown",
                    Map.of("command", "/" + request.command())));
            return;
        }

        final boolean admin;
        try {
            admin = adminCheck.isAdmin(request);
        } catch (final RuntimeException failure) {
            warn.accept("could not re-check the admin flag for /" + request.command(), failure);
            settle(request, false,
                    messages.format(localeOf(request), "command.remote.failed", Map.of()));
            return;
        }

        final RemoteUser user = new RemoteUser(request, messages, admin);
        if (entry.declaration().adminOnly() && !admin) {
            // Not a duplicate of the asking side's check: this is the revocation that happened while
            // the row waited. It settles DONE rather than FAILED - the command was answered, and
            // the answer is no.
            user.reply("command.not-admin");
            settle(request, true, user.text());
            return;
        }

        final Values values;
        try {
            values = RequestArguments.decode(entry.declaration(), request.arguments());
        } catch (final RuntimeException malformed) {
            warn.accept("/" + request.command() + " arrived with arguments this build cannot read: "
                    + request.arguments(), malformed);
            settle(request, false, messages.format(localeOf(request), "command.remote.arguments",
                    Map.of("command", "/" + request.command())));
            return;
        }

        try {
            entry.run().accept(user, values);
        } catch (final RuntimeException failure) {
            warn.accept("/" + request.command() + " threw while running for " + request.requestedBy(),
                    failure);
            settle(request, false,
                    messages.format(localeOf(request), "command.remote.failed", Map.of()));
            return;
        }

        // A command that answered nothing did its work and said nothing about it. Saying so is not
        // decoration: a blank reply and a request that never ran look identical to whoever is
        // watching a spinner in Discord.
        settle(request, true, user.lineCount() == 0
                ? messages.format(localeOf(request), "command.remote.silent", Map.of())
                : user.text());
    }

    private void settle(final CommandRequest request, final boolean ok, final String result) {
        try {
            requests.finish(request.id(), ok, result);
        } catch (final RuntimeException failure) {
            // Nothing left to do with it. The row stays RUNNING and the asker's wait runs out,
            // which is the correct outward behaviour for a target that cannot reach the database.
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
