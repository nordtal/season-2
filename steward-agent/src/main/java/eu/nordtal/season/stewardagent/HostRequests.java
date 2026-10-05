package eu.nordtal.season.stewardagent;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.time.NetworkTime;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.messages.Refused;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.DatabaseWaiting;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.stewardagent.config.AgentSettings;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * How the host asks for a run and follows it, through the inbox and the refusals everyone else meets.
 *
 * {@code request KIND [a,b] [MINUTES]} prints the row's id, {@code status ID} its status, a tab and its report.
 */
final class HostRequests {

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
            final UpdateDirectory runs = UpdateDirectory.using(database.dataSource());
            return "status".equals(args[0]) ? status(runs, args) : request(runs, args);
        }
    }

    private static int request(final UpdateDirectory runs, final String[] args) {
        if (args.length < 2) {
            System.err.println(
                    "request KIND [SERVICES] [MINUTES]: KIND is one of " + Arrays.toString(UpdateKind.values()));
            return 2;
        }
        final UpdateKind kind;
        try {
            kind = UpdateKind.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException unknown) {
            System.err.println(args[1] + " is not a kind of run");
            return 2;
        }
        final List<String> services = args.length > 2 && !args[2].isBlank() ? List.of(args[2].split(",")) : List.of();
        final Duration delay = Duration.ofMinutes(args.length > 3 ? Long.parseLong(args[3]) : 0);
        try {
            final UpdateRequest written = runs.submit(kind, Actor.HOST, delay, services);
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
        final String stored = row.get().result();
        final String report = stored == null ? "" : UpdateReports.english(stored);
        System.out.println(row.get().status() + "\t" + report.replace('\n', ' '));
        return 0;
    }
}
