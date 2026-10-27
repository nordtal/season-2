package eu.nordtal.season.database;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.messages.document.Document;
import java.sql.Types;
import java.util.UUID;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.spi.JdbiPlugin;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/** Sets up a Jdbi with SQL objects, the PostgreSQL types, the two id types and a document as columns. */
public final class Jdbis {

    private Jdbis() {}

    public static Jdbi over(final DataSource dataSource) {
        return Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .installPlugin(ids());
    }

    /** Returns the plugin that binds a {@link DiscordId} as text, a {@link PlayerId} as a UUID, a document as jsonb. */
    public static JdbiPlugin ids() {
        return new JdbiPlugin() {
            @Override
            public void customizeJdbi(final Jdbi jdbi) {
                jdbi.registerArgument(new org.jdbi.v3.core.argument.AbstractArgumentFactory<DiscordId>(Types.VARCHAR) {
                            @Override
                            protected org.jdbi.v3.core.argument.Argument build(
                                    final DiscordId value, final org.jdbi.v3.core.config.ConfigRegistry config) {
                                return (position, statement, context) -> statement.setString(position, value.value());
                            }
                        })
                        .registerArgument(new org.jdbi.v3.core.argument.AbstractArgumentFactory<PlayerId>(Types.OTHER) {
                            @Override
                            protected org.jdbi.v3.core.argument.Argument build(
                                    final PlayerId value, final org.jdbi.v3.core.config.ConfigRegistry config) {
                                return (position, statement, context) -> statement.setObject(position, value.value());
                            }
                        })
                        .registerArgument(new org.jdbi.v3.core.argument.AbstractArgumentFactory<Document>(Types.OTHER) {
                            @Override
                            protected org.jdbi.v3.core.argument.Argument build(
                                    final Document value, final org.jdbi.v3.core.config.ConfigRegistry config) {
                                return (position, statement, context) ->
                                        statement.setObject(position, value.json(), Types.OTHER);
                            }
                        })
                        .registerColumnMapper(Document.class, (results, column, context) -> {
                            final String json = results.getString(column);
                            return json == null ? null : Document.decode(json);
                        })
                        .registerColumnMapper(DiscordId.class, (results, column, context) -> {
                            final String text = results.getString(column);
                            return text == null ? null : DiscordId.of(text);
                        })
                        .registerColumnMapper(PlayerId.class, (results, column, context) -> {
                            final UUID uuid = results.getObject(column, UUID.class);
                            return uuid == null ? null : PlayerId.of(uuid);
                        });
            }
        };
    }
}
