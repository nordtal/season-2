package eu.nordtal.season.steward.push;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Limits Web Push deliveries to the HTTPS origins of browser push services. */
public final class PushDestination {

    private PushDestination() {}

    public static boolean allowed(final String endpoint) {
        final URI uri;
        try {
            uri = new URI(endpoint);
        } catch (final URISyntaxException invalid) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        final String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("fcm.googleapis.com")
                || host.equals("updates.push.services.mozilla.com")
                || host.endsWith(".push.apple.com")
                || host.endsWith(".notify.windows.com");
    }
}
