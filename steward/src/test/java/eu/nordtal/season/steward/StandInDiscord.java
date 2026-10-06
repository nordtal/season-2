package eu.nordtal.season.steward;

import com.google.gson.Gson;
import eu.nordtal.season.steward.config.WebSpec;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Discord's three OAuth routes, answering for one guild member whom a test or the preview chooses.
 *
 * The one system boundary in the sign-in; everything else in the flow is the interface's own code.
 */
public final class StandInDiscord implements AutoCloseable {

    public static final String GUILD = "1234";

    /** The only code the token route accepts. */
    public static final String CODE = "the-code";

    /** Who is signing in. */
    public final AtomicReference<String> memberId = new AtomicReference<>("1");

    public final AtomicReference<String> memberNick = new AtomicReference<>("Ally");

    /** The account {@link #spec()} names as the one that may claim an empty admin tree; blank for none. */
    public final AtomicReference<String> rootId = new AtomicReference<>("1");

    /** The client secret as it arrived at the token route, or null if it never did. */
    public final AtomicReference<String> secretSeen = new AtomicReference<>();

    private @Nullable Javalin server;

    /** Starts on {@code port}, or any free one for {@code 0}, and returns the base URL the interface calls. */
    public String start(final int port) {
        final Javalin started = Javalin.create(cfg -> {
                    cfg.jsonMapper(new JavalinGson(new Gson(), true));
                    cfg.startup.showJavalinBanner = false;
                    cfg.routes.post("/oauth2/token", ctx -> {
                        final Map<String, String> form = form(ctx.body());
                        secretSeen.set(form.get("client_secret"));
                        if (!CODE.equals(form.get("code"))) {
                            ctx.status(400).json(Map.of("error", "invalid_grant"));
                            return;
                        }
                        ctx.json(Map.of("access_token", "an-access-token", "token_type", "Bearer"));
                    });
                    cfg.routes.get("/users/@me", ctx -> ctx.json(Map.of("id", memberId.get(), "username", "ally")));
                    cfg.routes.get("/users/@me/guilds/{guild}/member", ctx -> {
                        if (!GUILD.equals(ctx.pathParam("guild"))) {
                            ctx.status(404).json(Map.of("message", "Unknown Guild"));
                            return;
                        }
                        ctx.json(Map.of("nick", memberNick.get(), "roles", List.of("9999")));
                    });
                })
                .start(port);
        server = started;
        return "http://127.0.0.1:" + started.port();
    }

    /** The interface's Discord settings for this stand-in, read live. */
    public WebSpec.DiscordSpec spec() {
        return new WebSpec.DiscordSpec() {
            @Override
            public String clientId() {
                return "an-application";
            }

            @Override
            public String clientSecret() {
                return "a-client-secret";
            }

            @Override
            public String guildId() {
                return GUILD;
            }

            @Override
            public String rootId() {
                return rootId.get();
            }
        };
    }

    /** Makes {@code member} a guild member and an admin below {@code granter}, as the bot's sync and a grant would. */
    public static void admitBelow(final DataSource dataSource, final String member, final String granter)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement admit = connection.prepareStatement("""
                     INSERT INTO discord_user (discord_id, member_state, admin, admin_granted_by,
                                               admin_granted_at, updated)
                     VALUES (?, 'MEMBER', true, ?, now(), now())
                     ON CONFLICT (discord_id) DO UPDATE
                         SET member_state = 'MEMBER', admin = true, admin_granted_by = excluded.admin_granted_by,
                             admin_granted_at = now(), updated = now()
                     """)) {
            admit.setString(1, member);
            admit.setString(2, granter);
            admit.executeUpdate();
        }
    }

    private static Map<String, String> form(final String raw) {
        final Map<String, String> form = new LinkedHashMap<>();
        for (final String pair : raw.split("&", -1)) {
            final int equals = pair.indexOf('=');
            if (equals > 0) {
                form.put(
                        pair.substring(0, equals),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop();
        }
    }
}
