package eu.nordtal.season.bridgemod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * One connection's conversation: a hello with the token first, then one reply per request.
 * Every reply echoes the request's `id`. A wrong token or anything but a hello first closes the connection.
 */
final class Session {

    /** What the connection sends back, and whether it closes afterwards. */
    record Reply(String text, boolean close) {}

    private final Predicate<String> tokens;
    private final Function<Request, CompletableFuture<?>> client;
    private boolean authenticated;

    Session(final Predicate<String> tokens, final Function<Request, CompletableFuture<?>> client) {
        this.tokens = tokens;
        this.client = client;
    }

    /** Answers one text frame; the future completes once the client has carried the request out. */
    CompletableFuture<Reply> receive(final String frame) {
        final JsonObject json;
        try {
            final JsonElement parsed = JsonParser.parseString(frame);
            if (!parsed.isJsonObject()) {
                return CompletableFuture.completedFuture(refuse(null, "A request is a JSON object."));
            }
            json = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            return CompletableFuture.completedFuture(refuse(null, "A request is a JSON object."));
        }
        final JsonElement id = json.get("id");
        if (!authenticated) {
            return CompletableFuture.completedFuture(hello(id, json));
        }
        final Request request;
        try {
            request = Request.of(json);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(error(id, String.valueOf(e.getMessage())));
        }
        return client.apply(request)
                .handle((_, failure) ->
                        failure == null ? new Reply(reply(id, true, null), false) : error(id, message(failure)));
    }

    private Reply hello(final @Nullable JsonElement id, final JsonObject json) {
        final JsonElement token = json.get("token");
        if (!"hello".equals(string(json.get("op")))) {
            return refuse(id, "The first request is a hello with the token.");
        }
        if (token == null || !tokens.test(string(token))) {
            return refuse(id, "The token does not match the one in config/" + BridgeSettings.FILE + ".");
        }
        authenticated = true;
        return new Reply(reply(id, true, null), false);
    }

    private Reply refuse(final @Nullable JsonElement id, final String message) {
        return new Reply(reply(id, false, message), !authenticated);
    }

    private static Reply error(final @Nullable JsonElement id, final String message) {
        return new Reply(reply(id, false, message), false);
    }

    private static String reply(final @Nullable JsonElement id, final boolean ok, final @Nullable String error) {
        final JsonObject reply = new JsonObject();
        if (id != null) {
            reply.add("id", id);
        }
        reply.addProperty("ok", ok);
        if (error != null) {
            reply.addProperty("error", error);
        }
        return reply.toString();
    }

    private static String string(final @Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static String message(final Throwable failure) {
        final Throwable cause =
                failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
