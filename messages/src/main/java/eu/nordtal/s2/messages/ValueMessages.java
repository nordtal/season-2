package eu.nordtal.s2.messages;

import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Key;
import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.Name;
import eu.nordtal.s2.messages.spec.TextFormat;
import eu.nordtal.s2.messages.value.Example;

/**
 * The words values are shown with, in every process beneath its own bundles.
 * They are each kind's replacement word, a duration's units, a list's last joint, yes and no; declared here, so the
 * build checks them and an admin can change them, and read by key through {@code Words}.
 */
@MessageSpec(value = "values", format = TextFormat.PLAIN)
public interface ValueMessages {

    Values values();

    @Name("Values")
    interface Values {

        Missing missing();

        @Name("Missing value")
        interface Missing {

            @Name("Missing text")
            MessageRef text();

            @Name("Missing number")
            MessageRef number();

            @Name("Missing duration")
            MessageRef duration();

            @Name("Missing time")
            MessageRef instant();

            @Name("Missing amount")
            MessageRef money();

            @Name("Missing list")
            MessageRef list();

            @Name("Missing player")
            MessageRef name();

            @Name("Missing Discord member")
            MessageRef mention();

            @Name("Missing item")
            MessageRef item();

            @Name("Missing glyph")
            MessageRef glyph();

            @Name("Missing choice")
            MessageRef choice();

            @Name("Missing message")
            MessageRef message();
        }

        Duration duration();

        @Name("Duration")
        interface Duration {

            @Name("Days")
            MessageRef days(@Arg("n") @Example("2") long n);

            @Name("Hours")
            MessageRef hours(@Arg("n") @Example("2") long n);

            @Name("Minutes")
            MessageRef minutes(@Arg("n") @Example("2") long n);

            @Name("Seconds")
            MessageRef seconds(@Arg("n") @Example("2") long n);

            @Key("short")
            Brief brief();

            @Name("Short duration")
            interface Brief {

                @Name("Days, short")
                MessageRef days(@Arg("n") @Example("2") long n);

                @Name("Hours, short")
                MessageRef hours(@Arg("n") @Example("2") long n);

                @Name("Minutes, short")
                MessageRef minutes(@Arg("n") @Example("2") long n);

                @Name("Seconds, short")
                MessageRef seconds(@Arg("n") @Example("2") long n);
            }
        }

        List list();

        @Name("List")
        interface List {

            @Name("Joined with and")
            MessageRef and(@Arg("rest") @Example("Alex, Sam") String rest, @Arg("last") @Example("Kim") String last);

            @Name("Joined with or")
            MessageRef or(@Arg("rest") @Example("Alex, Sam") String rest, @Arg("last") @Example("Kim") String last);
        }

        Choice choice();

        @Name("Choice")
        interface Choice {

            @Name("Yes")
            MessageRef yes();

            @Name("No")
            MessageRef no();
        }
    }
}
