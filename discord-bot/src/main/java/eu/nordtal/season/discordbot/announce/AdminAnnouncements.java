package eu.nordtal.season.discordbot.announce;

import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.messages.document.Document;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.LongFunction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;

/** Posts an admin's announcement into the channel each of its languages goes out in, as its document draws it. */
public final class AdminAnnouncements {

    private final LongFunction<Optional<Document>> documents;
    private final BiFunction<Document, String, MessageCreateData> draw;
    private final Announcements channels;
    private final BiConsumer<Long, List<String>> posted;
    private final Logger log;

    /**
     * @param documents reads the document of an announcement by its id
     * @param draw draws a document in one language, by its tag
     * @param posted notes the languages of an announcement that went out
     */
    public AdminAnnouncements(
            final LongFunction<Optional<Document>> documents,
            final BiFunction<Document, String, MessageCreateData> draw,
            final Announcements channels,
            final BiConsumer<Long, List<String>> posted,
            final Logger log) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.draw = Objects.requireNonNull(draw, "draw");
        this.channels = Objects.requireNonNull(channels, "channels");
        this.posted = Objects.requireNonNull(posted, "posted");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** Posts every target of a request and answers per language whether it went out. */
    public BotRequest.PostAnnouncement.Answer post(final BotRequest.PostAnnouncement request) {
        final Optional<Document> document = documents.apply(request.announcement());
        if (document.isEmpty()) {
            log.error("announcement not found, nothing posted: announcement={}", request.announcement());
        }
        final List<BotRequest.Announce.Posted> answers = new ArrayList<>();
        final List<String> out = new ArrayList<>();
        for (final BotRequest.PostAnnouncement.Target target : request.targets()) {
            final boolean done = document.isPresent() && post(document.get(), target);
            answers.add(new BotRequest.Announce.Posted(target.language(), done));
            if (done) {
                out.add(target.language());
            }
        }
        if (!out.isEmpty()) {
            posted.accept(request.announcement(), out);
        }
        return new BotRequest.PostAnnouncement.Answer(answers);
    }

    private boolean post(final Document document, final BotRequest.PostAnnouncement.Target target) {
        final MessageCreateData message;
        try {
            message = draw.apply(document, target.language());
        } catch (final RuntimeException broken) {
            log.error("announcement not drawn: language={}", target.language(), broken);
            return false;
        }
        return channels.post(target.channel(), target.language(), message);
    }
}
