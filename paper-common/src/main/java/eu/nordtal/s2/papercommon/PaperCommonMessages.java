package eu.nordtal.s2.papercommon;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.MessageSpec;
import eu.nordtal.s2.common.message.spec.MessageSpecs;
import eu.nordtal.s2.common.message.spec.Name;
import net.kyori.adventure.text.Component;

/**
 * Every message of the paper-common bundle, one method per key.
 */
@MessageSpec("paper-common")
public interface PaperCommonMessages {

    /** The messages; stateless, so one instance serves every caller. */
    PaperCommonMessages MESSAGES = MessageSpecs.create(PaperCommonMessages.class);

    SystemMessages system();

    @Name("System")
    interface SystemMessages {

        @Name("Join")
        MessageRef join(@Arg("icon") Object icon, @Arg("_player") Component player);

        @Name("Leave")
        MessageRef leave(@Arg("icon") Object icon, @Arg("_player") Component player);

        @Name("Death")
        MessageRef death(@Arg("icon") Object icon, @Arg("_death") Component death);

        @Name("Advancement")
        MessageRef advancement(@Arg("icon") Object icon, @Arg("_player") Component player, @Arg("_advancement") Component advancement);

        Chat chat();

        @Name("Chat")
        interface Chat {

            @Name("Line")
            MessageRef line(@Arg("_sender") Component sender, @Arg("separator") Object separator, @Arg("_message") Component message);
        }
    }
}
