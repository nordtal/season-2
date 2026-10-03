package eu.nordtal.s2.steward.texts;

import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Display;
import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.Name;
import eu.nordtal.s2.messages.spec.TextFormat;
import java.time.Instant;

/**
 * Steward's own texts, English only: the page renders them in the browser and an admin may override any of them.
 * The frontend names a key as a string, which the generated {@code texts.gen.ts} types; the README says why.
 */
@MessageSpec(value = "steward", format = TextFormat.PLAIN, shown = Display.STEWARD)
public interface StewardTexts {

    Steward steward();

    @Name("Steward")
    interface Steward {

        Service service();

        Artifact artifact();

        Image image();

        Run run();

        @Name("Service")
        interface Service {

            @Name("Service state")
            MessageRef state(@Arg("state") ServiceWord state);

            @Name("Docker's word")
            MessageRef dockerState(@Arg("state") String state);

            @Name("Held down")
            MessageRef heldSince(@Arg("since") Instant since, @Arg("state") String state);

            @Name("Failing healthcheck")
            MessageRef unhealthy();

            @Name("No verdict yet")
            MessageRef starting();

            @Name("Healthcheck")
            MessageRef health(@Arg("health") String health);

            @Name("No healthcheck")
            MessageRef noHealth();

            @Name("Not read yet")
            MessageRef notRead();
        }

        @Name("Artifact")
        interface Artifact {

            @Name("Artifact status")
            MessageRef status(@Arg("status") ArtifactStatus status);

            @Name("Artifact status explained")
            MessageRef statusTip(@Arg("status") ArtifactStatus status);
        }

        @Name("Image")
        interface Image {

            @Name("Image drift")
            MessageRef drift(@Arg("drift") ImageResult.State drift);

            @Name("Image drift explained")
            MessageRef driftTip(@Arg("drift") ImageResult.State drift);

            @Name("Image")
            MessageRef label();
        }

        @Name("Run")
        interface Run {

            @Name("Run kind")
            MessageRef kind(@Arg("kind") UpdateKind kind);

            @Name("Run status")
            MessageRef status(@Arg("status") UpdateStatus status);
        }
    }

    /** The words a service's badge and dot say: Docker's own states, and the ones its health is folded into. */
    enum ServiceWord {
        RUNNING,
        HEALTHY,
        STARTING,
        UNHEALTHY,
        HELD,
        STANDBY,
        CREATED,
        RESTARTING,
        REMOVING,
        PAUSED,
        EXITED,
        DEAD
    }

    /** What a source says of one artifact, as the agent's plan names it. */
    enum ArtifactStatus {
        UP_TO_DATE,
        OUTDATED,
        MISSING,
        UNSUPPORTED,
        MOUNT_MISSING,
        UNRESOLVED
    }
}
