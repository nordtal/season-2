package eu.nordtal.season.discordbot;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.discordrendering.DiscordRenderer;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.Messages;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class StoredDocumentsStandIn {

    private StoredDocumentsStandIn() {}

    /** Returns a renderer of the packaged documents in English and German, with the bot's bundles. */
    public static DocumentRenderer renderer() {
        return renderer(none());
    }

    public static DocumentRenderer renderer(final StoredDocuments documents) {
        return new DocumentRenderer(
                DiscordRenderer.of(Messages.load(
                        StoredDocumentsStandIn.class.getClassLoader(),
                        DocumentRenderer.BUNDLES,
                        Locale.ENGLISH,
                        Locale.GERMAN)),
                documents);
    }

    public static StoredDocuments none() {
        return holding(List.of(), List.of());
    }

    public static StoredDocuments holding(
            final List<DocumentStore.StoredDocument> written, final List<DocumentStore.Image> images) {
        final StoredDocuments documents = new StoredDocuments(new DocumentStore() {
            @Override
            public List<StoredDocument> documents(final Collection<String> namespaces) {
                return written;
            }

            @Override
            public void change(final StoredDocument document, final Actor actor) {
                throw new UnsupportedOperationException("the stand-in is read only");
            }

            @Override
            public void remove(final String namespace, final String name) {
                throw new UnsupportedOperationException("the stand-in is read only");
            }

            @Override
            public long upload(final String mediaType, final byte[] bytes, final Actor actor) {
                throw new UnsupportedOperationException("the stand-in is read only");
            }

            @Override
            public Optional<Image> image(final long id) {
                return images.stream().filter(image -> image.id() == id).findFirst();
            }
        });
        documents.hold(written);
        return documents;
    }
}
