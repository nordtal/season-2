package eu.nordtal.season.limbo;

import eu.nordtal.season.limboprotocol.WaitReason;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.Key;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;

/** Every message of the limbo bundle, one method per key. */
@MessageSpec("limbo")
public interface LimboMessages {

    /** The one shared instance; it is stateless. */
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

            @Name("Server stopped")
            Screen held();

            @Name("Maintenance")
            Screen maintenance();

            @Name("Unknown reason")
            Screen unknown();

            /** The screen for a reason, and the one place a reason becomes a key. */
            default Screen of(final WaitReason reason) {
                return switch (reason) {
                    case PACK -> pack();
                    case BACKEND -> backend();
                    case MAINTENANCE -> maintenance();
                    case UPDATE -> update();
                    case HELD -> held();
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

    Tab tab();

    @Name("Tab list")
    @Shown(Display.TAB_LIST)
    interface Tab {

        @Name("Footer")
        MessageRef footer();
    }
}
