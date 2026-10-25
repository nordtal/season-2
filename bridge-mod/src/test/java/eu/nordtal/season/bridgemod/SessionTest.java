package eu.nordtal.season.bridgemod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class SessionTest {

    private final List<Request> carriedOut = new ArrayList<>();
    private final Session session = new Session("secret"::equals, request -> {
        if (request instanceof Request.Give) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("give needs creative mode."));
        }
        carriedOut.add(request);
        return CompletableFuture.completedFuture(null);
    });

    private Session.Reply receive(final String frame) {
        return session.receive(frame.replace('\'', '"')).join();
    }

    @Test
    void aHelloWithTheTokenOpensTheSessionForRequests() {
        assertEquals(
                new Session.Reply("{\"id\":1,\"ok\":true}", false), receive("{'id':1,'op':'hello','token':'secret'}"));
        assertEquals(new Session.Reply("{\"id\":\"r\",\"ok\":true}", false), receive("{'id':'r','op':'reload'}"));
        assertEquals(List.of(new Request.Reload()), carriedOut);
    }

    @Test
    void aWrongTokenClosesTheConnectionAndCarriesNothingOut() {
        assertEquals(
                new Session.Reply(
                        "{\"ok\":false,\"error\":\"The token does not match the one in config/nordtal-bridge.properties.\"}",
                        true),
                receive("{'op':'hello','token':'guess'}"));
        assertEquals(true, receive("{'op':'reload'}").close());
        assertEquals(List.of(), carriedOut);
    }

    @Test
    void aRequestBeforeTheHelloClosesTheConnection() {
        assertEquals(
                new Session.Reply("{\"ok\":false,\"error\":\"The first request is a hello with the token.\"}", true),
                receive("{'op':'reload','token':'secret'}"));
        assertEquals(List.of(), carriedOut);
    }

    @Test
    void aWrongOrFailedRequestAnswersWithItsErrorAndKeepsTheSession() {
        receive("{'op':'hello','token':'secret'}");
        assertEquals(
                new Session.Reply("{\"id\":2,\"ok\":false,\"error\":\"chat needs 'text'.\"}", false),
                receive("{'id':2,'op':'chat'}"));
        assertEquals(
                new Session.Reply("{\"id\":3,\"ok\":false,\"error\":\"give needs creative mode.\"}", false),
                receive("{'id':3,'op':'give','item':'minecraft:paper'}"));
        assertEquals(
                new Session.Reply("{\"ok\":false,\"error\":\"A request is a JSON object.\"}", false), receive("[1"));
    }
}
