package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.Name;

/** The season phase: what it is now, changing it, and the dates that drive it. */
@Name("Phase")
public interface Phase {

    @Name("Current")
    MessageRef current(@Arg("phase") Object phase);

    @Name("Dates")
    MessageRef dates(@Arg("launch") Object launch, @Arg("smpStart") Object smpStart, @Arg("zone") Object zone);

    @Name("Unknown")
    MessageRef unknown(@Arg("value") Object value, @Arg("phases") Object phases);

    @Name("Changed")
    MessageRef changed(@Arg("previous") Object previous, @Arg("current") Object current);

    @Name("Unchanged")
    MessageRef unchanged(@Arg("phase") Object phase);

    @Name("Failed")
    MessageRef failed();

    @Name("Confirm")
    MessageRef confirm(
            @Arg("previous") Object previous, @Arg("current") Object current, @Arg("consequence") Object consequence);

    @Key("current")
    Current currentSection();

    @Name("Current")
    interface Current {

        @Name("Unread")
        MessageRef unread(@Arg("phase") Object phase);
    }

    Date date();

    @Name("Date")
    interface Date {

        @Name("Unset")
        MessageRef unset();

        @Name("Confirm")
        MessageRef confirm(@Arg("what") Object what, @Arg("when") Object when);

        @Name("Invalid")
        MessageRef invalid(@Arg("pattern") Object pattern, @Arg("zone") Object zone, @Arg("clear") Object clear);

        @Name("Set")
        MessageRef set(@Arg("what") Object what, @Arg("current") Object current, @Arg("previous") Object previous);

        @Name("Unchanged")
        MessageRef unchanged(@Arg("what") Object what, @Arg("current") Object current);

        @Name("Cleared")
        MessageRef cleared(@Arg("what") Object what);

        @Name("Moved")
        MessageRef moved(@Arg("grants") Object grants, @Arg("accounts") Object accounts);

        @Name("None moved")
        MessageRef noneMoved();

        @Name("Kept")
        MessageRef kept();

        @Name("Refused")
        MessageRef refused(@Arg("reason") Object reason);

        @Name("Failed")
        MessageRef failed();

        @Key("moved")
        Moved movedSection();

        @Name("Moved")
        interface Moved {

            @Name("One")
            MessageRef one();

            @Name("One account")
            MessageRef oneAccount(@Arg("grants") Object grants);
        }

        What what();

        @Name("What")
        interface What {

            @Name("Launch")
            MessageRef launch();

            @Name("SMP start")
            MessageRef smpStart();
        }
    }

    Read read();

    @Name("Read")
    interface Read {

        @Name("Failed")
        MessageRef failed();

        @Key("failed")
        Failed failedSection();

        @Name("Failed")
        interface Failed {

            @Name("Only")
            MessageRef only();
        }
    }

    @Key("confirm")
    Confirm confirmSection();

    @Name("Confirm")
    interface Confirm {

        @Name("Same")
        MessageRef same(@Arg("current") Object current, @Arg("consequence") Object consequence);
    }

    Consequence consequence();

    @Name("Consequence")
    interface Consequence {

        @Name("Pre launch")
        @Key("PRE_LAUNCH")
        MessageRef preLaunch();

        @Name("Pre event")
        @Key("PRE_EVENT")
        MessageRef preEvent();

        @Name("Start event")
        @Key("START_EVENT")
        MessageRef startEvent();

        @Name("SMP")
        @Key("SMP")
        MessageRef smp();

        @Name("Maintenance")
        @Key("MAINTENANCE")
        MessageRef maintenance();
    }
}
