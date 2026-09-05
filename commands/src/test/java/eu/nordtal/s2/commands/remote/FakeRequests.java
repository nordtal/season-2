package eu.nordtal.s2.commands.remote;

import eu.nordtal.s2.common.command.CommandOutcome;
import eu.nordtal.s2.common.command.CommandRequest;
import eu.nordtal.s2.common.command.CommandRequests;
import eu.nordtal.s2.common.command.NewCommandRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The request table, in a map.
 *
 * <p>It enforces the same transitions the SQL does - a claim only takes a row that is
 * {@code PENDING} and unexpired, {@code finish} only settles a {@code RUNNING} one, {@code expire}
 * only a {@code PENDING} one - because those guards are what the two ends rely on, and a fake that
 * let anything through would make every test here pass on code the database would refuse.
 * {@code CommandRequestIntegrationTest} in {@code :common} is what proves the real statements agree
 * with this.</p>
 */
final class FakeRequests implements CommandRequests {

    private record Row(NewCommandRequest request, CommandOutcome.Status status, String result) {
    }

    private final Map<Long, Row> rows = new LinkedHashMap<>();
    private final List<NewCommandRequest> submitted = new ArrayList<>();
    private long next = 1;

    /** Set to make the next call of anything throw, for the "database stopped answering" branches. */
    RuntimeException failure;

    /** Every request written, in order. */
    List<NewCommandRequest> submitted() {
        return List.copyOf(submitted);
    }

    @Override
    public long submit(final NewCommandRequest request) {
        throwIfAsked();
        final long id = next++;
        rows.put(id, new Row(request, CommandOutcome.Status.PENDING, null));
        submitted.add(request);
        return id;
    }

    @Override
    public Optional<CommandRequest> claim(final String target) {
        throwIfAsked();
        for (final Map.Entry<Long, Row> entry : rows.entrySet()) {
            final Row row = entry.getValue();
            if (row.status() != CommandOutcome.Status.PENDING
                    || !row.request().target().equals(target)
                    || !row.request().expires().isAfter(Instant.now())) {
                continue;
            }
            rows.put(entry.getKey(), new Row(row.request(), CommandOutcome.Status.RUNNING, null));
            final NewCommandRequest request = row.request();
            return Optional.of(new CommandRequest(entry.getKey(), request.command(),
                    request.arguments(), request.source(), request.requestedBy(),
                    request.discordId(), request.minecraftId(), request.locale(),
                    request.expires()));
        }
        return Optional.empty();
    }

    @Override
    public void finish(final long id, final boolean ok, final String result) {
        throwIfAsked();
        final Row row = rows.get(id);
        if (row == null || row.status() != CommandOutcome.Status.RUNNING) {
            return;
        }
        rows.put(id, new Row(row.request(),
                ok ? CommandOutcome.Status.DONE : CommandOutcome.Status.FAILED, result));
    }

    @Override
    public boolean expire(final long id) {
        throwIfAsked();
        final Row row = rows.get(id);
        if (row == null || row.status() != CommandOutcome.Status.PENDING) {
            return false;
        }
        rows.put(id, new Row(row.request(), CommandOutcome.Status.EXPIRED, null));
        return true;
    }

    @Override
    public Optional<CommandOutcome> outcome(final long id) {
        throwIfAsked();
        return Optional.ofNullable(rows.get(id))
                .map(row -> new CommandOutcome(row.status(), Optional.ofNullable(row.result())));
    }

    @Override
    public int deleteSettledOlderThan(final int days) {
        // Retention is the updater's, not this module's. Nothing in :commands calls it.
        throw new UnsupportedOperationException("not part of what the inbox or the outbox does");
    }

    @Override
    public void close() {
    }

    /** The status of a row, for an assertion. */
    CommandOutcome.Status statusOf(final long id) {
        return rows.get(id).status();
    }

    /** The answer written back into a row, for an assertion. */
    String resultOf(final long id) {
        return rows.get(id).result();
    }

    /** Settle a row from outside, the way a target in another process would. */
    void answer(final long id, final boolean ok, final String result) {
        final Row row = rows.get(id);
        rows.put(id, new Row(row.request(),
                ok ? CommandOutcome.Status.DONE : CommandOutcome.Status.FAILED, result));
    }

    /** Claim a row without running anything, to reproduce the lost expiry race. */
    void claimSilently(final long id) {
        final Row row = rows.get(id);
        rows.put(id, new Row(row.request(), CommandOutcome.Status.RUNNING, null));
    }

    private void throwIfAsked() {
        if (failure != null) {
            throw failure;
        }
    }
}
