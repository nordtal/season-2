package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.stewardagent.plan.Work;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An update asks each running service for the secrets its label names, and writes each missing name. */
class MissingSecretsTest {

    private static final long RUN = 725L;

    private final CollectedSteps collected = new CollectedSteps();
    private final Steps record = new Steps(collected, RUN);

    @Test
    void aMissingSecretIsAWarningOnItsServicesResolveStepThatNamesIt() {
        final FakeContainers containers = new FakeContainers()
                .running("steward", "discord-bot", "smp")
                .lacksSecrets("steward", "NORDTAL_STEWARD_WEB_TOTP_KEY");

        final List<Work.Finding> findings = MissingSecrets.of(containers, containers.topology(), List.of(), List.of());
        Resolved.services(record, new Work(Map.of(), findings));

        final RunStep.Row steward = collected.rows().getFirst();
        assertEquals(1, collected.rows().size(), "the bot holds its secret and smp names none");
        assertEquals(RunStep.Kind.RESOLVE, steward.step());
        assertEquals("steward", steward.service());
        assertEquals(RunStep.Outcome.WARNING, steward.outcome());
        assertEquals(RunStep.Reason.MISSING_SECRET, steward.reason());
        assertEquals(List.of(new RunStep.Change("NORDTAL_STEWARD_WEB_TOTP_KEY", null, null, null)), steward.changes());
        assertEquals(
                List.of(
                        "discord-bot:NORDTAL_BOT_TOKEN",
                        "steward:NORDTAL_STEWARD_WEB_DISCORD_CLIENT_SECRET",
                        "steward:NORDTAL_STEWARD_WEB_WEB_PUSH_PRIVATE_KEY",
                        "steward:NORDTAL_STEWARD_WEB_TOTP_KEY"),
                containers.secretsAsked,
                "each service is asked for what its label names, in the order of compose.yml");
    }

    @Test
    void aServiceThatIsStoppedHeldOutOfScopeOrCannotBeAskedNamesNothing() {
        final FakeContainers containers = new FakeContainers()
                .running("discord-bot")
                .lacksSecrets("discord-bot", "NORDTAL_BOT_TOKEN")
                .lacksSecrets("steward", "NORDTAL_STEWARD_WEB_TOTP_KEY");

        assertEquals(
                List.of(), MissingSecrets.of(containers, containers.topology(), List.of(), List.of("discord-bot")));
        assertEquals(List.of(), MissingSecrets.of(containers, containers.topology(), List.of("smp"), List.of()));
        containers.secretsUnasked("discord-bot");
        assertEquals(List.of(), MissingSecrets.of(containers, containers.topology(), List.of(), List.of()));
        assertEquals(
                List.of(),
                containers.secretsAsked.stream()
                        .filter(asked -> asked.startsWith("steward:"))
                        .toList(),
                "a stopped steward is not asked");
    }
}
