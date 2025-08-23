/*
 * Copyright (c) 2024-2025 nordtal.eu.
 *
 * Licensed under the Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International License (CC BY-NC-ND 4.0).
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://creativecommons.org/licenses/by-nc-nd/4.0/legalcode
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.growaction.javacore.persistence.mongodb;

import com.mongodb.client.MongoClients;
import dev.morphia.Datastore;
import dev.morphia.Morphia;
import dev.morphia.query.filters.Filters;
import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.growaction.javacore.persistence.common.EntityRepository;

import java.util.List;

/**
 * This class implements abstract database actions for the MongoDB entities of this project
 *
 * @param <T> the class type of the entity the repository is handling
 */
public abstract class MongoDbRepository<T> implements EntityRepository<T> {

    /**
     * The type of class returned by this repository
     */
    private final Class<T> resultClass;

    /**
     * Holds this instance's datastore for database interactions
     */
    @Getter(AccessLevel.PROTECTED)
    private final Datastore datastore;

    /**
     * Default constructor that sets the repository up for database operation
     *
     * @param credentials the {@link MongoDbCredentials} containing the database information and result class of the repository
     */
    protected MongoDbRepository(final MongoDbCredentials<T> credentials) {
        this.resultClass = credentials.entityClass();
        datastore = Morphia.createDatastore(MongoClients.create(credentials.getUriString()), credentials.database());
    }

    /**
     * Creates or updates an entity to the database
     *
     * @param entity the entity of type {@link T} to be saved to the database
     * @return the saved entity of type {@link T}
     */
    @Override
    public @NotNull T save(@NotNull final T entity) {
        return datastore.save(entity);
    }

    /**
     * Finds the first entity of type {@link T} from the database by entry field name and value
     *
     * @param field the name of the field to filter for
     * @param value the value of the field to filter for
     * @return the first entity of type {@link T} or {@code null} if none was found
     */
    @Override
    public @Nullable T findFirst(final @NotNull String field, final @Nullable Object value) {
        return datastore.find(resultClass)
                .filter(Filters.eq(field, value))
                .first();
    }

    /**
     * Finds the first entity of type {@link T} from the database by entry id field and value
     * <p>
     * <b>Warning:</b> Use with caution! This method expects the type {@link T} to have a field
     * named "{@code _id}" in the database. Other id field names will not be found by this method.
     * </p>
     *
     * @param value the value of the field to filter for
     * @return the first entity of type {@link T} or {@code null} if none was found
     */
    @Override
    public @Nullable T findFirstById(final @Nullable Object value) {
        return findFirst("_id", value);
    }

    /**
     * Finds all entities of type {@link T} from the database by entry field name and value
     *
     * @param field the name of the field to filter for
     * @param value the value of the field to filter for
     * @return all entities of type {@link T} contained in a {@link List} or an empty {@link List} if none were found
     */
    @Override
    public @NotNull List<T> findAll(final @NotNull String field, final @Nullable Object value) {
        return datastore.find(resultClass)
                .filter(Filters.eq(field, value))
                .stream().toList();
    }

    /**
     * Finds all entities of type {@link T} from the database
     *
     * @return all entities of type {@link T} contained in a {@link List} or an empty {@link List} if none were found
     */
    @Override
    public @NotNull List<T> all() {
        return datastore.find(resultClass)
                .stream().toList();
    }

    /**
     * Deletes an entity of type {@link T} from the database
     *
     * @param entity the entity of type {@link T} to be deleted
     */
    @Override
    public void delete(@NotNull final T entity) {
        datastore.delete(entity);
    }

}
