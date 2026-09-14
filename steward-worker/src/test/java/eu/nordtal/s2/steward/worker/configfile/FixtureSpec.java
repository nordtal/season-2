package eu.nordtal.s2.steward.worker.configfile;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

import java.util.List;

/**
 * A spec that exists only so jcore writes a file with one of everything in it.
 *
 * <p>The fixtures in this package are <b>written by jcore itself</b>, never typed out by hand
 * where that is avoidable. A parser that claims to read the output of a library has to be tested
 * against the output of that library: every hand-written fixture is a guess at what jcore does,
 * and a guess is exactly the thing that is wrong the day jcore changes. Two of jcore's habits
 * found this way were surprises - see {@link ConfigFilesReadTest}.</p>
 */
@ConfigSpec(header = {
        "A fixture, written by jcore.",
        "",
        "The second paragraph of the header, after a blank line."
})
public interface FixtureSpec {

    @Order(1)
    @Key("port")
    @Comment({
            "A whole number.",
            "",
            "With a blank line in the middle of its comment."
    })
    default int port() {
        return 8080;
    }

    @Order(2)
    @Key("ratio")
    @Comment("A number with a fractional part.")
    default double ratio() {
        return 1.5;
    }

    @Order(3)
    @Key("enabled")
    @Comment("A boolean.")
    default boolean enabled() {
        return true;
    }

    @Order(4)
    @Key("public-url")
    @Comment("A string that needs no quotes.")
    default String publicUrl() {
        return "https://steward.dev.nordtal.eu";
    }

    @Order(5)
    @Key("build-number")
    @Comment("A string that jcore has to quote, because YAML would read it as a number.")
    default String buildNumber() {
        return "12";
    }

    @Order(6)
    @Key("api-token")
    @Comment("A secret, empty in the file because it comes from the environment.")
    default String apiToken() {
        return "";
    }

    @Order(7)
    @Key("stop-services")
    @Comment({
            "A list. Not editable in this alpha, and shown as one anyway.",
            "The worker's backup.stop-services is the real one of these."
    })
    default List<String> stopServices() {
        return List.of("discord-bot", "smp");
    }

    @Order(8)
    @Key("empty-list")
    @Comment("An empty list, which jcore writes as a flow sequence on the key's own line.")
    default List<String> emptyList() {
        return List.of();
    }

    @Order(9)
    @Key("worker")
    @Comment("A nested section.")
    WorkerFixture worker();

    /** A nested section, with a section of its own inside it. */
    @ConfigSpec
    interface WorkerFixture {

        @Order(1)
        @Key("base-url")
        @Comment("Where it is.")
        default String baseUrl() {
            return "http://steward-worker:8082";
        }

        @Order(2)
        @Key("token")
        @Comment("The shared secret.")
        default String token() {
            return "";
        }

        @Order(3)
        @Key("limits")
        @Comment("Two levels down.")
        LimitFixture limits();

        /** The innermost section, to prove the dotted path keeps going. */
        @ConfigSpec
        interface LimitFixture {

            @Order(1)
            @Key("max-retries")
            @Comment("How many times.")
            default int maxRetries() {
                return 3;
            }
        }
    }
}
