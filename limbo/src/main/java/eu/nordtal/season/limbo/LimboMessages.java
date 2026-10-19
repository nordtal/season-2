package eu.nordtal.season.limbo;

import eu.nordtal.season.limboprotocol.WaitReason;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.Key;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;

/** The messages of the limbo bundle. */
@MessageSpec("limbo")
public interface LimboMessages {

    /** The shared instance. */
    LimboMessages MESSAGES = MessageSpecs.create(LimboMessages.class);

    Limbo limbo();

    @Name("Waiting room")
    interface Limbo {

        @Key("wait")
        Wait waiting();

        @Name("Screens")
        @Shown(Display.TITLE)
        interface Wait {

            @Name("Resource pack")
            Screen pack();

            @Name("Server not up")
            Screen backend();

            @Name("Update")
            Screen update();

            @Name("Update title with progress")
            @Key("update-progress")
            MessageRef updateProgress(@Arg("percent") long percent);

            @Name("Server stopped")
            Screen held();

            @Name("Next in line")
            Screen queue();

            @Name("Maintenance")
            Screen maintenance();

            @Name("Unknown reason")
            Screen unknown();

            /** Returns the screen of the reason. */
            default Screen of(final WaitReason reason) {
                return switch (reason) {
                    case PACK -> pack();
                    case BACKEND -> backend();
                    case MAINTENANCE -> maintenance();
                    case UPDATE -> update();
                    case HELD -> held();
                    case QUEUE -> queue();
                    case UNKNOWN -> unknown();
                };
            }
        }

        interface Screen {

            @Name("Title")
            MessageRef title();

            @Name("Subtitle")
            @Shown(Display.SUBTITLE)
            MessageRef subtitle();
        }
    }
}
