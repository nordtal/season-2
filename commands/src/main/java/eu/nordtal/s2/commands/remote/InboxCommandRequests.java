package eu.nordtal.s2.commands.remote;

import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.InboxStatus;
import eu.nordtal.s2.database.inbox.InboxTable;
import eu.nordtal.s2.database.inbox.Outcome;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.database.inbox.Schedule;
import eu.nordtal.s2.database.inbox.ServerRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** {@link CommandRequests} on the servers' inboxes: a command is the {@code COMMAND} kind of its target's table. */
final class InboxCommandRequests implements CommandRequests {

    private final Map<Target, Inbox<ServerRequest>> inboxes = new EnumMap<>(Target.class);
    private final Clock clock;

    InboxCommandRequests(final DataSource dataSource, final Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (final Target target : Target.values()) {
            table(target).ifPresent(table -> inboxes.put(target, Inbox.over(dataSource, table)));
        }
    }

    /** Returns the inbox a command for {@code target} travels to, or empty for a target no command travels to. */
    static Optional<InboxTable<ServerRequest>> table(final Target target) {
        return switch (target) {
            case SMP -> Optional.of(ServerRequest.SMP);
            case HUNGER_GAMES -> Optional.of(ServerRequest.HUNGER_GAMES);
            case LIMBO -> Optional.of(ServerRequest.LIMBO);
            case PROXY, BOT -> Optional.empty();
        };
    }

    private Inbox<ServerRequest> inbox(final Target target) {
        final Inbox<ServerRequest> inbox = inboxes.get(target);
        if (inbox == null) {
            throw new IllegalArgumentException("no command travels to " + target + ": it has no inbox");
        }
        return inbox;
    }

    @Override
    public long submit(final NewCommandRequest request) {
        return inbox(target(request))
                .submit(payload(request), actor(request), schedule(request))
                .id();
    }

    @Override
    public long submit(final NewCommandRequest request, final AuditLine journal) {
        return inbox(target(request))
                .submit(payload(request), actor(request), schedule(request), journal)
                .id();
    }

    @Override
    public Optional<CommandRequest> claim(final Target target) {
        return inbox(target).claim().map(InboxCommandRequests::claimed);
    }

    @Override
    public void finish(final Target target, final long id, final boolean ok, final String result) {
        // A row no longer running was settled twice; not worth throwing on a command thread.
        final var _ = inbox(target).settle(id, ok ? Outcome.done(result) : Outcome.failed(result));
    }

    @Override
    public boolean expire(final Target target, final long id) {
        return inbox(target)
                .find(id)
                .map(row -> row.status() == InboxStatus.PENDING || row.status() == InboxStatus.EXPIRED)
                .orElse(false);
    }

    @Override
    public Optional<CommandOutcome> outcome(final Target target, final long id) {
        return inbox(target).find(id).map(row -> new CommandOutcome(status(row.status()), text(row)));
    }

    private static CommandOutcome.Status status(final InboxStatus status) {
        return switch (status) {
            case PENDING -> CommandOutcome.Status.PENDING;
            case RUNNING -> CommandOutcome.Status.RUNNING;
            case DONE -> CommandOutcome.Status.DONE;
            case FAILED, REFUSED -> CommandOutcome.Status.FAILED;
            case EXPIRED, CANCELLED -> CommandOutcome.Status.EXPIRED;
        };
    }

    private static Optional<String> text(final Request<ServerRequest> row) {
        return row.outcome(String.class);
    }

    private static Target target(final NewCommandRequest request) {
        return Target.valueOf(request.target());
    }

    private static ServerRequest.Command payload(final NewCommandRequest request) {
        return new ServerRequest.Command(
                request.command(),
                request.arguments(),
                request.source(),
                request.requestedBy(),
                request.discordId().orElse(null),
                request.minecraftId().orElse(null),
                request.locale());
    }

    /** A person when the request knows their Discord id, and otherwise whoever sits at a console on the host. */
    private static Actor actor(final NewCommandRequest request) {
        return request.discordId().map(Actor::person).orElse(Actor.HOST);
    }

    private Schedule schedule(final NewCommandRequest request) {
        return Schedule.within(Duration.between(clock.instant(), request.expires()));
    }

    private static CommandRequest claimed(final Request<ServerRequest> row) {
        final ServerRequest.Command command = switch (row.payload()) {
            case ServerRequest.Command one -> one;
        };
        return new CommandRequest(
                row.id(),
                command.path(),
                command.arguments(),
                command.source(),
                command.requestedBy(),
                Optional.ofNullable(command.discordId()),
                Optional.ofNullable(command.minecraftId()),
                command.locale(),
                Objects.requireNonNull(row.expires(), "a command always expires"));
    }
}
