package eu.nordtal.season.stewardagent;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.time.NetworkTime;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.RunLines;
import eu.nordtal.season.database.update.RunRecord;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messages.Refused;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.DatabaseWaiting;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.stewardagent.config.AgentSettings;
import eu.nordtal.season.stewardagent.schema.Schema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The host's side of a run: it writes a request to the inbox and reads its status.
 *
 * {@code request KIND [a,b] [MINUTES] [--replace-local]} prints the row's id, {@code status ID} its status and steps.
 */
final class HostRequests {

    static final String REPLACE_LOCAL = "--replace-local";

    static final int KEPT_LOCAL = 3;

    private HostRequests() {}

    static int run(final String[] args) {
        final DatabaseSpec config;
        try {
            config = AgentSettings.database().get();
        } catch (final SettingsException broken) {
            System.err.println("the database settings cannot be read: " + broken.getMessage());
            return 1;
        }
        final Database opened = DatabaseWaiting.openDatabase(
                config,
                "steward-agent-host",
                Duration.ofSeconds(10),
                Duration.ofSeconds(1),
                Waiting.on(NetworkTime.clock()));
        if (opened == null) {
            System.err.println("the database did not answer, so nothing was asked");
            return 1;
        }
        try (Database database = opened) {
            final UpdateDirectory runs = Schema.updates(database);
            return "status".equals(args[0]) ? status(runs, args) : request(runs, args);
        }
    }

    private static int request(final UpdateDirectory runs, final String[] given) {
        final List<String> args = new ArrayList<>(List.of(given));
        final boolean replaceLocal = args.remove(REPLACE_LOCAL);
        if (args.size() < 2) {
            System.err.println("request KIND [SERVICES] [MINUTES] [" + REPLACE_LOCAL + "]: KIND is one of "
                    + Arrays.toString(UpdateKind.values()));
            return 2;
        }
        final UpdateKind kind;
        try {
            kind = UpdateKind.valueOf(args.get(1).toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException unknown) {
            System.err.println(args.get(1) + " is not a kind of run");
            return 2;
        }
        if (replaceLocal && kind != UpdateKind.UPDATE) {
            System.err.println(REPLACE_LOCAL + " is for an update only, the one kind that replaces a build");
            return 2;
        }
        final List<String> services =
                args.size() > 2 && !args.get(2).isBlank() ? List.of(args.get(2).split(",")) : List.of();
        final Duration delay = Duration.ofMinutes(args.size() > 3 ? Long.parseLong(args.get(3)) : 0);
        try {
            final UpdateRequest written = replaceLocal
                    ? runs.submit(new StewardRequest.Update(true), services, Actor.HOST, delay)
                    : runs.submit(kind, Actor.HOST, delay, services);
            System.out.println(written.id());
            return 0;
        } catch (final Refused refused) {
            System.err.println(DatabaseText.english(refused.refusal().message()));
            return 1;
        } catch (final IllegalArgumentException refused) {
            System.err.println(refused.getMessage());
            return 2;
        }
    }

    private static int status(final UpdateDirectory runs, final String[] args) {
        if (args.length < 2 || !args[1].matches("[0-9]+")) {
            System.err.println("status ID");
            return 2;
        }
        final Optional<UpdateRequest> row = runs.find(Long.parseLong(args[1]));
        if (row.isEmpty()) {
            return 1;
        }
        final UpdateStatus status = row.get().status();
        final RunRecord run = runs.recordOf(row.get().id());
        told(status, run).forEach(System.out::println);
        return keptLocal(status, run) ? KEPT_LOCAL : 0;
    }

    /** The status and stage on one line, then each step with its facts indented below it. */
    static List<String> told(final UpdateStatus status, final RunRecord run) {
        final List<String> told = new ArrayList<>();
        told.add(status + "\t" + (run.stage() == null ? "" : run.stage().name()));
        for (final RunStep.Row step : run.steps()) {
            told.add(DatabaseText.english(TEXTS.run().stepKind(step.step()))
                    + (step.service() == null ? "" : " " + step.service())
                    + (step.outcome() == null
                            ? ""
                            : ": " + DatabaseText.english(TEXTS.run().outcome(step.outcome()))));
            RunLines.of(step).forEach(line -> told.add("    " + DatabaseText.english(line)));
        }
        return told;
    }

    static boolean keptLocal(final UpdateStatus status, final RunRecord run) {
        return status == UpdateStatus.FAILED
                && run.steps().stream().anyMatch(step -> step.reason() == RunStep.Reason.REFUSED_LOCAL_BUILD);
    }
}
