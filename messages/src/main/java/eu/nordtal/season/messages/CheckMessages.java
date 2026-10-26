package eu.nordtal.season.messages;

import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.value.Example;
import java.util.List;

/**
 * The problems that {@code MessageCheck} reports for a text, in English only.
 * The validator may depend on no other bundle, so this bundle lives beside it. Steward serves it
 * with its own bundles, so an admin can change it.
 */
@MessageSpec(value = "check", format = TextFormat.PLAIN)
@Shown(Display.STEWARD)
public interface CheckMessages {

    CheckMessages TEXTS = MessageSpecs.create(CheckMessages.class);

    Check check();

    @Name("Text check")
    interface Check {

        Syntax syntax();

        /** {@code at} counts characters from 1. */
        @Name("Unreadable text")
        interface Syntax {

            @Name("A closing brace closes nothing")
            MessageRef closesNothing(@Arg("at") @Example("14") int at);

            @Name("A case is never closed")
            MessageRef caseNeverClosed(@Arg("at") @Example("14") int at);

            @Name("An opening brace opens nothing")
            MessageRef opensNothing(@Arg("at") @Example("14") int at);

            @Name("A placeholder misses its kind")
            MessageRef kindMissing(@Arg("name") @Example("count") String name, @Arg("at") @Example("14") int at);

            @Name("A placeholder misses its style")
            MessageRef styleMissing(
                    @Arg("name") @Example("count") String name,
                    @Arg("kind") @Example("number") String kind,
                    @Arg("at") @Example("14") int at);

            @Name("A plural or select is never closed")
            MessageRef choiceNeverClosed(@Arg("name") @Example("count") String name, @Arg("at") @Example("14") int at);

            @Name("A case has no name")
            MessageRef caseUnnamed(@Arg("name") @Example("count") String name, @Arg("at") @Example("14") int at);

            @Name("A case is written twice")
            MessageRef caseTwice(
                    @Arg("name") @Example("count") String name,
                    @Arg("case") @Example("one") String key,
                    @Arg("at") @Example("14") int at);

            @Name("A plural or select misses its other case")
            MessageRef otherMissing(@Arg("name") @Example("count") String name, @Arg("at") @Example("14") int at);

            @Name("A plural or select stands in a tag's argument")
            MessageRef choiceInArgument(@Arg("at") @Example("14") int at);

            @Name("A placeholder ends on its dot")
            MessageRef attributeMissing(@Arg("name") @Example("player") String name, @Arg("at") @Example("14") int at);

            @Name("A comma is missing after the name")
            MessageRef commaAfterName(@Arg("at") @Example("14") int at);

            @Name("A comma is missing before the cases")
            MessageRef commaBeforeCases(@Arg("at") @Example("14") int at);

            @Name("A placeholder is not closed")
            MessageRef valueNotClosed(@Arg("name") @Example("count") String name, @Arg("at") @Example("14") int at);

            @Name("A case is not opened")
            MessageRef caseNotOpened(@Arg("case") @Example("one") String key, @Arg("at") @Example("14") int at);

            @Name("A building block without a name")
            MessageRef blockUnnamed(@Arg("at") @Example("14") int at);

            @Name("A value of a building block bound halfway")
            MessageRef bindingIncomplete(
                    @Arg("block") @Example("values.duration.days") String block, @Arg("at") @Example("14") int at);

            @Name("A value of a building block bound twice")
            MessageRef bindingTwice(
                    @Arg("block") @Example("values.duration.days") String block,
                    @Arg("name") @Example("n") String name,
                    @Arg("at") @Example("14") int at);

            @Name("A building block is not closed")
            MessageRef blockNotClosed(
                    @Arg("block") @Example("values.duration.days") String block, @Arg("at") @Example("14") int at);

            @Name("A building block stands in a tag's argument")
            MessageRef blockInArgument(@Arg("at") @Example("14") int at);
        }

        Value value();

        @Name("Placeholders")
        interface Value {

            @Name("Nothing the message offers")
            MessageRef unknown(
                    @Arg("name") @Example("name") String name,
                    @Arg("offered") @Example("count,player") List<String> offered);

            @Name("No such kind")
            MessageRef noKind(
                    @Arg("name") @Example("count") String name,
                    @Arg("kind") @Example("amount") String kind,
                    @Arg("kinds") @Example("number,duration,money") List<String> kinds);

