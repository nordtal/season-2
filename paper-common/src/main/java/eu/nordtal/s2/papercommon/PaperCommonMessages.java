package eu.nordtal.s2.papercommon;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.context.PlayerContext;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Display;
import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.MessageSpecs;
import eu.nordtal.s2.messages.spec.Name;
import eu.nordtal.s2.messages.spec.Shown;
import eu.nordtal.s2.messages.value.Example;
import eu.nordtal.s2.messages.value.GameContent;

/** Every message of the paper-common bundle, one method per key. */
@MessageSpec("paper-common")
public interface PaperCommonMessages {

    /** The messages; stateless, so one instance serves every caller. */
    PaperCommonMessages MESSAGES = MessageSpecs.create(PaperCommonMessages.class);

    SystemMessages system();

    Login login();

    Tab tab();

    @Name("Tab list")
    @Shown(Display.TAB_LIST)
    interface Tab {

        /** The one header of every server, since the client keeps it across a server change. */
        @Name("Header")
        MessageRef header();
    }

    CommandMessages command();

    @Name("Command")
    interface CommandMessages {

        @Name("Unknown")
        MessageRef unknown();

        Help help();

        @Name("Help")
        interface Help {

            @Name("Header")
            MessageRef header(@Arg("command") String command);

            @Name("Line")
            MessageRef line(@Arg("usage") String usage, @Arg("what") String what);

            @Name("Usage")
            MessageRef usage(@Arg("usage") String usage);

            @Name("What")
            MessageRef what(@Arg("what") String what);
        }
    }

    Admin admin();

    @Name("Admin")
    interface Admin {

        @Name("Confirm")
        MessageRef confirm(@Arg("command") String command);

        @Name("Failed")
        MessageRef failed();
    }

    @Name("Login")
    interface Login {

        @Name("Database unreachable")
        MessageRef databaseUnreachable();
    }

    @Name("System")
    interface SystemMessages {

        @Name("Join")
        MessageRef join(@Arg("player") PlayerContext player);

        @Name("Leave")
        MessageRef leave(@Arg("player") PlayerContext player);

        /** The game's own death line, so each client reads it in its own language. */
        @Name("Death")
        MessageRef death(@Arg("death") @Example("death.attack.generic") GameContent death);

        @Name("Advancement")
        MessageRef advancement(
                @Arg("player") PlayerContext player,
                @Arg("advancement") @Example("advancements.story.mine_diamond.title") GameContent advancement);

        Chat chat();

        @Name("Chat")
        interface Chat {

            @Name("Line")
            MessageRef line(@Arg("sender") PlayerContext sender, @Arg("message") @Example("Hello!") String message);
        }
    }
}
