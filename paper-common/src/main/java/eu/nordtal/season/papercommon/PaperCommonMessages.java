package eu.nordtal.season.papercommon;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.GameContent;

/** The messages of the paper-common bundle. */
@MessageSpec("paper-common")
@Shown(Display.CHAT)
public interface PaperCommonMessages {

    PaperCommonMessages MESSAGES = MessageSpecs.create(PaperCommonMessages.class);

    SystemMessages system();

    Login login();

    Tab tab();

    @Name("Tab list")
    @Shown(Display.TAB_LIST)
    interface Tab {

        @Name("Header")
        MessageRef header();

        @Name("Footer")
        MessageRef footer(@Arg("online") int online, @Arg("max") int max);
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
        @Shown({Display.CHAT, Display.STEWARD})
        MessageRef failed();

        @Name("Preview shown")
        @Shown(Display.STEWARD)
        MessageRef previewShown();
    }

    @Name("Login")
    interface Login {

        @Name("Database unreachable")
        @Shown(Display.KICK_SCREEN)
        MessageRef databaseUnreachable();
    }

    @Name("System")
    interface SystemMessages {

        @Name("Join")
        MessageRef join(@Arg("player") PlayerContext player);

        @Name("Leave")
        MessageRef leave(@Arg("player") PlayerContext player);

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
