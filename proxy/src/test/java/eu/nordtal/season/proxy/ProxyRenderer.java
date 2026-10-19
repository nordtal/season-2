package eu.nordtal.season.proxy;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import java.util.Locale;
import java.util.Set;

/** The renderer of the proxy's packaged documents in English and German, as the tests read them. */
public final class ProxyRenderer {

    public static final MessageRenderer RENDERER = MessageRenderer.of(
                    Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN))
            .reading(new AppliedDocuments(ProxyRenderer.class.getClassLoader(), Set.of("proxy")));

    private ProxyRenderer() {}
}
