package xyz.growaction.javacore.persistence.mariadb;

import lombok.Builder;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.jetbrains.annotations.NotNull;

/**
 * This record is used to construct {@link SessionFactory} objects for specific entity types using a JDBC URI
 *
 * @param uri the database uri for the {@link SessionFactory}
 * @param username the database username for the {@link SessionFactory}
 * @param password the database password for the {@link SessionFactory}
 * @param entityClass the entity class type to add to the {@link SessionFactory}
 */
public record MariaDbSessionFactoryConstructor<T>(@NotNull String uri, @NotNull String username, @NotNull String password, @NotNull Class<T> entityClass) {

    private static final String HIBERNATE_URL_PROPERTY = "hibernate.connection.url";
    private static final String HIBERNATE_USERNAME_PROPERTY = "hibernate.connection.username";
    private static final String HIBERNATE_PASSWORD_PROPERTY = "hibernate.connection.password";

    /**
     * Constructs a new {@link SessionFactory} object
     *
     * @return the constructed {@link SessionFactory} object
     */
    public SessionFactory construct() {
        final Configuration configuration = new Configuration();

        configuration.setProperty(HIBERNATE_URL_PROPERTY, uri());
        configuration.setProperty(HIBERNATE_USERNAME_PROPERTY, username());
        configuration.setProperty(HIBERNATE_PASSWORD_PROPERTY, password());
        configuration.setProperty("hibernate.hbm2ddl.auto", "update");
        configuration.setProperty("hibernate.show_sql", "true");

        configuration.addAnnotatedClass(entityClass);

        return configuration.buildSessionFactory();
    }
}