            @Name("Another kind")
            MessageRef otherKind(
                    @Arg("name") @Example("name") String name,
                    @Arg("kind") @Example("text") String kind,
                    @Arg("written") @Example("number") String written);

            @Name("No such style")
            MessageRef noStyle(
                    @Arg("name") @Example("count") String name,
                    @Arg("style") @Example("long") String style,
                    @Arg("kind") @Example("number") String kind,
                    @Arg("styles") @Example("plain") List<String> styles);

            @Name("A kind without styles")
            MessageRef styleless(
                    @Arg("name") @Example("name") String name,
                    @Arg("style") @Example("plain") String style,
                    @Arg("kind") @Example("text") String kind);

            @Name("A plural on something else")
            MessageRef pluralOn(@Arg("name") @Example("name") String name, @Arg("kind") @Example("text") String kind);

            @Name("A select on something else")
            MessageRef selectOn(
                    @Arg("name") @Example("count") String name, @Arg("kind") @Example("number") String kind);

            @Name("No plural category")
            MessageRef pluralCase(
                    @Arg("name") @Example("count") String name,
                    @Arg("case") @Example("several") String key,
                    @Arg("cases") @Example("zero,one,two,few,many,other") List<String> cases);

            @Name("A value never shown")
            MessageRef unshown(@Arg("role") @Example("player") String role);
        }

        Block block();

        @Name("Building blocks")
        interface Block {

            @Name("No such building block")
            MessageRef unknown(@Arg("block") @Example("values.duration.weeks") String block);

            @Name("A value the building block lacks")
            MessageRef noValue(
                    @Arg("block") @Example("values.duration.days") String block,
                    @Arg("name") @Example("count") String name,
                    @Arg("offered") @Example("n") List<String> offered);

            @Name("A value the building block needs")
            MessageRef unbound(
                    @Arg("block") @Example("values.duration.days") String block,
                    @Arg("name") @Example("n") String name,
                    @Arg("offered") @Example("days,player") List<String> offered);

            @Name("A value of another kind")
            MessageRef otherKind(
                    @Arg("block") @Example("values.duration.days") String block,
                    @Arg("name") @Example("n") String name,
                    @Arg("kind") @Example("number") String kind,
                    @Arg("given") @Example("text") String given);
        }

        Tag tag();

        @Name("Tags")
        interface Tag {

            @Name("A tag never closed")
            MessageRef neverClosed(@Arg("tag") @Example("bold") String tag);

            @Name("A colour in a packaged text")
            MessageRef packagedColour(
                    @Arg("tag") @Example("red") String tag,
                    @Arg("tones") @Example("neutral,good,bad,warn") List<String> tones);

            @Name("No such tag")
            MessageRef unknown(@Arg("tag") @Example("blink") String tag);

            @Name("No such action")
            MessageRef unknownAction(
                    @Arg("action") @Example("open") String action,
                    @Arg("actions") @Example("join,leave") List<String> actions);

            @Name("A value in an argument that takes none")
            MessageRef valueInArgument(
                    @Arg("name") @Example("name") String name, @Arg("tag") @Example("click") String tag);

            @Name("A value in an argument without quotes")
            MessageRef unquotedValue(
                    @Arg("name") @Example("url") String name, @Arg("tag") @Example("click") String tag);

            @Name("A closing tag closes nothing")
            MessageRef closesNothing(@Arg("tag") @Example("bold") String tag);

            @Name("A link shows its address")
            MessageRef linkShowsAddress(@Arg("name") @Example("url") String name);

            @Name("A line break on one line")
            MessageRef lineBreakOnOneLine(@Arg("tag") @Example("newline") String tag);

            @Name("A closing tag closes the wrong one")
            MessageRef closesOther(
                    @Arg("tag") @Example("bold") String tag, @Arg("open") @Example("italic") String open);
        }

        @Name("Another word for a game term")
        MessageRef synonym(
                @Arg("word") @Example("membership") String word, @Arg("term") @Example("access") String term);

        @Name("Too long")
        MessageRef tooLong(@Arg("length") @Example("64") int length, @Arg("limit") @Example("48") int limit);
    }
}
