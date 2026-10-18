package eu.nordtal.season.steward.settings;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.inbox.SettingsChange;
import org.jspecify.annotations.Nullable;

/** Asks the running server of a service whether it would start with a settings change, before Steward saves it. */
@FunctionalInterface
public interface ServerTrial {

    /** Asks no server, as without a database. */
    ServerTrial NONE = (service, change, actor) -> Verdict.UNASKED;

    Verdict ask(String service, SettingsChange change, Actor actor);

    /** @param refusal the server's reason in words, set only for {@link Kind#REFUSED} */
    record Verdict(Kind kind, @Nullable String refusal) {

        static final Verdict UNASKED = new Verdict(Kind.UNASKED, null);

        /** {@code UNASKED} is a service without a server to ask; {@code UNANSWERED} one whose server did not answer. */
        public enum Kind {
            TAKEN,
            REFUSED,
            UNANSWERED,
            UNASKED
        }
    }
}
