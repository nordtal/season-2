package eu.nordtal.season.smp;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;

/** Steward's names for the choices of the SMP's settings. What a player sees are documents. */
@MessageSpec("smp")
@Shown(Display.STEWARD)
public interface SmpMessages {

    SmpMessages MESSAGES = MessageSpecs.create(SmpMessages.class);

    Smp smp();

    @Name("SMP")
    interface Smp {

        Settings settings();

        /** The names that Steward's settings show for the choices of the {@code milestones} group. */
        @Name("Settings")
        @Shown(Display.STEWARD)
        interface Settings {

            Unlock unlock();

            @Name("Unlocks")
            interface Unlock {

                @Name("Border")
                MessageRef border();

                @Name("Nether")
                MessageRef nether();

                @Name("End")
                MessageRef end();

                @Name("Nothing")
                MessageRef nothing();
            }

            ObjectiveType objectiveType();

            @Name("Objective type")
            interface ObjectiveType {

                @Name("Hand-in")
                MessageRef handIn();

                @Name("Statistic")
                MessageRef statistic();

                @Name("Advancement")
                MessageRef advancement();
            }
        }
    }
}
