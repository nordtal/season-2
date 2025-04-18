package xyz.growaction.javacore.persistence.mariadb;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import lombok.AccessLevel;
import lombok.Getter;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.growaction.javacore.persistence.common.EntityRepository;

import java.util.List;

/**
 * This class implements abstract database actions for the MariaDB entities of this project
 *
 * @param <T> the class type of the entity the repository is handling
 */
public abstract class MariaDbRepository<T> implements EntityRepository<T> {

    /**
     * The type of class returned by this repository
     */
    @NotNull
    private final Class<T> resultClass;

    /**
     * Holds this instance's {@link SessionFactory} for creating database sessions
     */
    @Getter(AccessLevel.PROTECTED)
    @NotNull
    private final SessionFactory sessionFactory;

    /**
     * Default constructor that sets the repository up for database operation
     *
     * @param sessionFactoryConstructor the {@link MariaDbSessionFactoryConstructor} providing credentials for the database connection
     * @throws org.hibernate.HibernateException in case an error occurs while connecting to the database
     */
    protected MariaDbRepository(final @NotNull MariaDbSessionFactoryConstructor<T> sessionFactoryConstructor) {
        this.sessionFactory = sessionFactoryConstructor.construct();
        this.resultClass = sessionFactoryConstructor.entityClass();
    }

    /**
     * Creates or updates an entity to the database
     *
     * @param entity the entity of type {@link T} to be saved to the database
     * @return the saved entity of type {@link T}
     */
    @Override
    public @NotNull T save(final @NotNull T entity) {
        Transaction transaction = null;
        try (Session session = sessionFactory.openSession()) {
            transaction = session.beginTransaction();
            session.merge(entity);
            transaction.commit();
        } catch (Exception e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new RuntimeException(e);
        }
        return entity;
    }

    /**
     * Finds the first entity of type {@link T} from the database by entry field name and value
     *
     * @param field the name of the field to filter for
     * @param value the value of the field to filter for
     * @return the first entity of type {@link T} or {@code null} if none was found
     */
    @Override
    public @Nullable T findFirst(final @NotNull String field, @Nullable final Object value) {
        try (Session session = sessionFactory.openSession()) {
            CriteriaBuilder builder = session.getCriteriaBuilder();
            CriteriaQuery<T> criteriaQuery = builder.createQuery(resultClass);
            Root<T> root = criteriaQuery.from(resultClass);
            criteriaQuery.select(root).where(builder.equal(root.get(field), value));
            return session.createQuery(criteriaQuery).uniqueResult();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Finds the first entity of type {@link T} from the database by entry id field and value
     * <p>
     * <b>Warning:</b> Use with caution! This method expects the type {@link T} to have a field
     * named "{@code id}" in the database. Other id field names will not be found by this method.
     * </p>
     *
     * @param value the value of the field to filter for
     * @return the first entity of type {@link T} or {@code null} if none was found
     */
    @Override
    public @Nullable T findFirstById(final @Nullable Object value) {
        return findFirst("id", value);
    }

    /**
     * Finds all entities of type {@link T} from the database by entry field name and value
     *
     * @param field the name of the field to filter for
     * @param value the value of the field to filter for
     * @return all entities of type {@link T} contained in a {@link List} or an empty {@link List} if none were found
     */
    @Override
    public @NotNull List<T> findAll(final @NotNull String field, @Nullable final Object value) {
        try (Session session = sessionFactory.openSession()) {
            CriteriaBuilder builder = session.getCriteriaBuilder();
            CriteriaQuery<T> criteriaQuery = builder.createQuery(resultClass);
            Root<T> root = criteriaQuery.from(resultClass);
            criteriaQuery.select(root).where(builder.equal(root.get(field), value));
            return session.createQuery(criteriaQuery).getResultList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Finds all entities of type {@link T} from the database
     *
     * @return all entities of type {@link T} contained in a {@link List} or an empty {@link List} if none were found
     */
    @Override
    public @NotNull List<T> all() {
        try (Session session = sessionFactory.openSession()) {
            CriteriaBuilder builder = session.getCriteriaBuilder();
            CriteriaQuery<T> criteriaQuery = builder.createQuery(resultClass);
            Root<T> root = criteriaQuery.from(resultClass);
            criteriaQuery.select(root);
            return session.createQuery(criteriaQuery).getResultList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Deletes an entity of type {@link T} from the database
     *
     * @param entity the entity of type {@link T} to be deleted
     */
    @Override
    public void delete(final @NotNull T entity) {
        Transaction transaction = null;
        try (Session session = sessionFactory.openSession()) {
            transaction = session.beginTransaction();
            session.remove(entity);
            transaction.commit();
        } catch (Exception e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new RuntimeException(e);
        }
    }
    
}
