package eu.nordtal.s2.messages;

import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.MessageSpecs;
import eu.nordtal.s2.messages.spec.Name;
import eu.nordtal.s2.messages.spec.TextFormat;
import java.util.List;

/**
 * What the one validator says is wrong with a text, in English only.
 * An admin reads it in Steward, a developer in the build's refusal and a log. It lives beside the validator, which may
 * depend on no other bundle; Steward serves it with its own, so an admin can change it.
 */
@MessageSpec(value = "check", format = TextFormat.PLAIN)
public interface CheckMessages {

    /** The texts; stateless, so one instance serves every caller. */
    CheckMessages TEXTS = MessageSpecs.create(CheckMessages.class);

    Check check();

    @Name("Text check")
    interface Check {

        Syntax syntax();

        /** A text that cannot be read at all; {@code at} counts characters from 1. */
        @Name("Unreadable text")
        interface Syntax {

            @Name("A closing brace closes nothing")
            MessageRef closesNothing(@Arg("at") int at);

            @Name("A case is never closed")
            MessageRef caseNeverClosed(@Arg("at") int at);

            @Name("An opening brace opens nothing")
            MessageRef opensNothing(@Arg("at") int at);

            @Name("A placeholder misses its kind")
            MessageRef kindMissing(@Arg("name") String name, @Arg("at") int at);

            @Name("A placeholder misses its style")
            MessageRef styleMissing(@Arg("name") String name, @Arg("kind") String kind, @Arg("at") int at);

            @Name("A plural or select is never closed")
            MessageRef choiceNeverClosed(@Arg("name") String name, @Arg("at") int at);

            @Name("A case has no name")
            MessageRef caseUnnamed(@Arg("name") String name, @Arg("at") int at);

            @Name("A case is written twice")
            MessageRef caseTwice(@Arg("name") String name, @Arg("case") String key, @Arg("at") int at);

            @Name("A plural or select misses its other case")
            MessageRef otherMissing(@Arg("name") String name, @Arg("at") int at);

            @Name("A plural or select stands in a tag's argument")
            MessageRef choiceInArgument(@Arg("at") int at);

            @Name("A placeholder ends on its dot")
            MessageRef attributeMissing(@Arg("name") String name, @Arg("at") int at);

            @Name("A comma is missing after the name")
            MessageRef commaAfterName(@Arg("at") int at);

            @Name("A comma is missing before the cases")
            MessageRef commaBeforeCases(@Arg("at") int at);

            @Name("A placeholder is not closed")
            MessageRef valueNotClosed(@Arg("name") String name, @Arg("at") int at);

            @Name("A case is not opened")
            MessageRef caseNotOpened(@Arg("case") String key, @Arg("at") int at);
        }

        Value value();

        @Name("Placeholders")
        interface Value {

            @Name("Nothing the message offers")
            MessageRef unknown(@Arg("name") String name, @Arg("offered") List<String> offered);

            @Name("No such kind")
            MessageRef noKind(@Arg("name") String name, @Arg("kind") String kind, @Arg("kinds") List<String> kinds);

            @Name("Another kind")
            MessageRef otherKind(@Arg("name") String name, @Arg("kind") String kind, @Arg("written") String written);

            @Name("No such style")
            MessageRef noStyle(
                    @Arg("name") String name,
                    @Arg("style") String style,
                    @Arg("kind") String kind,
                    @Arg("styles") List<String> styles);

            @Name("A kind without styles")
            MessageRef styleless(@Arg("name") String name, @Arg("style") String style, @Arg("kind") String kind);

            @Name("A plural on something else")
            MessageRef pluralOn(@Arg("name") String name, @Arg("kind") String kind);

            @Name("A select on something else")
            MessageRef selectOn(@Arg("name") String name, @Arg("kind") String kind);

            @Name("No plural category")
            MessageRef pluralCase(@Arg("name") String name, @Arg("case") String key, @Arg("cases") List<String> cases);

            @Name("A value never shown")
            MessageRef unshown(@Arg("role") String role);
        }

        Tag tag();

        @Name("Tags")
        interface Tag {

            @Name("A tag never closed")
            MessageRef neverClosed(@Arg("tag") String tag);

            @Name("A colour in a packaged text")
            MessageRef packagedColour(@Arg("tag") String tag, @Arg("tones") List<String> tones);

            @Name("No such tag")
            MessageRef unknown(@Arg("tag") String tag);

            @Name("No such action")
            MessageRef unknownAction(@Arg("action") String action, @Arg("actions") List<String> actions);

            @Name("A value in an argument that takes none")
            MessageRef valueInArgument(@Arg("name") String name, @Arg("tag") String tag);

            @Name("A value in an argument without quotes")
            MessageRef unquotedValue(@Arg("name") String name, @Arg("tag") String tag);

            @Name("A closing tag closes nothing")
            MessageRef closesNothing(@Arg("tag") String tag);

            @Name("A closing tag closes the wrong one")
            MessageRef closesOther(@Arg("tag") String tag, @Arg("open") String open);
        }

        @Name("Too long")
        MessageRef tooLong(@Arg("length") int length, @Arg("limit") int limit);
    }
}
