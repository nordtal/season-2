package eu.nordtal.season.database.notify;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jdbi.v3.core.statement.SqlStatements;
import org.jdbi.v3.sqlobject.customizer.SqlStatementCustomizer;
import org.jdbi.v3.sqlobject.customizer.SqlStatementCustomizerFactory;
import org.jdbi.v3.sqlobject.customizer.SqlStatementCustomizingAnnotation;

/**
 * Binds {@code :channel} to the name of a {@link Channel} in every statement of the annotated SQL object.
 *
 * A statement then notifies with {@code pg_notify(:channel, '')} and no SQL spells a channel name itself.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SqlStatementCustomizingAnnotation(Notifies.Factory.class)
public @interface Notifies {

    /** The channel the SQL object's statements notify on. */
    Channel value();

    /** Builds the customizer that binds the channel for a whole SQL object type. */
    final class Factory implements SqlStatementCustomizerFactory {

        @Override
        public SqlStatementCustomizer createForType(final Annotation annotation, final Class<?> sqlObjectType) {
            final String name = ((Notifies) annotation).value().sqlName();
            return statement -> {
                // Most statements of the object notify nothing, so they leave the binding unused.
                statement.configure(SqlStatements.class, config -> config.setUnusedBindingAllowed(true));
                statement.bind("channel", name);
            };
        }
    }
}
