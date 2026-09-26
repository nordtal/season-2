package eu.nordtal.s2.limbo;

import eu.nordtal.s2.common.limbo.WaitReason;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Display;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.MessageSpec;
import eu.nordtal.s2.common.message.spec.MessageSpecs;
import eu.nordtal.s2.common.message.spec.Name;
import eu.nordtal.s2.common.message.spec.Shown;

/**
 * Every message of the limbo bundle, one method per key.
 *
 * The bundle is the whole interface of the waiting room: one title and one subtitle per reason to
 * wait, and the tab list.
 */
@MessageSpec("limbo")
public interface LimboMessages {

    /** The messages; stateless, so one instance serves every caller. */
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

        @Name("Header")
        MessageRef header(@Arg("logo") Object logo);

        @Name("Footer")
        MessageRef footer();
    }
}
