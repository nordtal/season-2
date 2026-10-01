package eu.nordtal.s2.steward.bunq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import eu.nordtal.s2.steward.config.StewardSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The one line steward says about bunq at startup, seen both ways.
 *
 * Without the bunq variables every container is healthy and quiet, so this line is the only sign.
 */
class StartLineTest {

    /** The poll the "on" sentence quotes; any value works. */
    private static final Duration POLL = Duration.ofSeconds(30);

    /** A key shaped like a real one, so "the line never contains it" is a real assertion. */
    private static final String API_KEY = "sandbox_1234567890abcdefghijklmnopqrstuvwxyz";

    @Test
    void withAKeyOneInfoLineNamingTheAccountAndThePollAndNeverTheKey() {
        final List<ILoggingEvent> events = capture(() -> new BunqGateway(bunq(API_KEY, "987654")).logStartupLine(POLL));

        assertEquals(1, events.size(), "the start line is one line: " + events);
        final ILoggingEvent line = events.getFirst();
        System.out.println("[start line, bunq ON ] " + line.getLevel() + " " + line.getFormattedMessage());

        assertEquals(Level.INFO, line.getLevel(), "a configured bunq is not a warning - it is the expected state");
        final String text = line.getFormattedMessage();
        assertTrue(text.startsWith("bunq is ON"), "the first three words are what somebody greps for: " + text);
        assertTrue(
                text.contains("987654"),
                "the account id belongs in the line - an id pointing at the wrong account is the"
                        + " other way this goes wrong quietly: " + text);
        assertTrue(text.contains("30s"), "the poll interval belongs in the line: " + text);
        assertFalse(text.contains(API_KEY), "THE API KEY MUST NEVER BE IN A LOG LINE. It was in: " + text);
    }

    @Test
    void withoutAKeyOneWarnLineNamingBothNewVariablesAndTheOldOnes() {
        final List<ILoggingEvent> events = capture(() -> new BunqGateway(bunq("", "")).logStartupLine(POLL));

        assertEquals(1, events.size(), "the start line is one line: " + events);
        final ILoggingEvent line = events.getFirst();
        System.out.println("[start line, bunq OFF] " + line.getLevel() + " " + line.getFormattedMessage());

        assertEquals(
                Level.WARN,
                line.getLevel(),
                "'bunq is OFF' at INFO is a line nobody finds among several hundred at startup,"
                        + " and being found is the entire job of this sentence");
        final String text = line.getFormattedMessage();
        assertTrue(text.startsWith("bunq is OFF"), text);
        assertTrue(
                text.contains("NORDTAL_STEWARD_BUNQ_API_KEY") && text.contains("NORDTAL_STEWARD_BUNQ_ACCOUNT_ID"),
                "the line has to name the variables to set, or it says only that something is" + " missing: " + text);
        assertTrue(
                text.contains("NORDTAL_BOT_BUNQ_"),
                "and the OLD names, because the one deployment that will ever read this line in"
                        + " anger is the one whose environment file still uses them: " + text);
    }

    @Test
    void halfACredentialIsNotHalfOnItIsOffAndTheConfigRefusesItFirst() {
        // Half a credential is unconfigured: a key with no account must not read as on.
        assertFalse(new BunqGateway(bunq(API_KEY, "")).configured());
        assertFalse(new BunqGateway(bunq("", "987654")).configured());
        assertFalse(
                new BunqGateway(bunq("   ", "  ")).configured(),
                "whitespace is not a credential - a blank environment value is unset to jcore");
    }

    private static StewardSpec.BunqSpec bunq(final String apiKey, final String accountId) {
        return new StewardSpec.BunqSpec() {
            @Override
            public String apiKey() {
                return apiKey;
            }

            @Override
            public String accountId() {
                return accountId;
            }
        };
    }

    /** Runs {@code work} with an appender on the root logger and returns what it emitted. */
    private static List<ILoggingEvent> capture(final Runnable work) {
        final List<ILoggingEvent> events = new ArrayList<>();
        final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(final ILoggingEvent event) {
                if (event.getLoggerName().equals(BunqGateway.class.getName())) {
                    events.add(event);
                }
            }
        };

        final ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
        try {
            work.run();
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
        return events;
    }
}
