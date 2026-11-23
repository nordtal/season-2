package eu.nordtal.season.stewardagent.backup;

import eu.nordtal.season.common.http.Reply;
import eu.nordtal.season.common.http.WebClient;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.internalapi.backup.BackupDestinationWire;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Asks steward where the stack's copy goes and tells it how the copy went, with the agent token. */
public final class StewardDestination implements OffsiteCopy.Destination {

    private static final Logger log = LoggerFactory.getLogger(StewardDestination.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final WebClient web;
    private final URI credentials;
    private final URI report;

    /**
     * @param setting the configuration key of {@code baseUrl}, named when it is refused
     * @throws IllegalArgumentException when {@code baseUrl} would carry the token in clear across a network
     */
    public StewardDestination(final String setting, final String baseUrl, final String token) {
        final String base = WebClient.tokenSafe(setting, baseUrl);
        this.web = WebClient.create(TIMEOUT).bearer(token);
        this.credentials = URI.create(
                base + BackupDestinationWire.of(BackupDestinationWire.CREDENTIALS, BackupDestinationWire.STACK));
        this.report =
                URI.create(base + BackupDestinationWire.of(BackupDestinationWire.REPORT, BackupDestinationWire.STACK));
    }

    @Override
    public Optional<BackupDestinationWire.Credentials> credentials() throws IOException {
        final Reply reply = web.get(credentials);
        if (reply.status() == 204) {
            return Optional.empty();
        }
        if (!reply.ok()) {
            // The body may name the account, so only the status leaves this process.
            throw new IOException("steward answered " + reply.status());
        }
        return Optional.of(Json.decode(reply.body(), BackupDestinationWire.Credentials.class));
    }

    @Override
    public void report(final BackupDestinationWire.Report outcome) {
        try {
            final Reply reply = web.postJson(report, outcome);
            if (!reply.ok()) {
                log.warn("backup copy report refused: status={}", reply.status());
            }
        } catch (final IOException failed) {
            log.warn("backup copy report not sent: cause=\"{}\"", failed.getMessage());
        }
    }
}
