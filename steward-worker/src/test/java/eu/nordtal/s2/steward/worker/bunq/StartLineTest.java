package eu.nordtal.s2.steward.worker.bunq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import eu.nordtal.s2.steward.worker.config.StewardSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The one line steward-worker says about bunq at startup, seen both ways.
 *
 * <h2>Why a test and not a deployment</h2>
 * The line exists for exactly one situation: the two variables were renamed from
 * {@code NORDTAL_BOT_BUNQ_*} to {@code NORDTAL_STEWARD_BUNQ_*} in steward/109, and both are
 * deliberately <b>not</b> {@code :?} in {@code compose.yml} because a season without a bank account
 * is a valid season. An environment file that still has the old names therefore produces a stack in
 * which every container is healthy, every log is quiet, and no payment is ever noticed again.
 *
 * <p>Proving that by rolling out would mean rolling out twice - once with a key and once without -
 * on the only machine this project has, and steward/109 forbids deploying at all. So the decision is
 * driven here instead, through the <b>real logging path</b>: {@link BunqGateway#logStartupLine} is
 * what {@code StewardWorker} calls, an appender on the root logger sees what actually came out, and
 * both branches are asserted on the text and on the level. The two sentences are printed to standard
 * output as well, so the test report carries the words themselves rather than a claim about them.</p>
 *
 * <h2>What it cannot prove</h2>
 * That the line is reached at startup. That is one call in {@code StewardWorker#startPayments},
 * unconditional and above the {@code return}, and a test that asserted it would be asserting the
 * shape of a method rather than a behaviour.
 */
class StartLineTest {

    /** The poll the "on" sentence quotes. Any value; 30 is {@code bunq.poll-interval-seconds}. */
    private static final Duration POLL = Duration.ofSeconds(30);

    /** A key shaped like a real one, so "the line never contains it" is a real assertion. */
    private static final String API_KEY = "sandbox_1234567890abcdefghijklmnopqrstuvwxyz";

    @Test
    @DisplayName("with a key: one INFO line naming the account and the poll, and never the key")
    void theOnBranch() {
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
    @DisplayName("without a key: one WARN line naming both new variables and the old ones")
    void theOffBranch() {
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
    @DisplayName("half a credential is not half on - it is off, and the config refuses it first")
    void halfIsNotOn() {
        // Configs.requireBunq refuses this pair before a gateway is ever built, which is why there
        // is no third sentence. What is asserted here is the gateway's own answer if it ever got
        // one anyway: not configured. A gateway that treated a key with no account as "on" would
        // reach Long.parseLong("") in its constructor.
        assertFalse(new BunqGateway(bunq(API_KEY, "")).configured());
        assertFalse(new BunqGateway(bunq("", "987654")).configured());
        assertFalse(
                new BunqGateway(bunq("   ", "  ")).configured(),
                "whitespace is not a credential - a blank environment value is unset to jcore");
    }

    // ---------------------------------------------------------------- plumbing

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
